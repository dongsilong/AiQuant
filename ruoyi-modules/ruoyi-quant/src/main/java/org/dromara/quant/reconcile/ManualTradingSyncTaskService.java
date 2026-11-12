package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.notification.QuantTradeNotificationService;
import org.dromara.quant.reconcile.domain.QuantTradingSyncTask;
import org.dromara.quant.reconcile.mapper.QuantTradingSyncTaskMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动订单与成交历史同步任务协调器。
 *
 * 任务状态持久化在数据库；工作线程只负责执行，不占用 HTTP 请求线程。
 */
@Service
@Slf4j
public class ManualTradingSyncTaskService {
    private static final String PENDING = "PENDING";
    private static final String RUNNING = "RUNNING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final int ERROR_MESSAGE_LIMIT = 1000;

    private final QuantTradingSyncTaskMapper taskMapper;
    private final QuantCredentialMapper credentialMapper;
    private final TradingReconciliationService reconciliationService;
    private final QuantTradeNotificationService notificationService;
    private final TaskExecutor executor;
    private final String workerToken = UUID.randomUUID().toString();
    private final Set<Long> submittedTasks = ConcurrentHashMap.newKeySet();

    public ManualTradingSyncTaskService(QuantTradingSyncTaskMapper taskMapper,
                                        QuantCredentialMapper credentialMapper,
                                        TradingReconciliationService reconciliationService,
                                        QuantTradeNotificationService notificationService,
                                        @Qualifier("quantTradingSyncExecutor") TaskExecutor executor) {
        this.taskMapper = taskMapper;
        this.credentialMapper = credentialMapper;
        this.reconciliationService = reconciliationService;
        this.notificationService = notificationService;
        this.executor = executor;
    }

    public TradingSyncTaskView start(Long credentialId, String requestedSymbol) {
        if (credentialId == null || credentialId <= 0) {
            throw new IllegalArgumentException("交易账户不能为空");
        }
        String normalizedSymbol = normalizeSymbol(requestedSymbol);
        QuantTradingSyncTask existing = findActiveTask(credentialId);
        if (existing != null) {
            return reuseOrReject(existing, normalizedSymbol);
        }

        LocalDateTime now = LocalDateTime.now();
        QuantTradingSyncTask task = new QuantTradingSyncTask();
        task.setCredentialId(credentialId);
        task.setRequestedSymbol(normalizedSymbol);
        task.setStatus(PENDING);
        task.setTotalSymbolCount(0);
        task.setCompletedSymbolCount(0);
        task.setOrderCount(0);
        task.setTradeCount(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            if (taskMapper.insert(task) != 1) {
                throw new ServiceException("同步任务创建失败");
            }
        } catch (DuplicateKeyException concurrentStart) {
            existing = findActiveTask(credentialId);
            if (existing == null) {
                throw concurrentStart;
            }
            return reuseOrReject(existing, normalizedSymbol);
        }

        submit(task.getId());
        return view(task);
    }

    public TradingSyncTaskView get(Long taskId) {
        QuantTradingSyncTask task = taskMapper.selectById(taskId);
        return task == null ? null : view(task);
    }

    private TradingSyncTaskView reuseOrReject(QuantTradingSyncTask existing, String requestedSymbol) {
        if (!Objects.equals(existing.getRequestedSymbol(), requestedSymbol)) {
            throw new ServiceException("该交易账户已有订单与成交同步任务正在执行，任务编号：" + existing.getId());
        }
        submit(existing.getId());
        return view(existing);
    }

