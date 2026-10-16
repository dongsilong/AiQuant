package org.dromara.quant.risk;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.OpenPositionCommand;
import org.dromara.quant.market.AccountBalanceSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 账户级开仓容量：短事务检查并占位，交易所调用始终在事务外。
 * 占位没有 TTL，未知订单必须通过执行恢复确认，不能按时间释放。
 */
@Service
public class OpenCapacityService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RiskDecisionService risk;
    private final ExchangeGateway gateway;

    public OpenCapacityService(JdbcTemplate jdbc, PlatformTransactionManager manager, RiskDecisionService risk, ExchangeGateway gateway) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(3);
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(5);
        this.risk = risk;
        this.gateway = gateway;
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
        if (reserve && command.credential() != null && !"PAPER".equalsIgnoreCase(command.runMode())) {
            repairLegacyReservations(command.credentialId(), command.credential(), freshPositions);
        }
        if (command.credentialId() == null || command.idempotencyKey() == null || command.symbol() == null
            || notional == null || notional.signum() <= 0 || (!"PAPER".equalsIgnoreCase(command.runMode()) && freshPositions == null)) {
            throw new CapacityBlockedException("开仓容量检查缺少有效账户、金额或持仓事实");
        }
        transaction.executeWithoutResult(status -> {
            List<Long> credentials = jdbc.query("SELECT id FROM quant_credential WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), command.credentialId());
            if (credentials.isEmpty()) { throw new CapacityBlockedException("开仓账户不存在"); }
            if (!"PAPER".equalsIgnoreCase(command.runMode()) && (balance == null || !balance.fresh()
                || !command.credentialId().equals(balance.credentialId()))) {
                throw new CapacityBlockedException("缺少当前账户的可信可用余额快照，暂停新开仓");
            }
            List<Map<String, Object>> configs = jdbc.queryForList("SELECT max_open_positions FROM quant_risk_config WHERE credential_id=?", command.credentialId());
            if (configs.size() != 1) { throw new CapacityBlockedException("账户风控配置缺失或不唯一"); }
            int accountLimit = positiveLimit(configs.get(0).get("max_open_positions"), "账户最大持仓数");
            int strategyLimit = Integer.MAX_VALUE;
            if (command.strategyId() != null) {
                List<Integer> limits = jdbc.query("SELECT max_open_count FROM quant_strategy WHERE id=?",
                    (rs, row) -> (Integer) rs.getObject(1), command.strategyId());
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
            for (var position : jdbc.queryForList("SELECT symbol, position_side FROM quant_position WHERE credential_id=? AND quantity>0", command.credentialId())) {
                accountKeys.add(key(position.get("symbol"), position.get("position_side")));
            }
            Set<String> strategyKeys = new HashSet<>();
            // OPEN_PENDING 尚未经过容量准入，只有容量表中已批准的占位参与计数。
            // 升级迁移必须回填旧服务遗留的待确认执行，再整体切换新服务。
            for (var state : jdbc.queryForList("SELECT i.strategy_id, q.symbol, s.direction FROM quant_strategy_position_state s "
                + "JOIN quant_strategy_instance i ON i.id=s.instance_id JOIN quant_strategy_symbol b ON b.id=i.strategy_symbol_id "
                + "JOIN quant_symbol q ON q.id=b.symbol_id WHERE i.credential_id=? AND s.status NOT IN ('CLOSED','OPEN_PENDING') AND s.position_qty>0", command.credentialId())) {
                String positionKey = key(state.get("symbol"), state.get("direction"));
                accountKeys.add(positionKey);
                if (sameId(state.get("strategy_id"), command.strategyId())) { strategyKeys.add(positionKey); }
            }
            List<Map<String, Object>> slots = jdbc.queryForList("SELECT r.*, e.execution_status, e.exchange_order_status, s.status AS state_status, s.updated_at AS state_updated "
                + "FROM quant_open_capacity r LEFT JOIN quant_execution_record e ON e.credential_id=r.credential_id AND e.idempotency_key=r.idempotency_key "
                + "LEFT JOIN quant_strategy_position_state s ON s.instance_id=r.instance_id WHERE r.credential_id=? AND r.status='RESERVED'", command.credentialId());
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
                boolean closed = slot.get("state_status") == null || "CLOSED".equals(slot.get("state_status"))
                    || ("FILLED".equalsIgnoreCase(orderStatus) && !accountKeys.contains(positionKey));
                // 必须同时确认执行终态、持仓归零和此次开仓之后的关闭事实；不能因初始空仓释放名额。
                if (failed || terminal && closed && !accountKeys.contains(positionKey)) {
                    releasable.add(Objects.toString(slot.get("idempotency_key")));
                    if (reserve) {
                        jdbc.update("UPDATE quant_open_capacity SET status='RELEASED', updated_at=CURRENT_TIMESTAMP WHERE credential_id=? AND idempotency_key=? AND status='RESERVED'",
                            command.credentialId(), slot.get("idempotency_key"));
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
            jdbc.update("INSERT INTO quant_open_capacity (credential_id, idempotency_key, strategy_id, instance_id, symbol, position_side, reserved_margin, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", command.credentialId(), command.idempotencyKey(),
                command.strategyId(), command.strategyInstanceId(), command.symbol().toUpperCase(Locale.ROOT), direction, margin);
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
            List<Long> credentials = jdbc.query("SELECT id FROM quant_credential WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), credentialId);
            if (credentials.isEmpty()) { throw new CapacityBlockedException("开仓账户不存在"); }
            Long localCount = jdbc.queryForObject("SELECT COUNT(*) FROM quant_position WHERE credential_id=? AND symbol=? "
                + "AND (quantity<>0 OR quantity IS NULL)", Long.class, credentialId, symbol);
            if (localCount == null || localCount > 0) { return; }
            List<String> closedKeys = jdbc.query("SELECT r.idempotency_key FROM quant_open_capacity r "
                + "JOIN quant_execution_record e ON e.credential_id=r.credential_id AND e.idempotency_key=r.idempotency_key "
                + "JOIN quant_strategy_position_state s ON s.instance_id=r.instance_id "
                + "WHERE r.credential_id=? AND r.instance_id=? AND r.symbol=? AND r.status='RESERVED' "
                + "AND e.execution_status IN ('CONFIRMED','RECOVERED') "
                + "AND e.exchange_order_status IN ('FILLED','CANCELED','EXPIRED','REJECTED') "
                + "AND s.status='CLOSED' AND s.pending_action IS NULL",
                (rs, row) -> rs.getString(1), credentialId, instanceId, symbol);
            for (String closedKey : closedKeys) {
                jdbc.update("UPDATE quant_open_capacity SET status='RELEASED',updated_at=CURRENT_TIMESTAMP "
                    + "WHERE credential_id=? AND idempotency_key=? AND status='RESERVED'", credentialId, closedKey);
            }
        });
    }

    /**
     * 修复历史版本留下的 RESERVED 占位。只有确认无当前持仓且无交易所活动/历史订单，
     * 或已有本地终态事实时才释放；网络核实失败保持阻断。
     */
    private void repairLegacyReservations(Long credentialId, ExchangeGateway.Credential credential,
                                          List<ExchangeGateway.PositionSnapshot> freshPositions) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT r.idempotency_key,r.symbol,r.created_at,r.reserved_margin,"
                + "e.execution_status,e.exchange_order_status,e.client_order_id,e.exchange_order_id,"
                + "q.client_order_id AS ledger_client_order_id,q.status AS ledger_status "
                + "FROM quant_open_capacity r "
                + "LEFT JOIN quant_execution_record e ON e.credential_id=r.credential_id AND e.idempotency_key=r.idempotency_key "
                + "LEFT JOIN quant_order q ON q.credential_id=r.credential_id AND q.idempotency_key=r.idempotency_key "
                + "WHERE r.credential_id=? AND r.status='RESERVED' "
                + "AND (r.reserved_margin IS NULL OR e.idempotency_key IS NULL)", credentialId);
        if (rows.isEmpty()) return;

        Map<String, List<ExchangeGateway.OrderSnapshot>> currentCache = new HashMap<>();
        Map<String, List<ExchangeGateway.OrderSnapshot>> historyCache = new HashMap<>();
        Instant now = Instant.now();
        int released = 0;

        for (Map<String, Object> row : rows) {
            String symbol = Objects.toString(row.get("symbol"), "");
            String key = Objects.toString(row.get("idempotency_key"), "");
            if (symbol.isBlank() || key.isBlank()) continue;

            boolean hasPosition = freshPositions.stream().anyMatch(pos -> symbol.equalsIgnoreCase(pos.symbol())
                && pos.quantity() != null && pos.quantity().signum() != 0);
            if (hasPosition) {
                log.warn("遗留开仓占位仍有真实持仓，禁止释放 credentialId={} symbol={} key={}", credentialId, symbol, key);
                continue;
            }

            String executionStatus = Objects.toString(row.get("execution_status"), "");
            String orderStatus = Objects.toString(row.get("exchange_order_status"), "");
            String ledgerStatus = Objects.toString(row.get("ledger_status"), "");
            if ("FAILED".equalsIgnoreCase(executionStatus)
                || terminalExecution(executionStatus, orderStatus)
                || terminalOrderStatus(ledgerStatus)) {
                releaseReservation(credentialId, key, "LOCAL_TERMINAL");
                released++;
                continue;
            }

            String clientOrderId = firstText(row.get("client_order_id"), row.get("ledger_client_order_id"));
            List<ExchangeGateway.OrderSnapshot> openOrders =
                currentCache.computeIfAbsent(symbol, x -> safeQueryOrders(credential, x));
            if (openOrders == null) {
                continue;
            }
            if (matches(openOrders, clientOrderId, row.get("exchange_order_id"))) {
                log.warn("遗留开仓占位对应交易所仍有活动订单，继续阻断 credentialId={} symbol={} key={}",
                    credentialId, symbol, key);
                continue;
            }

            Instant sevenDaysAgo = now.minus(Duration.ofDays(7));
            List<ExchangeGateway.OrderSnapshot> history =
                historyCache.computeIfAbsent(symbol, x -> safeQueryHistory(credential, x, sevenDaysAgo, now));
            if (history == null) {
                continue;
            }

            ExchangeGateway.OrderSnapshot matched =
                findMatch(history, clientOrderId, row.get("exchange_order_id"));
            if (matched != null) {
                if (terminalOrderStatus(matched.status())) {
                    releaseReservation(credentialId, key, "EXCHANGE_TERMINAL");
                    released++;
                } else {
                    log.warn("遗留开仓占位对应交易所订单尚未终态，继续阻断 credentialId={} symbol={} status={} key={}",
                        credentialId, symbol, matched.status(), key);
                }
                continue;
            }

            // 当前挂单和可查询历史中均没有对应订单，同时交易所当前没有持仓，释放孤立遗留占位。
            releaseReservation(credentialId, key, "NO_EXCHANGE_ORDER");
            released++;
        }

        if (released > 0) {
            log.info("遗留开仓占位恢复完成 credentialId={} released={}", credentialId, released);
        }
    }

    private void releaseReservation(Long credentialId, String key, String reason) {
        if (key == null || key.isBlank()) return;
        int count = jdbc.update(
            "UPDATE quant_open_capacity SET status='RELEASED',updated_at=CURRENT_TIMESTAMP "
                + "WHERE credential_id=? AND idempotency_key=? AND status='RESERVED'",
            credentialId, key);
        if (count > 0) {
            log.info("释放遗留开仓占位 credentialId={} key={} reason={}", credentialId, key, reason);
        }
    }

    private List<ExchangeGateway.OrderSnapshot> safeQueryOrders(ExchangeGateway.Credential credential, String symbol) {
        try {
            return Optional.ofNullable(gateway.queryOrders(credential, symbol)).orElse(List.of());
        } catch (RuntimeException ex) {
            log.warn("遗留开仓占位查询当前订单失败 symbol={} reason={}", symbol, ex.getMessage());
            return null;
        }
    }

    private List<ExchangeGateway.OrderSnapshot> safeQueryHistory(ExchangeGateway.Credential credential, String symbol,
                                                                  Instant from, Instant to) {
        try {
            return Optional.ofNullable(gateway.queryOrderHistory(
                credential, symbol, new ExchangeGateway.TimeRange(from, to), 1000L)).orElse(List.of());
        } catch (RuntimeException ex) {
            log.warn("遗留开仓占位查询订单历史失败 symbol={} reason={}", symbol, ex.getMessage());
            return null;
        }
    }

    private static ExchangeGateway.OrderSnapshot findMatch(List<ExchangeGateway.OrderSnapshot> orders,
                                                            String clientOrderId, Object exchangeOrderId) {
        if (orders == null) return null;
        return orders.stream().filter(o ->
            clientOrderId != null && clientOrderId.equals(o.clientOrderId())
                || exchangeOrderId instanceof Number n
                    && n.longValue() == (o.orderId() == null ? Long.MIN_VALUE : o.orderId()))
            .findFirst().orElse(null);
    }

    private static boolean matches(List<ExchangeGateway.OrderSnapshot> orders, String clientOrderId,
                                   Object exchangeOrderId) {
        return findMatch(orders, clientOrderId, exchangeOrderId) != null;
    }

    private static boolean terminalExecution(String executionStatus, String orderStatus) {
        return ("CONFIRMED".equalsIgnoreCase(executionStatus) || "RECOVERED".equalsIgnoreCase(executionStatus))
            && terminalOrderStatus(orderStatus);
    }

    private static boolean terminalOrderStatus(String status) {
        return Set.of("FILLED", "CANCELED", "EXPIRED", "REJECTED", "EXPIRED_IN_MATCH")
            .contains(Objects.toString(status, "").toUpperCase(Locale.ROOT));
    }

    private static String firstText(Object a, Object b) {
        String x = Objects.toString(a, "");
        return x.isBlank() ? Objects.toString(b, null) : x;
    }

    /** 仅在余额请求发出前采集；UNKNOWN 和部分成交不得计入已覆盖集合。 */
    public Set<String> balanceCoverage(Long credentialId) {
        return new HashSet<>(jdbc.query("SELECT r.idempotency_key,r.reserved_margin FROM quant_open_capacity r JOIN quant_execution_record e "
            + "ON e.credential_id=r.credential_id AND e.idempotency_key=r.idempotency_key WHERE r.credential_id=? "
            + "AND r.status='RESERVED' AND e.execution_status IN ('CONFIRMED','RECOVERED') AND e.exchange_order_status='FILLED'",
            (rs, row) -> rs.getString(1), credentialId));
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
        for (var row : jdbc.queryForList("SELECT r.idempotency_key,r.reserved_margin,e.execution_status FROM quant_open_capacity r "
            + "LEFT JOIN quant_execution_record e ON e.credential_id=r.credential_id AND e.idempotency_key=r.idempotency_key "
            + "WHERE r.credential_id=? AND r.status='RESERVED'", credentialId)) {
            String key = Objects.toString(row.get("idempotency_key"));
            if (releasable.contains(key) || snapshot.coveredExecutions().contains(key) || "FAILED".equals(row.get("execution_status"))) { continue; }
            if (!(row.get("reserved_margin") instanceof BigDecimal amount) || amount.signum() <= 0) {
                throw new CapacityBlockedException("遗留待确认开仓缺少保证金金额，必须恢复核实后才能新增开仓: idempotencyKey=" + key);
            }
            reserved = reserved.add(amount);
        }
        return snapshot.availableBalance().subtract(reserved).max(BigDecimal.ZERO);
    }

    /** 明确未成交/未发送且执行记录已经标为 FAILED，才允许释放此次占位。 */
    public void releaseFailed(Long credentialId, String idempotencyKey) {
        jdbc.update("UPDATE quant_open_capacity SET status='RELEASED', updated_at=CURRENT_TIMESTAMP WHERE credential_id=? AND idempotency_key=? "
            + "AND EXISTS (SELECT 1 FROM quant_execution_record e WHERE e.credential_id=? AND e.idempotency_key=? AND e.execution_status='FAILED')",
            credentialId, idempotencyKey, credentialId, idempotencyKey);
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
