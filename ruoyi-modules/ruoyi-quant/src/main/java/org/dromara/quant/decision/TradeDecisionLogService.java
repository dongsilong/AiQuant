package org.dromara.quant.decision;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.stereotype.Service;
import java.time.Instant;

@Service
@Slf4j
public class TradeDecisionLogService {
    private final TradeDecisionWriter writer;

    public TradeDecisionLogService(TradeDecisionLogRepository repository, org.dromara.quant.notification.QuantTradeNotificationService notifications) {
        writer = new TradeDecisionWriter(10_000, repository::writeBatch, count -> {
            log.error("开平仓决策日志记录丢失，累计丢失={}，请检查日志队列和数据库",count);
            notifications.notifyDecisionLogLoss(count);
        });
    }

    @PostConstruct
    public void start() { writer.start(); }

    /** 观察路径必须失败隔离，不得向交易线程传播日志异常。 */
    public void record(DecisionLogEvent event) {
        // 已确认持仓期间的重复开仓评估不属于新的开仓失败，不写入决策日志。
        if (event != null && "OPEN".equals(event.operation()) && "BLOCKED".equals(event.status())
            && "POSITION_OPEN".equals(event.reasonCode())) { return; }
        try { writer.offer(event); } catch (RuntimeException ignored) { }
    }

    public void privateEvent(Long credentialId, ExchangeGateway.UserDataEvent event) {
        if (event != null && "ORDER_TRADE_UPDATE".equals(event.eventType())) {
            record(DecisionLogEvent.builder().recordType("PRIVATE").credentialId(credentialId)
                .eventTime(event.eventTime()).rawEvent(event.rawJson()).build());
        }
    }

    public void trade(Long credentialId, ExchangeGateway.TradeSnapshot trade) {
        record(DecisionLogEvent.builder().recordType("FILL").credentialId(credentialId).symbol(trade.symbol())
            .orderId(trade.orderId()).tradeId(trade.tradeId()).quantity(trade.quantity()).price(trade.price())
            .direction(trade.side()).tradeTime(trade.time()).eventTime(trade.time()).build());
    }

    public void order(Long credentialId, ExchangeGateway.OrderSnapshot order) {
        if (order != null) {
            record(DecisionLogEvent.builder().recordType("ORDER").credentialId(credentialId).symbol(order.symbol())
                .orderId(order.orderId()).clientOrderId(order.clientOrderId()).direction(order.side())
                .status(order.status()).orderStatus(order.status()).eventTime(order.updateTime())
                .quantity(order.executedQuantity() != null && order.executedQuantity().signum() > 0 ? order.executedQuantity() : null)
                .price(order.executedQuantity() != null && order.executedQuantity().signum() > 0
                    && order.averagePrice() != null && order.averagePrice().signum() > 0 ? order.averagePrice() : null)
                .tradeTime(order.executedQuantity() != null && order.executedQuantity().signum() > 0 ? order.updateTime() : null).build());
        }
    }

    public void protection(Long credentialId, org.dromara.quant.protection.domain.QuantProtectionOrder local,
                           ExchangeGateway.ProtectionOrderSnapshot actual) {
        if (actual == null || actual.actualOrderId() == null || actual.actualOrderId() <= 0) { return; }
        boolean filled = actual.executedQuantity() != null && actual.executedQuantity().signum() > 0
            && actual.actualPrice() != null && actual.actualPrice().signum() > 0;
        record(DecisionLogEvent.builder().eventKey("O:"+credentialId+":"+local.getSymbol()+":"+actual.actualOrderId())
            .recordType("PROTECTION").credentialId(credentialId).symbol(local.getSymbol()).operation("CLOSE")
            .direction(local.getPositionSide()).status(filled ? "FINISHED".equals(actual.status()) ? "FILLED" : "PARTIALLY_FILLED" : "ACCEPTED")
            .reasonCode("EXCHANGE_PROTECTION").reason("交易所保护单触发："+local.getProtectionType()+"，触发价="+local.getStopPrice())
            .orderId(actual.actualOrderId()).clientOrderId(local.getClientOrderId()).quantity(filled ? actual.executedQuantity() : null)
            .price(filled ? actual.actualPrice() : null).eventTime(actual.updateTime())
            .tradeTime(filled ? actual.updateTime() : null).build());
    }

    public static String safeReason(RuntimeException ex) {
        if (ex instanceof org.dromara.common.core.exception.ServiceException
            || ex instanceof org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException
            || ex instanceof org.dromara.quant.risk.LiveTradingGate.LiveTradingBlockedException
            || ex instanceof IllegalArgumentException
            || ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException) {
            String reason = ex.getMessage();
            if (reason != null) {
                if (java.util.regex.Pattern.compile("(?i)(authorization|api[-_]?key|secret|token|signature)\\s*[:=]").matcher(reason).find()) {
                    return "执行异常（敏感请求内容已隐藏）："+ex.getClass().getSimpleName();
                }
                return reason.substring(0,Math.min(1500,reason.length()));
            }
        }
        return "执行异常："+ex.getClass().getSimpleName();
    }

    @PreDestroy
    public void close() { writer.close(); }
}
