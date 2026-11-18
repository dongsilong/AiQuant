package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.dromara.quant.execution.mapper.LiveRecoveryApprovalMapper;
import org.dromara.quant.execution.mapper.LiveRecoveryAuditMapper;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.dromara.quant.risk.mapper.OpenCapacityMapper;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.dromara.quant.runtime.mapper.QuantStrategyPositionStateMapper;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * TESTNET 专用的 OPEN/UNKNOWN 未执行人工恢复。
 * LIVE 精确订单恢复路径保持独立；本服务只有在交易所历史事实完整且无目标订单/成交时才允许释放阻断。
 */
@Service
public class TestnetOpenNotExecutedRecoveryService {
    private static final Duration MAX_HISTORY_AGE = Duration.ofHours(72);
    private final LiveRecoveryApprovalMapper cases;
    private final LiveRecoveryAuditMapper audit;
    private final LiveOrderDispatchMapper dispatch;
    private final QuantExecutionRecordMapper executions;
    private final QuantCredentialMapper credentials;
    private final StrategyOrderIntentMapper intents;
    private final QuantStrategyPositionStateMapper positionStates;
    private final QuantOrderMapper orders;
    private final OpenCapacityMapper capacity;
    private final ExchangeGateway gateway;
    private final TransactionTemplate transaction;

