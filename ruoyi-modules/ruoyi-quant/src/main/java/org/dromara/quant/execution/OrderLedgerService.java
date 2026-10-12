package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 统一写入本地订单账本。
 */
@Service
public class OrderLedgerService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.dromara.quant.decision.TradeDecisionLogService decisionLogs;

    /**
     * 当前领域 Mapper。
     */
    private final QuantOrderMapper mapper;

    /**
     * 创建 OrderLedgerService 实例并初始化其依赖。
     */
    public OrderLedgerService(QuantOrderMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 同步对应业务事实。
     */
    public QuantOrder syncSnapshot(Long credentialId, ExchangeGateway.OrderSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        if (decisionLogs != null) { decisionLogs.order(credentialId, snapshot); }
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
        entity.setSymbol(snapshot.symbol());
        entity.setExchangeOrderId(snapshot.orderId());
        entity.setSide(snapshot.side());
        entity.setOrderType(snapshot.type());
        entity.setStatus(snapshot.status());
        entity.setPrice(snapshot.price());
        entity.setOriginalQuantity(snapshot.originalQuantity());
        entity.setExecutedQuantity(snapshot.executedQuantity());
        entity.setUpdatedAt(snapshot.updateTime() == null ? LocalDateTime.now() : LocalDateTime.ofInstant(snapshot.updateTime(), java.time.ZoneOffset.UTC));
        if (entity.getId() == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return entity;
    }

    /**
     * 新增或更新本地订单账本。
     */
    public QuantOrder upsert(Long credentialId, Long strategyId, Long strategyInstanceId, ExchangeGateway.OrderRequest request, ExchangeGateway.OrderResult result) {
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
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getId() == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return entity;
    }
}
