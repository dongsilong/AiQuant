package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.decision.TradeDecisionLogService;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 统一写入本地订单账本。
 */
@RequiredArgsConstructor
@Service
public class OrderLedgerService {
    /**
     * 当前领域 Mapper。
     */
    private final QuantOrderMapper mapper;
    @Autowired
    private TradeDecisionLogService decisionLogs;

    /**
     * 按主键重新读取持久化订单，供恢复流程核验并发更新后的最终状态。
     */
    public QuantOrder findById(Long orderId) {
        return orderId == null ? null : mapper.selectById(orderId);
    }

    /**
     * 同步对应业务事实。
     */
    public QuantOrder syncSnapshot(Long credentialId, ExchangeGateway.OrderSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        if (decisionLogs != null) {
            decisionLogs.order(credentialId, snapshot);
        }
        String clientOrderId = snapshot.clientOrderId() == null ? "EXCHANGE:" + snapshot.orderId() : snapshot.clientOrderId();
        QuantOrder entity = snapshot.orderId() == null ? null :
            mapper.selectOne(new LambdaQueryWrapper<QuantOrder>().eq(QuantOrder::getCredentialId, credentialId).eq(QuantOrder::getExchangeOrderId, snapshot.orderId()));
        if (entity == null) {
            entity = mapper.selectOne(new LambdaQueryWrapper<QuantOrder>().eq(QuantOrder::getCredentialId, credentialId).eq(QuantOrder::getClientOrderId, clientOrderId));
        }
        if (entity == null) {
            entity = new QuantOrder();
            entity.setCredentialId(credentialId);
            entity.setClientOrderId(clientOrderId);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setIdempotencyKey(clientOrderId);
        }
        // Exchange-order lookup may find a legacy row with an EXCHANGE:<id> placeholder.
        // Always persist the exact client order ID returned by Binance before strategy binding.
        entity.setClientOrderId(clientOrderId);
        entity.setSymbol(snapshot.symbol());
        entity.setExchangeOrderId(snapshot.orderId());
        entity.setSide(snapshot.side());
        entity.setOrderType(snapshot.type());
        entity.setStatus(snapshot.status());
        entity.setPrice(snapshot.price());
        entity.setOriginalQuantity(snapshot.originalQuantity());
        entity.setExecutedQuantity(snapshot.executedQuantity());
        entity.setUpdatedAt(snapshot.updateTime() == null ? LocalDateTime.now() : LocalDateTime.ofInstant(snapshot.updateTime(), java.time.ZoneOffset.UTC));
        return persistMonotonic(entity);
    }

    /**
     * 新增或更新本地订单账本。
     */
    public QuantOrder upsert(Long credentialId, Long strategyId, Long strategyInstanceId, ExchangeGateway.OrderRequest request, ExchangeGateway.OrderResult result) {
        return upsert(credentialId, strategyId, strategyInstanceId, request, result, null, null);
    }

    /** Persist optional immutable opening metadata without changing order execution semantics. */
    public QuantOrder upsert(Long credentialId, Long strategyId, Long strategyInstanceId, ExchangeGateway.OrderRequest request, ExchangeGateway.OrderResult result,
                             Integer leverageSnapshot, java.math.BigDecimal priceTickSizeSnapshot) {
        String clientOrderId = result.clientOrderId() == null ? "EXCHANGE:" + result.orderId() : result.clientOrderId();
        QuantOrder entity = result.orderId() == null ? null :
            mapper.selectOne(new LambdaQueryWrapper<QuantOrder>().eq(QuantOrder::getCredentialId, credentialId).eq(QuantOrder::getExchangeOrderId, result.orderId()));
        if (entity == null) {
            entity = mapper.selectOne(new LambdaQueryWrapper<QuantOrder>().eq(QuantOrder::getCredentialId, credentialId).eq(QuantOrder::getClientOrderId, clientOrderId));
        }
        if (entity == null) {
            entity = new QuantOrder();
            entity.setCredentialId(credentialId);
            entity.setClientOrderId(clientOrderId);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setIdempotencyKey(request.clientOrderId());
        }
        entity.setStrategyId(strategyId);
        entity.setStrategyInstanceId(strategyInstanceId);
        entity.setSymbol(request.symbol());
        entity.setExchangeOrderId(result.orderId());
        entity.setSide(request.side());
        entity.setOrderType(request.type());
        entity.setStatus(result.status());
        entity.setPositionSide(request.positionSide());
        entity.setPrice(request.price());
        entity.setOriginalQuantity(request.quantity());
        entity.setExecutedQuantity("FILLED".equalsIgnoreCase(result.status()) ? request.quantity() : (entity.getExecutedQuantity() == null ? java.math.BigDecimal.ZERO :
            entity.getExecutedQuantity()));
        entity.setReduceOnly(request.reduceOnly());
        if (!request.reduceOnly()) {
            if (entity.getLeverageSnapshot() == null) {
                entity.setLeverageSnapshot(leverageSnapshot);
            }
            if (entity.getPriceTickSizeSnapshot() == null) {
                entity.setPriceTickSizeSnapshot(priceTickSizeSnapshot);
            }
        }
        entity.setUpdatedAt(LocalDateTime.now());
        return persistMonotonic(entity);
    }

    /**
     * Never let a stale Binance event regress terminal order status, cumulative
     * executed quantity, confirmed order identity or strategy ownership.
     * The comparison/update is one database statement, not a stale read-then-write.
     */
    private QuantOrder persistMonotonic(QuantOrder entity) {
        if (entity.getId() == null) {
            if (mapper.insert(entity) != 1) {
                throw new IllegalStateException("无法持久化新的交易所订单事实");
            }
            return entity;
        }
        if (mapper.mergeExchangeFactIfMonotonic(entity) == 1) {
            return entity;
        }
        QuantOrder current = mapper.selectById(entity.getId());
        if (current == null) {
            throw new IllegalStateException("订单对账期间本地订单消失，等待重试");
        }
        if (entity.getExchangeOrderId() != null && current.getExchangeOrderId() != null && !entity.getExchangeOrderId().equals(current.getExchangeOrderId()) || entity.getStrategyInstanceId() != null && current.getStrategyInstanceId() != null && !entity.getStrategyInstanceId().equals(current.getStrategyInstanceId())) {
            throw new IllegalStateException("交易所订单身份或策略归属冲突，禁止覆盖");
        }
        // Returning the freshest DB state (rather than the stale in-memory entity)
        // ensures downstream reconciliation never assumes a rejected snapshot won.
        return current;
    }

}
