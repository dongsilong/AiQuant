package org.dromara.quant.protection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.StrategyOrderIntentService;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.dromara.quant.protection.domain.QuantProtectionOrder;
import org.dromara.quant.protection.mapper.QuantProtectionOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;

/** 将已冻结的保护来源传递到 Binance 实际子订单；不按交易对或数量猜测成交归属。 */
@RequiredArgsConstructor
@Service
public class ProtectionOrderJournalService {
    private final QuantProtectionOrderMapper protections;
    private final QuantOrderMapper orders;
    private final StrategyOrderIntentService intents;

    /**
     * 只累计当前 Algo 创建后、由其他已完成保护单的精确成交子订单证明的减仓量。
     * 使用 Binance 子订单创建时间，而非本地账本更新时间，避免延迟恢复或历史成交误归属。
     */
    public BigDecimal priorProtectionExecutedAfter(Long credentialId, QuantProtectionOrder current,
                                                    List<QuantProtectionOrder> candidates, java.time.Instant currentAlgoCreateTime) {
        if (credentialId == null || current == null || current.getPositionId() == null || current.getPositionCycleKey() == null
            || current.getSymbol() == null || current.getPositionSide() == null || currentAlgoCreateTime == null || candidates == null
            || candidates.isEmpty()) {
            return BigDecimal.ZERO;
        }
        LocalDateTime algoCreatedAt = LocalDateTime.ofInstant(currentAlgoCreateTime, ZoneOffset.UTC);
        BigDecimal total = BigDecimal.ZERO;
        String expectedChildSide = "SHORT".equalsIgnoreCase(current.getPositionSide()) ? "BUY" : "SELL";
        for (QuantProtectionOrder candidate : candidates) {
            if (candidate == null || candidate.getId() == null
                || !Objects.equals(candidate.getCredentialId(), credentialId)
                || !Objects.equals(candidate.getPositionId(), current.getPositionId())
                || !Objects.equals(candidate.getPositionCycleKey(), current.getPositionCycleKey())
                || candidate.getSymbol() == null || !candidate.getSymbol().equalsIgnoreCase(current.getSymbol())
                || candidate.getPositionSide() == null || !candidate.getPositionSide().equalsIgnoreCase(current.getPositionSide())
                || !"FINISHED".equalsIgnoreCase(candidate.getStatus())
                || candidate.getExecutedQuantity() == null || candidate.getExecutedQuantity().signum() <= 0) {
                continue;
            }
            List<QuantOrder> childOrders = orders.selectList(new LambdaQueryWrapper<QuantOrder>()
                .eq(QuantOrder::getCredentialId, credentialId)
                .eq(QuantOrder::getSymbol, current.getSymbol())
                .likeRight(QuantOrder::getIdempotencyKey, "PROTECTION_CHILD:" + candidate.getId() + ":"));
            if (childOrders.size() != 1) {
                continue;
            }
            QuantOrder child = childOrders.getFirst();
            if (child.getExchangeOrderId() == null || child.getExchangeOrderId() <= 0
                || child.getExchangeCreatedAt() == null || !child.getExchangeCreatedAt().isAfter(algoCreatedAt)
                || !"FILLED".equalsIgnoreCase(child.getStatus())
                || child.getOriginalQuantity() == null || child.getExecutedQuantity() == null
                || child.getExecutedQuantity().signum() <= 0
                || child.getOriginalQuantity().compareTo(child.getExecutedQuantity()) != 0
                || child.getExecutedQuantity().compareTo(candidate.getExecutedQuantity()) != 0
                || candidate.getClientOrderId() == null || !candidate.getClientOrderId().equals(child.getClientOrderId())
                || !Boolean.TRUE.equals(child.getReduceOnly()) || !Boolean.FALSE.equals(child.getClosePosition())
                || child.getSide() == null || !expectedChildSide.equalsIgnoreCase(child.getSide())
                || child.getPositionSide() == null
                || !("BOTH".equalsIgnoreCase(child.getPositionSide()) || current.getPositionSide().equalsIgnoreCase(child.getPositionSide()))) {
                continue;
            }
            total = total.add(candidate.getExecutedQuantity());
        }
        return total;
    }

