package org.dromara.quant.decision;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.stereotype.Service;

/**
 * 将交易事件异步写入决策日志，隔离日志故障与交易执行线程。
 */
@Service
@Slf4j
public class TradeDecisionLogService {
    private final TradeDecisionWriter writer;

    public TradeDecisionLogService(TradeDecisionLogRepository repository, org.dromara.quant.notification.QuantTradeNotificationService notifications) {
        writer = new TradeDecisionWriter(10_000, repository::writeBatch, count -> {
            log.error("开平仓决策日志记录丢失，累计丢失={}，请检查日志队列和数据库", count);
            notifications.notifyDecisionLogLoss(count);
        }, this::logWriteFailure);
    }

    /**
     * 将异常归纳为安全原因，避免泄露请求或认证信息。
     */
    public static String safeReason(RuntimeException ex) {
        if (ex instanceof org.dromara.common.core.exception.ServiceException || ex instanceof org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException || ex instanceof org.dromara.quant.risk.LiveTradingGate.LiveTradingBlockedException || ex instanceof IllegalArgumentException || ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException) {
            String reason = ex.getMessage();
            if (reason != null) {
                if (java.util.regex.Pattern.compile("(?i)(authorization|api[-_]?key|secret|token|signature)\\s*[:=]").matcher(reason).find()) {
                    return "执行异常（敏感请求内容已隐藏）：" + ex.getClass().getSimpleName();
                }
                return reason.substring(0, Math.min(1500, reason.length()));
            }
        }
        return "执行异常：" + ex.getClass().getSimpleName();
    }

    /**
     * 输出批量写入失败的安全诊断，不记录请求凭证或原始敏感载荷。
     */
    private void logWriteFailure(RuntimeException failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        java.sql.SQLException sql = null;
        for (Throwable current = failure; current != null && sql == null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException exception) {
                sql = exception;
            }
        }
        StackTraceElement source = cause.getStackTrace().length == 0 ? null : cause.getStackTrace()[0];
        String origin = source == null ? "unknown" : source.getClassName() + "." + source.getMethodName() + ":" + source.getLineNumber();
        log.error("决策日志数据库批次写入失败并已丢弃: errorType={}, origin={}, sqlState={}, vendorCode={}", cause.getClass().getSimpleName(), origin, sql == null ? null : sql.getSQLState(),
            sql == null ? null : sql.getErrorCode());
    }

    /**
     * 启动异步日志写入器。
     */
    @PostConstruct
    public void start() {
        writer.start();
    }

    /**
     * 观察路径必须失败隔离，不得向交易线程传播日志异常。
     */
    public void record(DecisionLogEvent event) {
        // 已确认持仓期间的重复开仓评估不属于新的开仓失败，不写入决策日志。
        if (event != null && "OPEN".equals(event.operation()) && "BLOCKED".equals(event.status()) && "POSITION_OPEN".equals(event.reasonCode())) {
            return;
        }
        try {
            writer.offer(event);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * 记录用户数据流中的订单成交事件。
     */
    public void privateEvent(Long credentialId, ExchangeGateway.UserDataEvent event) {
        if (event != null && "ORDER_TRADE_UPDATE".equals(event.eventType())) {
            record(DecisionLogEvent.builder().recordType("PRIVATE").credentialId(credentialId).eventTime(event.eventTime()).rawEvent(event.rawJson()).build());
        }
    }

    /**
     * 记录交易所确认的成交事实。
     */
    public void trade(Long credentialId, ExchangeGateway.TradeSnapshot trade) {
        record(DecisionLogEvent.builder().recordType("FILL").credentialId(credentialId).symbol(trade.symbol()).orderId(trade.orderId()).tradeId(trade.tradeId()).quantity(trade.quantity()).price(trade.price()).direction(trade.side()).tradeTime(trade.time()).eventTime(trade.time()).build());
    }

    /**
     * 记录交易所返回的普通订单状态快照。
     */
    public void order(Long credentialId, ExchangeGateway.OrderSnapshot order) {
        if (order != null) {
            record(DecisionLogEvent.builder().recordType("ORDER").credentialId(credentialId).symbol(order.symbol()).orderId(order.orderId()).clientOrderId(order.clientOrderId()).direction(order.side()).status(order.status()).orderStatus(order.status()).eventTime(order.updateTime()).quantity(order.executedQuantity() != null && order.executedQuantity().signum() > 0 ? order.executedQuantity() : null).price(order.executedQuantity() != null && order.executedQuantity().signum() > 0 && order.averagePrice() != null && order.averagePrice().signum() > 0 ? order.averagePrice() : null).tradeTime(order.executedQuantity() != null && order.executedQuantity().signum() > 0 ? order.updateTime() : null).build());
        }
    }

    /**
     * 记录本地保护单与交易所保护单之间的确认事实。
     */
    public void protection(Long credentialId, org.dromara.quant.protection.domain.QuantProtectionOrder local, ExchangeGateway.ProtectionOrderSnapshot actual) {
        if (actual == null || actual.actualOrderId() == null || actual.actualOrderId() <= 0) {
            return;
        }
        boolean filled = actual.executedQuantity() != null && actual.executedQuantity().signum() > 0 && actual.actualPrice() != null && actual.actualPrice().signum() > 0;
        record(DecisionLogEvent.builder().eventKey("O:" + credentialId + ":" + local.getSymbol() + ":" + actual.actualOrderId()).recordType("PROTECTION").credentialId(credentialId).symbol(local.getSymbol()).operation("CLOSE").direction(local.getPositionSide()).status(filled ? "FINISHED".equals(actual.status()) ? "FILLED" : "PARTIALLY_FILLED" : "ACCEPTED").reasonCode("EXCHANGE_PROTECTION").reason("交易所保护单触发：" + local.getProtectionType() + "，触发价=" + local.getStopPrice()).orderId(actual.actualOrderId()).clientOrderId(local.getClientOrderId()).quantity(filled ? actual.executedQuantity() : null).price(filled ? actual.actualPrice() : null).eventTime(actual.updateTime()).tradeTime(filled ? actual.updateTime() : null).build());
    }

    /**
     * 停止日志写入器并尽可能排空已接收事件。
     */
    @PreDestroy
    public void close() {
        writer.close();
    }
}