    private QuantTradingSyncTask findActiveTask(Long credentialId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<QuantTradingSyncTask>()
            .eq(QuantTradingSyncTask::getCredentialId, credentialId)
            .in(QuantTradingSyncTask::getStatus, PENDING, RUNNING)
            .orderByDesc(QuantTradingSyncTask::getCreatedAt)
            .last("LIMIT 1"));
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String normalized = symbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[\\p{L}\\p{N}_]{2,32}")) {
            throw new IllegalArgumentException("无效的 Binance 交易对");
        }
        return normalized;
    }

    private void submit(Long taskId) {
        if (taskId == null || !submittedTasks.add(taskId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    execute(taskId);
                } finally {
                    submittedTasks.remove(taskId);
                }
            });
        } catch (RuntimeException rejected) {
            submittedTasks.remove(taskId);
            // 任务仍保持 PENDING；定时扫描器会在队列有空位后重新派发。
            log.warn("历史订单与成交同步任务暂未进入执行队列，等待后台重试 taskId={}", taskId, rejected);
        }
    }

    private void execute(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        int claimed = taskMapper.update(null, new LambdaUpdateWrapper<QuantTradingSyncTask>()
            .eq(QuantTradingSyncTask::getId, taskId)
            .eq(QuantTradingSyncTask::getStatus, PENDING)
            .set(QuantTradingSyncTask::getStatus, RUNNING)
            .set(QuantTradingSyncTask::getWorkerToken, workerToken)
            .set(QuantTradingSyncTask::getLeaseUntil, now.plusMinutes(2))
            .set(QuantTradingSyncTask::getStartedAt, now)
            .set(QuantTradingSyncTask::getUpdatedAt, now));
        if (claimed != 1) {
            return;
        }

        QuantTradingSyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.error("已抢占的历史同步任务记录不存在 taskId={}", taskId);
            return;
        }
        QuantCredential credential = credentialMapper.selectById(task.getCredentialId());
        if (credential == null) {
            IllegalStateException failure = new IllegalStateException("交易凭证不存在或已删除");
            if (finishFailure(taskId, failure)) {
                notificationService.notifyOrderTradeSyncFailure(null, task.getRequestedSymbol(), failure);
            }
            return;
        }

        try {
            TradingReconciliationService.TradingDataSyncResult result = reconciliationService.syncTradingData(
                credential, task.getRequestedSymbol(), progress -> updateProgress(taskId, progress));
            if (finishSuccess(taskId, result)) {
                notificationService.notifyOrderTradeSyncResult(credential, task.getRequestedSymbol(),
                    result.symbolCount(), result.orderCount(), result.tradeCount());
            }
        } catch (RuntimeException failure) {
            if (finishFailure(taskId, failure)) {
                notificationService.notifyOrderTradeSyncFailure(credential, task.getRequestedSymbol(), failure);
            }
            log.error("后台订单与成交历史同步失败 taskId={} credentialId={}", taskId, task.getCredentialId(), failure);
        }
    }

    private void updateProgress(Long taskId, TradingReconciliationService.TradingDataSyncProgress progress) {
        try {
            taskMapper.update(null, new LambdaUpdateWrapper<QuantTradingSyncTask>()
                .eq(QuantTradingSyncTask::getId, taskId)
                .eq(QuantTradingSyncTask::getStatus, RUNNING)
                .eq(QuantTradingSyncTask::getWorkerToken, workerToken)
                .set(QuantTradingSyncTask::getTotalSymbolCount, progress.totalSymbolCount())
                .set(QuantTradingSyncTask::getCompletedSymbolCount, progress.completedSymbolCount())
                .set(QuantTradingSyncTask::getCurrentSymbol, progress.currentSymbol())
                .set(QuantTradingSyncTask::getOrderCount, progress.orderCount())
                .set(QuantTradingSyncTask::getTradeCount, progress.tradeCount())
                .set(QuantTradingSyncTask::getUpdatedAt, LocalDateTime.now()));
        } catch (RuntimeException ex) {
            // 进度展示故障不能中断订单/成交事实同步。
            log.warn("更新历史订单与成交同步进度失败 taskId={}", taskId, ex);
        }
    }

    private boolean finishSuccess(Long taskId, TradingReconciliationService.TradingDataSyncResult result) {
        LocalDateTime now = LocalDateTime.now();
        return taskMapper.update(null, new LambdaUpdateWrapper<QuantTradingSyncTask>()
            .eq(QuantTradingSyncTask::getId, taskId)
            .eq(QuantTradingSyncTask::getStatus, RUNNING)
            .eq(QuantTradingSyncTask::getWorkerToken, workerToken)
            .set(QuantTradingSyncTask::getStatus, SUCCESS)
            .set(QuantTradingSyncTask::getTotalSymbolCount, result.symbolCount())
            .set(QuantTradingSyncTask::getCompletedSymbolCount, result.symbolCount())
            .set(QuantTradingSyncTask::getOrderCount, result.orderCount())
            .set(QuantTradingSyncTask::getTradeCount, result.tradeCount())
            .set(QuantTradingSyncTask::getCurrentSymbol, null)
            .set(QuantTradingSyncTask::getErrorMessage, null)
            .set(QuantTradingSyncTask::getCompletedAt, now)
            .set(QuantTradingSyncTask::getUpdatedAt, now)
            .set(QuantTradingSyncTask::getWorkerToken, null)
            .set(QuantTradingSyncTask::getLeaseUntil, null)) == 1;
    }

    private boolean finishFailure(Long taskId, RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        if (message.length() > ERROR_MESSAGE_LIMIT) {
            message = message.substring(0, ERROR_MESSAGE_LIMIT) + "...";
        }
        LocalDateTime now = LocalDateTime.now();
        return taskMapper.update(null, new LambdaUpdateWrapper<QuantTradingSyncTask>()
            .eq(QuantTradingSyncTask::getId, taskId)
            .eq(QuantTradingSyncTask::getStatus, RUNNING)
            .eq(QuantTradingSyncTask::getWorkerToken, workerToken)
            .set(QuantTradingSyncTask::getStatus, FAILED)
            .set(QuantTradingSyncTask::getErrorMessage, message)
            .set(QuantTradingSyncTask::getCompletedAt, now)
            .set(QuantTradingSyncTask::getUpdatedAt, now)
            .set(QuantTradingSyncTask::getWorkerToken, null)
            .set(QuantTradingSyncTask::getLeaseUntil, null)) == 1;
    }

    /**
     * 续租本节点正在运行的任务；进程退出后租约到期，其他节点可重新派发。
     */
    @Scheduled(fixedDelay = 30000L, initialDelay = 30000L)
    public void renewRunningTaskLeases() {
        LocalDateTime now = LocalDateTime.now();
        taskMapper.update(null, new LambdaUpdateWrapper<QuantTradingSyncTask>()
            .eq(QuantTradingSyncTask::getStatus, RUNNING)
            .eq(QuantTradingSyncTask::getWorkerToken, workerToken)
            .set(QuantTradingSyncTask::getLeaseUntil, now.plusMinutes(2))
            .set(QuantTradingSyncTask::getUpdatedAt, now));
    }

    /**
     * 恢复执行队列拒绝或服务重启后留下的待执行/租约过期任务。
     */
    @Scheduled(fixedDelay = 15000L, initialDelay = 15000L)
    public void recoverPendingTasks() {
        LocalDateTime now = LocalDateTime.now();
        taskMapper.update(null, new LambdaUpdateWrapper<QuantTradingSyncTask>()
            .eq(QuantTradingSyncTask::getStatus, RUNNING)
            .isNotNull(QuantTradingSyncTask::getLeaseUntil)
            .lt(QuantTradingSyncTask::getLeaseUntil, now)
            .set(QuantTradingSyncTask::getStatus, PENDING)
            .set(QuantTradingSyncTask::getWorkerToken, null)
            .set(QuantTradingSyncTask::getLeaseUntil, null)
            .set(QuantTradingSyncTask::getUpdatedAt, now));

        List<QuantTradingSyncTask> pending = taskMapper.selectList(new LambdaQueryWrapper<QuantTradingSyncTask>()
            .eq(QuantTradingSyncTask::getStatus, PENDING)
            .orderByAsc(QuantTradingSyncTask::getCreatedAt)
            .last("LIMIT 50"));
        for (QuantTradingSyncTask task : pending) {
            submit(task.getId());
        }
    }

    private static TradingSyncTaskView view(QuantTradingSyncTask task) {
        return new TradingSyncTaskView(task.getId(), task.getCredentialId(), task.getRequestedSymbol(), task.getStatus(),
            value(task.getTotalSymbolCount()), value(task.getCompletedSymbolCount()), value(task.getOrderCount()),
            value(task.getTradeCount()), task.getCurrentSymbol(), task.getErrorMessage(),
            task.getCreatedAt(), task.getStartedAt(), task.getCompletedAt());
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    public record TradingSyncTaskView(Long taskId, Long credentialId, String requestedSymbol, String status,
                                      int totalSymbolCount, int completedSymbolCount, int orderCount, int tradeCount,
                                      String currentSymbol, String errorMessage, LocalDateTime createdAt,
                                      LocalDateTime startedAt, LocalDateTime completedAt) {
    }
}
