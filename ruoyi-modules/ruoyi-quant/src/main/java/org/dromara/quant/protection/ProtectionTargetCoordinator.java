package org.dromara.quant.protection;

import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.protection.domain.QuantProtectionOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 持仓周期保护目标协调：短事务保存意图，网络请求必须在事务外执行。 */
@Service
public class ProtectionTargetCoordinator {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ProtectionTargetCoordinator(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(3);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    public record State(long version, int stepIndex, int confirmedStep, Long currentOrderId, Long pendingOrderId,
                        Long previousOrderId, BigDecimal quantity, BigDecimal triggerPrice, String orderType) {}

    public Lease acquire(Long credentialId, QuantPosition position, String type) {
        if (credentialId == null || position == null || position.getId() == null || position.getProtectionCycleKey() == null
            || position.getProtectionCycleKey().isBlank() || position.getPositionSide() == null || type == null) {
            throw new IllegalStateException("保护目标缺少可信持仓周期，禁止盲目创建");
        }
        String key;
        try {
            key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (credentialId + ":" + position.getProtectionCycleKey() + ":" + position.getPositionSide() + ":" + type).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("保护目标标识生成失败", ex); }
        try {
            jdbc.update("INSERT INTO quant_protection_target (target_key,credential_id,position_id,position_cycle_key,position_side,protection_type,target_version,step_index,confirmed_step,updated_at) VALUES (?,?,?,?,?,?,0,0,0,CURRENT_TIMESTAMP)",
                key, credentialId, position.getId(), position.getProtectionCycleKey(), position.getPositionSide(), type);
        } catch (DuplicateKeyException existing) { /* 不覆盖已有意图。 */ }
        String owner = UUID.randomUUID().toString();
        if (jdbc.update("UPDATE quant_protection_target SET owner_token=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP) WHERE target_key=? AND (owner_token IS NULL OR lease_until<CURRENT_TIMESTAMP)", owner, key) != 1) {
            throw new IllegalStateException("保护目标正在同步，等待原目标恢复");
        }
        return new Lease(key, owner);
    }

    public int confirmedStep(Long credentialId, QuantPosition position) {
        if (position.getProtectionCycleKey() == null) { throw new IllegalStateException("缺少持仓周期"); }
        var rows = jdbc.queryForList("SELECT confirmed_step FROM quant_protection_target WHERE credential_id=? AND position_cycle_key=? AND position_side=? AND protection_type='STEP_TP'",
            credentialId, position.getProtectionCycleKey(), position.getPositionSide());
        return rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("confirmed_step")).intValue();
    }

    private static State convert(Map<String,Object> row) {
        return new State(((Number)row.get("target_version")).longValue(), ((Number)row.get("step_index")).intValue(), ((Number)row.get("confirmed_step")).intValue(),
            number(row.get("current_order_id")), number(row.get("pending_order_id")), number(row.get("previous_order_id")),
            (BigDecimal)row.get("quantity"), (BigDecimal)row.get("trigger_price"), (String)row.get("order_type"));
    }
    private static Long number(Object value) { return value instanceof Number number ? number.longValue() : null; }

    public final class Lease implements AutoCloseable {
        private final String key;
        private final String owner;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();
        private final Thread heartbeat;
        private Lease(String key, String owner) {
            this.key = key; this.owner = owner;
            heartbeat = Thread.ofVirtual().name("quant-protection-target-lease").start(() -> {
                try {
                    while (!closed.get()) {
                        Thread.sleep(10000);
                        if (closed.get()) { break; }
                        if (jdbc.update("UPDATE quant_protection_target SET lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP) WHERE target_key=? AND owner_token=? AND lease_until>CURRENT_TIMESTAMP", key, owner) != 1) { lost.set(true); break; }
                    }
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                catch (RuntimeException ex) { lost.set(true); }
            });
        }
        public void check() {
            if (closed.get() || lost.get() || !Integer.valueOf(1).equals(jdbc.queryForObject(
                "SELECT COUNT(*) FROM quant_protection_target WHERE target_key=? AND owner_token=? AND lease_until>CURRENT_TIMESTAMP", Integer.class, key, owner))) {
                lost.set(true); throw new IllegalStateException("保护目标租约失效，禁止提交或覆盖意图");
            }
        }
        private Map<String,Object> row(boolean lock) {
            check();
            return jdbc.queryForMap("SELECT * FROM quant_protection_target WHERE target_key=?" + (lock ? " FOR UPDATE" : ""), key);
        }
        public State state() { return convert(row(false)); }
        public void prepare(QuantProtectionOrder candidate, int step, QuantProtectionOrder previous, Consumer<QuantProtectionOrder> insert) {
            transaction.executeWithoutResult(status -> {
                var old = convert(row(true));
                if (old.pendingOrderId() != null || old.previousOrderId() != null) {
                    throw new IllegalStateException("原保护目标尚待确认或清理，禁止生成第二份意图");
                }
                candidate.setTargetVersion(old.version() + 1);
                candidate.setStepIndex(Math.max(old.stepIndex(), step));
                insert.accept(candidate);
                if (candidate.getId() == null) { throw new IllegalStateException("保护意图缺少持久化主键"); }
                update("target_version=?,step_index=?,quantity=?,trigger_price=?,order_type=?,pending_order_id=?,previous_order_id=?,updated_at=CURRENT_TIMESTAMP",
                    candidate.getTargetVersion(), candidate.getStepIndex(), candidate.getQuantity(), candidate.getStopPrice(), candidate.getOrderType(), candidate.getId(),
                    previous == null ? old.currentOrderId() : previous.getId());
            });
        }
        public void confirmed(QuantProtectionOrder local, int step) {
            transaction.executeWithoutResult(status -> {
                var old = convert(row(true));
                if (old.pendingOrderId() != null && !old.pendingOrderId().equals(local.getId())) {
                    throw new IllegalStateException("必须先恢复持久化待确认保护意图");
                }
                update("current_order_id=?,pending_order_id=NULL,step_index=?,confirmed_step=?,quantity=?,trigger_price=?,order_type=?,updated_at=CURRENT_TIMESTAMP",
                    local.getId(), Math.max(old.stepIndex(), step), Math.max(old.confirmedStep(), step), local.getQuantity(), local.getStopPrice(), local.getOrderType());
            });
        }
        /** 仅在精确确认未生效或已终止后放开原待确认意图。 */
        public void terminal(QuantProtectionOrder local) {
            transaction.executeWithoutResult(status -> {
                var old = convert(row(true));
                if (Objects.equals(old.pendingOrderId(), local.getId())) { update("pending_order_id=NULL,updated_at=CURRENT_TIMESTAMP"); }
            });
        }
        public void cleaned() { check(); update("previous_order_id=NULL,updated_at=CURRENT_TIMESTAMP"); }
        private void update(String fields, Object... values) {
            check();
            var args = new Object[values.length + 2];
            System.arraycopy(values, 0, args, 0, values.length); args[values.length] = key; args[values.length + 1] = owner;
            if (jdbc.update("UPDATE quant_protection_target SET " + fields + " WHERE target_key=? AND owner_token=? AND lease_until>CURRENT_TIMESTAMP", args) != 1) {
                throw new IllegalStateException("保护目标更新失去所有权");
            }
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) { return; }
            heartbeat.interrupt();
            jdbc.update("UPDATE quant_protection_target SET owner_token=NULL,lease_until=NULL WHERE target_key=? AND owner_token=?", key, owner);
        }
    }
}
