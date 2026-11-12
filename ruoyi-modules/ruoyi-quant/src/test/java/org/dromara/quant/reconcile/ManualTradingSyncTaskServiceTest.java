package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.notification.QuantTradeNotificationService;
import org.dromara.quant.reconcile.domain.QuantTradingSyncTask;
import org.dromara.quant.reconcile.mapper.QuantTradingSyncTaskMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ManualTradingSyncTaskServiceTest {

    private static void initializeTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "manual-sync-task-test"),
            QuantTradingSyncTask.class);
    }

    @Test
    void startReturnsImmediatelyWithPersistentPendingTask() {
        initializeTableInfo();
        var tasks = mock(QuantTradingSyncTaskMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var reconciliation = mock(TradingReconciliationService.class);
        var notifications = mock(QuantTradeNotificationService.class);
        var executor = mock(TaskExecutor.class);
        var queued = new AtomicReference<Runnable>();
        doAnswer(invocation -> {
            queued.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(tasks.selectOne(any())).thenReturn(null);
        when(tasks.insert(any(QuantTradingSyncTask.class))).thenAnswer(invocation -> {
            ((QuantTradingSyncTask) invocation.getArgument(0)).setId(701L);
            return 1;
        });

        var service = new ManualTradingSyncTaskService(tasks, credentials, reconciliation, notifications, executor);
        var view = service.start(10L, "jctusdt");

        assertEquals(701L, view.taskId());
        assertEquals(10L, view.credentialId());
        assertEquals("JCTUSDT", view.requestedSymbol());
        assertEquals("PENDING", view.status());
        assertNotNull(queued.get());
        verify(tasks).insert(any(QuantTradingSyncTask.class));
        verifyNoInteractions(reconciliation, notifications);
    }

    @Test
    void repeatedStartForSameAccountAndScopeReusesActiveTask() {
        initializeTableInfo();
        var tasks = mock(QuantTradingSyncTaskMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var reconciliation = mock(TradingReconciliationService.class);
        var notifications = mock(QuantTradeNotificationService.class);
        var executor = mock(TaskExecutor.class);
        var queued = new AtomicReference<Runnable>();
        doAnswer(invocation -> {
            queued.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
        var existing = new QuantTradingSyncTask();
        existing.setId(702L);
        existing.setCredentialId(10L);
        existing.setRequestedSymbol("BTCUSDT");
        existing.setStatus("RUNNING");
        when(tasks.selectOne(any())).thenReturn(existing);

        var service = new ManualTradingSyncTaskService(tasks, credentials, reconciliation, notifications, executor);
        var view = service.start(10L, " btcusdt ");

        assertEquals(702L, view.taskId());
        assertEquals("RUNNING", view.status());
        verify(tasks, never()).insert(any(QuantTradingSyncTask.class));
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void backgroundWorkerPersistsSuccessAndQueuesSystemNotification() {
        initializeTableInfo();
        var tasks = mock(QuantTradingSyncTaskMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var reconciliation = mock(TradingReconciliationService.class);
        var notifications = mock(QuantTradeNotificationService.class);
        var executor = mock(TaskExecutor.class);
        var queued = new AtomicReference<Runnable>();
        doAnswer(invocation -> {
            queued.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(tasks.selectOne(any())).thenReturn(null);
        when(tasks.insert(any(QuantTradingSyncTask.class))).thenAnswer(invocation -> {
            ((QuantTradingSyncTask) invocation.getArgument(0)).setId(703L);
            return 1;
        });
        when(tasks.update(isNull(), any())).thenReturn(1);

        var credential = new QuantCredential();
        credential.setId(10L);
        credential.setRunMode("TESTNET");
        when(credentials.selectById(10L)).thenReturn(credential);
        var task = new QuantTradingSyncTask();
        task.setId(703L);
        task.setCredentialId(10L);
        task.setRequestedSymbol("BTCUSDT");
        when(tasks.selectById(703L)).thenReturn(task);
        var result = new TradingReconciliationService.TradingDataSyncResult(2, 5, 4);
        when(reconciliation.syncTradingData(eq(credential), eq("BTCUSDT"),
            ArgumentMatchers.<Consumer<TradingReconciliationService.TradingDataSyncProgress>>any())).thenReturn(result);

        var service = new ManualTradingSyncTaskService(tasks, credentials, reconciliation, notifications, executor);
        service.start(10L, "BTCUSDT");
        assertNotNull(queued.get());
        queued.get().run();

        verify(tasks, times(2)).update(isNull(), any());
        verify(notifications).notifyOrderTradeSyncResult(credential, "BTCUSDT", 2, 5, 4);
    }

    @Test
    void differentScopeCannotStartWhileAccountHasActiveTask() {
        initializeTableInfo();
        var tasks = mock(QuantTradingSyncTaskMapper.class);
        var existing = new QuantTradingSyncTask();
        existing.setId(704L);
        existing.setCredentialId(10L);
        existing.setRequestedSymbol("BTCUSDT");
        existing.setStatus("RUNNING");
        when(tasks.selectOne(any())).thenReturn(existing);
        var service = new ManualTradingSyncTaskService(tasks, mock(QuantCredentialMapper.class),
            mock(TradingReconciliationService.class), mock(QuantTradeNotificationService.class), mock(TaskExecutor.class));

        assertThrows(org.dromara.common.core.exception.ServiceException.class,
            () -> service.start(10L, "ETHUSDT"));
        verify(tasks, never()).insert(any(QuantTradingSyncTask.class));
    }
}