    /** 发送之前随保护意图保存来源；不能证明历史周期的记录保持未归属。 */
    public void captureOwner(QuantProtectionOrder local) {
        if (local.getStrategyId() != null || local.getStrategyInstanceId() != null) {
            if (local.getStrategyId() == null || local.getStrategyInstanceId() == null) {
                throw new IllegalStateException("保护单策略来源不完整");
            }
            return;
        }
        if (local.getPositionId() == null || local.getPositionCycleKey() == null) {
            return;
        }
        var owners = protections.cycleOwners(local.getCredentialId(), local.getPositionId(), local.getPositionCycleKey(), local.getSymbol(), local.getPositionSide());
        if (owners.size() != 1) {
            return;
        }
        Object strategy = owners.getFirst().get("strategy_id");
        Object instance = owners.getFirst().get("instance_id");
        if (strategy instanceof Number strategyId && strategyId.longValue() > 0 && instance instanceof Number instanceId && instanceId.longValue() > 0) {
            local.setStrategyId(strategyId.longValue());
            local.setStrategyInstanceId(instanceId.longValue());
        }
    }

    /** 精确算法单→实际子订单→冻结策略来源在同一事务入账，失败时保留原未知事实。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean bindChild(ExchangeGateway.Credential credential, QuantProtectionOrder parent,
                             ExchangeGateway.ProtectionOrderSnapshot actual, ExchangeGateway.OrderSnapshot child,
                             BigDecimal priorCycleExecuted) {
        // 事务回滚不能把失败的来源绑定留在调用方实体里，随后被状态更新意外写回。
        QuantProtectionOrder local = new QuantProtectionOrder();
        org.springframework.beans.BeanUtils.copyProperties(parent, local);
        java.util.StringJoiner conflicts = new java.util.StringJoiner(",");
        if (actual == null) {
            conflicts.add("actualMissing");
        } else if (child == null) {
            conflicts.add("childMissing");
        } else {
            if (actual.actualOrderId() == null || !actual.actualOrderId().equals(child.orderId())) conflicts.add("actualOrderId");
            if (actual.algoId() == null) conflicts.add("algoIdMissing");
            if (!Objects.equals(local.getClientOrderId(), actual.clientAlgoId())) conflicts.add("clientAlgoId");
            if (local.getExchangeOrderId() != null && !local.getExchangeOrderId().equals(actual.algoId())) conflicts.add("algoIdMismatch");
            if (!local.getSymbol().equalsIgnoreCase(actual.symbol())) conflicts.add("algoSymbol");
            if (!local.getSymbol().equalsIgnoreCase(child.symbol())) conflicts.add("childSymbol");
            if (child.clientOrderId() == null || child.clientOrderId().isBlank()) conflicts.add("childClientOrderIdMissing");
            BigDecimal priorExecuted = priorCycleExecuted;
            boolean quantityMatches = local.getQuantity() != null && actual.quantity() != null
                && local.getQuantity().compareTo(actual.quantity()) == 0;
            boolean reducedQuantityProvenByFilledChild = priorExecuted != null && priorExecuted.signum() > 0
                && actual.actualOrderId() != null && actual.actualOrderId().equals(child.orderId())
                && local.getQuantity() != null && actual.quantity() != null && actual.quantity().signum() > 0
                && local.getQuantity().subtract(priorExecuted).compareTo(actual.quantity()) == 0
                && child.originalQuantity() != null && child.originalQuantity().compareTo(actual.quantity()) == 0
                && child.executedQuantity() != null && child.executedQuantity().compareTo(child.originalQuantity()) == 0
                && "FILLED".equalsIgnoreCase(child.status())
                && Set.of("TRIGGERED", "FINISHED").contains(actual.status())
                && priorExecuted.add(child.executedQuantity()).compareTo(local.getQuantity()) == 0;
            if (!quantityMatches && !reducedQuantityProvenByFilledChild) conflicts.add("quantity");
            if (local.getStopPrice() == null || actual.triggerPrice() == null || local.getStopPrice().compareTo(actual.triggerPrice()) != 0) conflicts.add("triggerPrice");
            if (local.getOrderType() == null || !local.getOrderType().equalsIgnoreCase(actual.type())) conflicts.add("orderType");
            if (!("SHORT".equalsIgnoreCase(local.getPositionSide()) ? "BUY" : "SELL").equalsIgnoreCase(child.side())) conflicts.add("childReduceSide");
            if (child.side() == null || actual.side() == null || !child.side().equalsIgnoreCase(actual.side())) conflicts.add("algoChildSide");
            if (!("BOTH".equalsIgnoreCase(actual.positionSide()) || local.getPositionSide().equalsIgnoreCase(actual.positionSide()))) conflicts.add("positionSide");
        }
        if (conflicts.length() > 0) {
            throw new IllegalStateException("保护单实际子订单身份或减仓方向冲突，禁止分配成交；conflicts=" + conflicts
                + "; localSide=" + local.getPositionSide()
                + "; algoSide=" + (actual == null ? null : actual.side())
                + "; algoPositionSide=" + (actual == null ? null : actual.positionSide())
                + "; childSide=" + (child == null ? null : child.side())
                + "; localQty=" + local.getQuantity()
                + "; algoQty=" + (actual == null ? null : actual.quantity())
                + "; localTrigger=" + local.getStopPrice()
                + "; algoTrigger=" + (actual == null ? null : actual.triggerPrice()));
        }
        if (local.getStrategyId() == null && local.getStrategyInstanceId() == null) {
            captureOwner(local);
            if (local.getStrategyId() == null) {
                return false;
            }
            if (protections.bindCycleOwner(local.getId(), local.getCredentialId(), local.getPositionCycleKey(), local.getStrategyId(), local.getStrategyInstanceId()) != 1) {
                var persisted = protections.selectById(local.getId());
                if (persisted == null || !Objects.equals(persisted.getStrategyId(), local.getStrategyId())
                    || !Objects.equals(persisted.getStrategyInstanceId(), local.getStrategyInstanceId())) {
                    throw new IllegalStateException("保护单来源并发变更，禁止覆盖");
                }
            }
        }
        if (local.getStrategyId() == null || local.getStrategyId() <= 0 || local.getStrategyInstanceId() == null || local.getStrategyInstanceId() <= 0) {
            throw new IllegalStateException("保护单来源不完整，禁止分配成交");
        }
        QuantOrder order = orders.selectOne(new LambdaQueryWrapper<QuantOrder>().eq(QuantOrder::getCredentialId, local.getCredentialId())
            .eq(QuantOrder::getSymbol, local.getSymbol()).eq(QuantOrder::getExchangeOrderId, child.orderId()).last("FOR UPDATE"));
        if (order == null) {
            order = new QuantOrder();
            order.setCredentialId(local.getCredentialId());
            order.setSymbol(local.getSymbol());
            order.setExchangeOrderId(child.orderId());
            order.setClientOrderId(child.clientOrderId());
            order.setIdempotencyKey("PROTECTION_CHILD:" + local.getId() + ":" + child.orderId());
            order.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        } else if (!Objects.equals(order.getClientOrderId(), child.clientOrderId())
            || order.getStrategyId() != null && !order.getStrategyId().equals(local.getStrategyId())
            || order.getStrategyInstanceId() != null && !order.getStrategyInstanceId().equals(local.getStrategyInstanceId())
            || order.getPositionSide() != null && !order.getPositionSide().equalsIgnoreCase(actual.positionSide())) {
            throw new IllegalStateException("保护子订单已有冲突的策略归属，禁止覆盖");
        }
        order.setStrategyId(local.getStrategyId());
        order.setStrategyInstanceId(local.getStrategyInstanceId());
        order.setPositionSide(actual.positionSide());
        order.setReduceOnly(true);
        order.setSide(child.side());
        order.setOrderType(child.type());
        // 延迟的子订单查询不能把已落库的成交终态退回 NEW/PARTIALLY_FILLED。
        boolean terminalAlreadyKnown = Set.of("FILLED", "CANCELED", "EXPIRED", "REJECTED").contains(Objects.toString(order.getStatus(), ""));
        if (!terminalAlreadyKnown) {
            order.setStatus(child.status());
        }
        order.setPrice(child.price());
        order.setOriginalQuantity(child.originalQuantity());
        order.setExecutedQuantity(order.getExecutedQuantity() == null ? child.executedQuantity()
            : child.executedQuantity() == null ? order.getExecutedQuantity() : order.getExecutedQuantity().max(child.executedQuantity()));
        if (child.createTime() != null) {
            order.setExchangeCreatedAt(LocalDateTime.ofInstant(child.createTime(), ZoneOffset.UTC));
        }
        order.setUpdatedAt(child.updateTime() == null ? LocalDateTime.now(ZoneOffset.UTC) : LocalDateTime.ofInstant(child.updateTime(), ZoneOffset.UTC));
        if ((order.getId() == null ? orders.insert(order) : orders.updateById(order)) != 1) {
            throw new IllegalStateException("保护单子订单未可靠入账");
        }
        if ("LIVE".equalsIgnoreCase(credential.runMode())) {
            intents.recordVerifiedProtectionChild(local.getCredentialId(), local.getStrategyId(), local.getStrategyInstanceId(),
                local.getSymbol(), local.getPositionCycleKey(), local.getPositionSide(), child.side(),
                child.orderId(), child.clientOrderId());
        }
        return true;
    }
}