    public TestnetOpenNotExecutedRecoveryService(LiveRecoveryApprovalMapper cases, LiveRecoveryAuditMapper audit,
            LiveOrderDispatchMapper dispatch, QuantExecutionRecordMapper executions, QuantCredentialMapper credentials,
            StrategyOrderIntentMapper intents, QuantStrategyPositionStateMapper positionStates, QuantOrderMapper orders, OpenCapacityMapper capacity,
            ExchangeGateway gateway, PlatformTransactionManager manager) {
        this.cases = Objects.requireNonNull(cases);
        this.audit = Objects.requireNonNull(audit);
        this.dispatch = Objects.requireNonNull(dispatch);
        this.executions = Objects.requireNonNull(executions);
        this.credentials = Objects.requireNonNull(credentials);
        this.intents = Objects.requireNonNull(intents);
        this.positionStates = Objects.requireNonNull(positionStates);
        this.orders = Objects.requireNonNull(orders);
        this.capacity = Objects.requireNonNull(capacity);
        this.gateway = Objects.requireNonNull(gateway);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setTimeout(12);
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static String text(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof String result) || result.isBlank()) {
            throw new IllegalStateException("恢复工单缺少 " + field);
        }
        return result;
    }

    private static long number(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof Number result) || result.longValue() <= 0) {
            throw new IllegalStateException("恢复工单缺少有效 " + field);
        }
        return result.longValue();
    }

    private static boolean sameId(Object value, Long expected) {
        return expected != null && value instanceof Number actual && actual.longValue() == expected;
    }

    /**
     * 重新核验 TESTNET 开仓事实；任何查询失败、数据不完整或状态冲突都会保留 UNKNOWN 和派发阻断。
     */
    public void execute(long caseId, long actorId, String evidenceHash, String detailHash) {
        if (caseId <= 0 || actorId <= 0 || !validHash(evidenceHash) || !validHash(detailHash)) {
            throw new IllegalArgumentException("恢复需要有效工单、执行人及证据摘要");
        }
        Map<String, Object> recoveryCase = cases.findCase(caseId);
        if (recoveryCase == null || !"APPROVED".equals(recoveryCase.get("state"))
            || !evidenceHash.equalsIgnoreCase(String.valueOf(recoveryCase.get("evidence_sha256")))) {
            throw new IllegalStateException("恢复工单未获独立审批或证据摘要不匹配");
        }
        long requester = number(recoveryCase, "requested_by");
        long reviewer = number(recoveryCase, "reviewed_by");
        if (actorId == requester || actorId == reviewer || requester == reviewer) {
            throw new IllegalStateException("恢复执行人必须与申请人、审批人均不同");
        }
        long credentialId = number(recoveryCase, "credential_id");
        String symbol = text(recoveryCase, "symbol").toUpperCase(Locale.ROOT);
        String key = text(recoveryCase, "dispatch_key");
        String clientOrderId = text(recoveryCase, "expected_client_order_id");
        if (!"OPEN".equals(recoveryCase.get("operation_type")) || recoveryCase.get("expected_exchange_order_id") != null) {
            throw new IllegalStateException("仅允许恢复无交易所订单 ID 的 OPEN 执行");
        }

        var credential = credentials.selectById(credentialId);
        if (credential == null || !"TESTNET".equalsIgnoreCase(credential.getRunMode())) {
            throw new IllegalStateException("该恢复流程仅适用于 TESTNET 凭证");
        }
        ExchangeGateway.Credential exchangeCredential = new ExchangeGateway.Credential(
            credential.getApiKey(), credential.getSecretKey(), credential.getSignatureType(), credential.getRunMode());

        QuantExecutionRecord record = executions.selectOne(new LambdaQueryWrapper<QuantExecutionRecord>()
            .eq(QuantExecutionRecord::getCredentialId, credentialId)
            .eq(QuantExecutionRecord::getSymbol, symbol)
            .eq(QuantExecutionRecord::getIdempotencyKey, key));
        if (record == null || !"OPEN".equals(record.getOperationType()) || !"UNKNOWN".equals(record.getExecutionStatus())
            || record.getExchangeOrderId() != null || !clientOrderId.equals(record.getClientOrderId())
            || record.getStrategyId() == null || record.getStrategyInstanceId() == null || record.getCreatedAt() == null) {
            throw new IllegalStateException("本地执行账本不是匹配的 OPEN/UNKNOWN 未确认记录");
        }
        QuantOrder localOrder = orders.selectOne(new LambdaQueryWrapper<QuantOrder>()
            .eq(QuantOrder::getCredentialId, credentialId)
            .eq(QuantOrder::getSymbol, symbol)
            .eq(QuantOrder::getClientOrderId, clientOrderId));
        if (localOrder != null) {
            throw new IllegalStateException("本地订单账本已存在目标客户端订单号，不能按未执行恢复");
        }

        Instant now = Instant.now();
        Instant createdAt = record.getCreatedAt().toInstant(ZoneOffset.UTC);
        if (!createdAt.isAfter(now.minus(MAX_HISTORY_AGE).plusSeconds(10)) || createdAt.isAfter(now.plusSeconds(5))) {
            throw new IllegalStateException("执行意图超出 Binance 可证明的三天历史保留窗口");
        }

        Map<String, Object> active = dispatch.activeDispatch(credentialId, symbol);
        boolean dispatchTicketPresent;
        if (active == null) {
            // 历史记录可能产生于持久化派发票据机制之前；只有 guard 行确实存在且已空、
            // 同时目标幂等键完全没有票据时，才允许走无票据恢复分支。
            if (dispatch.inactiveGuardCount(credentialId, symbol) != 1
                || dispatch.ticketCount(credentialId, symbol, key) != 0) {
                throw new IllegalStateException("派发锁未明确处于空闲状态，或目标派发票据仍然存在");
            }
            dispatchTicketPresent = false;
        } else {
            if (!key.equals(active.get("dispatchKey")) || !clientOrderId.equals(active.get("clientOrderId"))
                || !"OPEN".equals(active.get("operationType")) || !"UNKNOWN".equals(active.get("dispatchStatus"))
                || active.get("exchangeOrderId") != null
                || !sameId(active.get("strategyInstanceId"), record.getStrategyInstanceId())) {
                throw new IllegalStateException("账户交易对派发锁或票据与恢复目标不一致");
            }
            dispatchTicketPresent = true;
        }

        Map<String, Object> pre = intents.pending(credentialId, key);
        if (pre == null || !sameId(pre.get("strategy_id"), record.getStrategyId())
            || !sameId(pre.get("strategy_instance_id"), record.getStrategyInstanceId())
            || !symbol.equalsIgnoreCase(Objects.toString(pre.get("symbol"), ""))
            || !clientOrderId.equals(pre.get("client_order_id")) || !"OPEN".equals(pre.get("operation_type"))
            || !(pre.get("position_cycle_key") instanceof String cycleKey) || cycleKey.isBlank()
            || !Set.of("LONG", "SHORT").contains(Objects.toString(pre.get("position_side"), ""))
            || !("LONG".equals(pre.get("position_side")) ? "BUY" : "SELL").equals(pre.get("exchange_side"))) {
            throw new IllegalStateException("不可变发单前意图缺失或与执行账本冲突");
        }

        QuantStrategyPositionState state = positionStates.selectOne(new LambdaQueryWrapper<QuantStrategyPositionState>()
            .eq(QuantStrategyPositionState::getInstanceId, record.getStrategyInstanceId()));
        if (state == null || !"OPEN_PENDING".equals(state.getStatus()) || !"OPEN".equals(state.getPendingAction())
            || !key.equals(state.getPendingIdempotencyKey()) || !clientOrderId.equals(state.getPendingClientOrderId())
            || state.getPendingOrderId() != null || state.getPositionId() != null || state.getPositionQty() == null
            || state.getPositionQty().signum() <= 0 || state.getVersion() == null
            || !Boolean.TRUE.equals(state.getRecoveryRequired()) || !Boolean.TRUE.equals(state.getUnsafeRecovery())
            || !cycleKey.equals(state.getPositionCycleKey()) || !pre.get("position_side").equals(state.getDirection())) {
            throw new IllegalStateException("策略 OPEN_PENDING 状态与发单前意图不一致");
        }

        // -2013/空结果只是一项证据；还必须通过活动订单、完整历史订单、完整成交及账户持仓核验。
        if (gateway.queryOrder(exchangeCredential, symbol, null, clientOrderId) != null) {
            throw new IllegalStateException("交易所精确查询已找到目标订单，不能按未执行恢复");
        }
        var activeOrders = gateway.queryOrders(exchangeCredential, symbol);
        if (activeOrders == null || activeOrders.stream().anyMatch(order -> order == null || order.symbol() == null
            || !symbol.equalsIgnoreCase(order.symbol()) || order.orderId() == null || order.orderId() <= 0
            || order.clientOrderId() == null || order.clientOrderId().isBlank()
            || clientOrderId.equals(order.clientOrderId()))) {
            throw new IllegalStateException("活动订单查询失败或仍存在目标客户端订单号");
        }

        Instant historyStart = createdAt.minusSeconds(5);
        ExchangeGateway.TimeRange range = new ExchangeGateway.TimeRange(historyStart, now);
        var history = gateway.queryOrderHistoryComplete(exchangeCredential, symbol, range, 1000L);
        if (history == null || history.stream().anyMatch(order -> order == null || order.symbol() == null
            || !symbol.equalsIgnoreCase(order.symbol()) || order.createTime() == null
            || order.clientOrderId() == null || order.clientOrderId().isBlank()
            || clientOrderId.equals(order.clientOrderId()))) {
            throw new IllegalStateException("历史订单查询不完整、身份字段缺失或已发现目标订单");
        }
        Map<Long, ExchangeGateway.OrderSnapshot> historicalOrders = new HashMap<>();
        for (var order : history) {
            if (order.orderId() == null || order.orderId() <= 0 || historicalOrders.putIfAbsent(order.orderId(), order) != null) {
                throw new IllegalStateException("历史订单存在缺失或重复的交易所订单 ID");
            }
        }
        var trades = gateway.queryTrades(exchangeCredential, symbol, range);
        if (trades == null || trades.stream().anyMatch(trade -> trade == null || trade.tradeId() == null
            || trade.symbol() == null || !symbol.equalsIgnoreCase(trade.symbol()) || trade.time() == null
            || trade.time().isBefore(historyStart) || trade.time().isAfter(now) || trade.orderId() == null
            || !historicalOrders.containsKey(trade.orderId()))) {
            throw new IllegalStateException("历史成交查询不完整，或成交无法关联到完整历史订单");
        }

        var positions = gateway.queryPositions(exchangeCredential, symbol);
        if (positions == null || positions.stream().anyMatch(position -> position == null || position.symbol() == null
            || symbol.equalsIgnoreCase(position.symbol()) && (position.quantity() == null || position.quantity().signum() != 0))) {
            throw new IllegalStateException("交易所当前持仓不是可确认的空仓状态");
        }
        Long localPositionCount = capacity.countActiveLocalPositions(credentialId, symbol);
        if (localPositionCount == null || localPositionCount != 0L) {
            throw new IllegalStateException("本地仍存在该交易对持仓，拒绝释放开仓容量");
        }

        String normalizedEvidenceHash = evidenceHash.toLowerCase(Locale.ROOT);
        String normalizedDetailHash = detailHash.toLowerCase(Locale.ROOT);
        LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime stateUpdatedAt = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        transaction.executeWithoutResult(status -> {
            if (!dispatchTicketPresent) {
                Map<String, Object> lockedGuard = dispatch.lockGuardState(credentialId, symbol);
                if (lockedGuard == null || !sameId(lockedGuard.get("inactive"), 1L)
                    || dispatch.ticketCount(credentialId, symbol, key) != 0) {
                    throw new IllegalStateException("历史恢复执行期间派发锁或票据状态发生变化，事务回滚");
                }
            }
            if (executions.markNotExecutedAfterVerifiedAbsence(record.getId(), credentialId, symbol, key, clientOrderId,
                    "TESTNET 人工核验：精确订单、活动订单、完整历史订单及成交均未发现目标开仓", updatedAt) != 1
                || positionStates.closeUnexecutedOpenPending(state.getId(), state.getInstanceId(), state.getVersion(), key, clientOrderId, cycleKey, stateUpdatedAt) != 1
                || cases.markExecuted(caseId, normalizedEvidenceHash) != 1
                || (dispatchTicketPresent && dispatch.markNotExecuted(credentialId, symbol, key, clientOrderId, record.getStrategyInstanceId()) != 1)
                || (dispatchTicketPresent && dispatch.release(credentialId, symbol, key) != 1)) {
                throw new IllegalStateException("TESTNET 未执行恢复 CAS 失败，事务回滚并保留派发阻断");
            }
            int released = capacity.releaseFailed(credentialId, key);
            var remainingReservations = capacity.reservedSlots(credentialId);
            if (released < 0 || released > 1 || remainingReservations == null
                || remainingReservations.stream().anyMatch(row -> key.equals(row.get("idempotency_key")))) {
                throw new IllegalStateException("开仓容量预留仍未可靠释放，事务回滚");
            }
            if (audit.append(IdWorker.getId(), caseId, actorId, "EXECUTE_NOT_EXECUTED", normalizedEvidenceHash, normalizedDetailHash) != 1) {
                throw new IllegalStateException("TESTNET 未执行恢复审计写入失败，事务回滚并保留派发阻断");
            }
        });
    }
}
