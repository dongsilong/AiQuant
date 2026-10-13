package org.dromara.quant.risk;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.OpenPositionCommand;
import org.dromara.quant.market.AccountBalanceSnapshot;
import org.dromara.quant.risk.mapper.OpenCapacityMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

/**
 * 账户级开仓容量：短事务检查并占位，交易所调用始终在事务外。
 * 占位没有 TTL，未知订单必须通过执行恢复确认，不能按时间释放。
 */
@Service
public class OpenCapacityService {
    private final OpenCapacityMapper mapper;
    private final TransactionTemplate transaction;
    private final RiskDecisionService risk;

    public OpenCapacityService(OpenCapacityMapper mapper, PlatformTransactionManager manager, RiskDecisionService risk) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(5);
        this.risk = risk;
    }

    /** 拒绝原因可直接用于业务提示，不包含凭证。 */
    public static class CapacityBlockedException extends IllegalStateException {
        private final boolean waitForCapacityChange;
        public CapacityBlockedException(String reason) { this(reason, false); }
        public CapacityBlockedException(String reason, boolean waitForCapacityChange) {
            super(reason);
            this.waitForCapacityChange = waitForCapacityChange;
        }
        public boolean waitForCapacityChange() { return waitForCapacityChange; }
    }

    /**
     * freshPositions 是经过完整初始化、私有流维护和定期校准的账户持仓事实；未知状态禁止开仓。
     * PAPER 的持仓事实由本地账本提供。已有占位和持仓按交易对、方向去重。
     */
    public void reserve(OpenPositionCommand command, BigDecimal notional, List<ExchangeGateway.PositionSnapshot> freshPositions) {
        reserve(command, notional, freshPositions, null);
    }

    public void reserve(OpenPositionCommand command, BigDecimal notional, List<ExchangeGateway.PositionSnapshot> freshPositions,
                        AccountBalanceSnapshot balance) {
        checkCapacity(command, notional, freshPositions, balance, true);
    }

    /** 失败重试前复用同一套容量判据；不创建或释放占位，不创建执行记录，不请求交易所。 */
    public void requireCanReserve(OpenPositionCommand command, BigDecimal notional, List<ExchangeGateway.PositionSnapshot> freshPositions,
                                  AccountBalanceSnapshot balance) {
        checkCapacity(command, notional, freshPositions, balance, false);
    }

    private void checkCapacity(OpenPositionCommand command, BigDecimal notional, List<ExchangeGateway.PositionSnapshot> freshPositions,
                               AccountBalanceSnapshot balance, boolean reserve) {
        if (command.credentialId() == null || command.idempotencyKey() == null || command.symbol() == null
            || notional == null || notional.signum() <= 0 || (!"PAPER".equalsIgnoreCase(command.runMode()) && freshPositions == null)) {
            throw new CapacityBlockedException("开仓容量检查缺少有效账户、金额或持仓事实");
        }
        transaction.executeWithoutResult(status -> {
            List<Long> credentials = mapper.lockCredential(command.credentialId());
            if (credentials.isEmpty()) { throw new CapacityBlockedException("开仓账户不存在"); }
            if (!"PAPER".equalsIgnoreCase(command.runMode()) && (balance == null || !balance.fresh()
                || !command.credentialId().equals(balance.credentialId()))) {
                throw new CapacityBlockedException("缺少当前账户的可信可用余额快照，暂停新开仓");
            }
            List<Map<String, Object>> configs = mapper.riskConfigs(command.credentialId());
            if (configs.size() != 1) { throw new CapacityBlockedException("账户风控配置缺失或不唯一"); }
            int accountLimit = positiveLimit(configs.get(0).get("max_open_positions"), "账户最大持仓数");
            int strategyLimit = Integer.MAX_VALUE;
            if (command.strategyId() != null) {
                List<Integer> limits = mapper.strategyLimits(command.strategyId());
                if (limits.size() != 1) { throw new CapacityBlockedException("策略配置不存在"); }
                strategyLimit = positiveLimit(limits.get(0), "策略最大持仓数");
            }
            Set<String> accountKeys = new HashSet<>();
            if (freshPositions != null) {
                for (var position : freshPositions) {
                    if (position.quantity() != null && position.quantity().signum() != 0) {
                        String direction = "BOTH".equalsIgnoreCase(position.side())
                            ? (position.quantity().signum() < 0 ? "SHORT" : "LONG") : position.side();
                        accountKeys.add(key(position.symbol(), direction));
                    }
                }
            }
            for (var position : mapper.activePositions(command.credentialId())) {
                String side = Objects.toString(position.get("position_side"), "");
                Object quantity = position.get("quantity");
                // BOTH 模式的本地空头数量可能为负；不允许被 quantity>0 过滤掉。
                if ("BOTH".equalsIgnoreCase(side) && quantity instanceof BigDecimal qty && qty.signum() < 0) {
                    side = "SHORT";
                } else if ("BOTH".equalsIgnoreCase(side) && quantity instanceof BigDecimal qty && qty.signum() > 0) {
                    side = "LONG";
                }
                accountKeys.add(key(position.get("symbol"), side));
            }
            Set<String> strategyKeys = new HashSet<>();
            // OPEN_PENDING 尚未经过容量准入，只有容量表中已批准的占位参与计数。
            // 升级迁移必须回填旧服务遗留的待确认执行，再整体切换新服务。
            for (var state : mapper.activeStrategyStates(command.credentialId())) {
                String positionKey = key(state.get("symbol"), state.get("direction"));
                accountKeys.add(positionKey);
                if (sameId(state.get("strategy_id"), command.strategyId())) { strategyKeys.add(positionKey); }
            }
            List<Map<String, Object>> slots = mapper.reservedSlots(command.credentialId());
            boolean alreadyReserved = false;
            Set<String> releasable = new HashSet<>();
            for (var slot : slots) {
                String positionKey = key(slot.get("symbol"), slot.get("position_side"));
                boolean failed = "FAILED".equals(slot.get("execution_status"));
                String executionStatus = Objects.toString(slot.get("execution_status"), "");
                String orderStatus = Objects.toString(slot.get("exchange_order_status"), "");
                boolean terminal = Set.of("CONFIRMED", "RECOVERED").contains(executionStatus)
                    && Set.of("FILLED", "CANCELED", "EXPIRED", "REJECTED").contains(orderStatus);
                java.time.LocalDateTime updated = localTime(slot.get("state_updated"));
                java.time.LocalDateTime created = localTime(slot.get("created_at"));
                boolean closed = "CLOSED".equals(slot.get("state_status")) && updated != null && created != null && !updated.isBefore(created);
                // 必须同时确认执行终态、持仓归零和此次开仓之后的关闭事实；不能因初始空仓释放名额。
                if (failed || terminal && closed && !accountKeys.contains(positionKey)) {
                    releasable.add(Objects.toString(slot.get("idempotency_key")));
                    if (reserve) {
                        mapper.release(command.credentialId(), Objects.toString(slot.get("idempotency_key")));
                    }
                    continue;
                }
                accountKeys.add(positionKey);
                if (sameId(slot.get("strategy_id"), command.strategyId())) { strategyKeys.add(positionKey); }
                if (command.idempotencyKey().equals(slot.get("idempotency_key"))) { alreadyReserved = true; }
            }
            if (alreadyReserved) { return; }
            if (command.strategyId() != null && strategyKeys.size() >= strategyLimit) {
                throw new CapacityBlockedException("策略最大持仓数已达到: 已持仓及待确认=" + strategyKeys.size() + ", 上限=" + strategyLimit, true);
            }
            if (accountKeys.size() >= accountLimit) {
                throw new CapacityBlockedException("账户最大持仓数已达到: 已持仓及待确认=" + accountKeys.size() + ", 上限=" + accountLimit, true);
            }
            String direction = "SELL".equalsIgnoreCase(command.side()) ? "SHORT" : "LONG";
            if (accountKeys.contains(key(command.symbol(), direction))) {
                throw new CapacityBlockedException("同账户同交易对方向已有持仓或待确认开仓，禁止重复扩仓: " + command.symbol(), true);
            }
            BigDecimal margin = notional.divide(BigDecimal.valueOf(command.leverage() == null ? 1 : Math.max(1, command.leverage())),
                16, java.math.RoundingMode.UP);
            if (!"PAPER".equalsIgnoreCase(command.runMode()) && margin.compareTo(availableBalance(command.credentialId(), balance, releasable)) > 0) {
                throw new CapacityBlockedException("账户剩余可用保证金不足，已计入并发及待确认开仓预留", true);
            }
            // 用最新配置再次执行其余风险规则，TESTNET/PAPER 也不能绕过。
            try {
                risk.requireOpenApproved(command.credentialId(), command.symbol(), notional, command.runMode(), command.idempotencyKey());
            } catch (org.dromara.common.core.exception.ServiceException ex) {
                throw new CapacityBlockedException(ex.getMessage());
            }
            if (!reserve) { return; }
            mapper.insertReservation(command.credentialId(), command.idempotencyKey(), command.strategyId(),
                command.strategyInstanceId(), command.symbol().toUpperCase(Locale.ROOT), direction, margin);
        });
    }

    /**
     * 在运行时将 CLOSED 改为 OPEN_PENDING 之前，独立提交已确认关闭的历史占位清理。
     * freshPositions 必须来自可信账户缓存；不执行交易所请求，也不释放未知或部分成交执行。
     */
    public void releaseClosedForInstance(Long credentialId, Long instanceId, String symbol,
                                         List<ExchangeGateway.PositionSnapshot> freshPositions) {
        if (credentialId == null || instanceId == null || symbol == null || freshPositions == null) {
            throw new CapacityBlockedException("清理历史开仓预留缺少可信账户持仓事实");
        }
        if (freshPositions.stream().anyMatch(position -> symbol.equalsIgnoreCase(position.symbol())
            && (position.quantity() == null || position.quantity().signum() != 0))) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            List<Long> credentials = mapper.lockCredential(credentialId);
            if (credentials.isEmpty()) { throw new CapacityBlockedException("开仓账户不存在"); }
            Long localCount = mapper.countActiveLocalPositions(credentialId, symbol);
            if (localCount == null || localCount > 0) { return; }
            List<String> closedKeys = mapper.closedKeys(credentialId, instanceId, symbol);
            for (String closedKey : closedKeys) {
                mapper.release(credentialId, closedKey);
            }
        });
    }

    /** 仅在余额请求发出前采集；UNKNOWN 和部分成交不得计入已覆盖集合。 */
    public Set<String> balanceCoverage(Long credentialId) {
        return new HashSet<>(mapper.balanceCoverage(credentialId));
    }

    /** 运行时用于原有比例计算；最终准入必须在账户行锁内再次读取预留。 */
    public BigDecimal availableBalance(Long credentialId, AccountBalanceSnapshot snapshot) {
        return availableBalance(credentialId, snapshot, Set.of());
    }

    private BigDecimal availableBalance(Long credentialId, AccountBalanceSnapshot snapshot, Set<String> releasable) {
        if (snapshot == null || !credentialId.equals(snapshot.credentialId()) || !snapshot.fresh()) {
            throw new CapacityBlockedException("账户余额快照不可信或已过期");
        }
        BigDecimal reserved = BigDecimal.ZERO;
        for (var row : mapper.marginReservations(credentialId)) {
            String key = Objects.toString(row.get("idempotency_key"));
            if (releasable.contains(key) || snapshot.coveredExecutions().contains(key) || "FAILED".equals(row.get("execution_status"))) { continue; }
            if (!(row.get("reserved_margin") instanceof BigDecimal amount) || amount.signum() <= 0) {
                throw new CapacityBlockedException("遗留待确认开仓缺少保证金金额，必须恢复核实后才能新增开仓");
            }
            reserved = reserved.add(amount);
        }
        return snapshot.availableBalance().subtract(reserved).max(BigDecimal.ZERO);
    }

    /** 明确未成交/未发送且执行记录已经标为 FAILED，才允许释放此次占位。 */
    public void releaseFailed(Long credentialId, String idempotencyKey) {
        mapper.releaseFailed(credentialId, idempotencyKey);
    }

    private static java.time.LocalDateTime localTime(Object value) {
        if (value instanceof Timestamp timestamp) { return timestamp.toLocalDateTime(); }
        return value instanceof java.time.LocalDateTime time ? time : null;
    }

    private static int positiveLimit(Object value, String name) {
        if (!(value instanceof Number number) || number.intValue() <= 0) { throw new CapacityBlockedException(name + "必须配置为正整数"); }
        return number.intValue();
    }

    private static boolean sameId(Object value, Long id) { return id != null && value instanceof Number n && n.longValue() == id; }
    private static String key(Object symbol, Object side) { return Objects.toString(symbol, "").toUpperCase(Locale.ROOT) + ":" + Objects.toString(side, "").toUpperCase(Locale.ROOT); }
}
