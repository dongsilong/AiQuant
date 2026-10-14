package org.dromara.quant.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.quant.account.domain.QuantTrade;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.dromara.quant.finance.domain.QuantFinancialEvent;
import org.dromara.quant.finance.mapper.QuantFinancialEventMapper;
import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.risk.domain.QuantRiskConfig;
import org.dromara.quant.risk.mapper.QuantRiskConfigMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 交易前风险引擎。所有策略开仓都必须通过本服务，策略本身不实现风险限制。
 */
@Slf4j
@Service
public class RiskDecisionService {
    /**
     * risk 数据访问 Mapper。
     */
    private final QuantRiskConfigMapper riskMapper;
    /**
     * 持仓 Mapper。
     */
    private final QuantPositionMapper positionMapper;
    /**
     * 执行记录 Mapper。
     */
    private final QuantExecutionRecordMapper executionRecordMapper;
    /**
     * 成交 Mapper。
     */
    private final QuantTradeMapper tradeMapper;
    /**
     * 财务事件 Mapper。
     */
    private final QuantFinancialEventMapper financialEventMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.dromara.quant.reconcile.LivePositionFreshness positionFreshness;


    /**
     * 创建 RiskDecisionService 实例并初始化其依赖。
     */
    public RiskDecisionService(QuantRiskConfigMapper riskMapper, QuantPositionMapper positionMapper, QuantExecutionRecordMapper executionRecordMapper,
                               QuantTradeMapper tradeMapper, QuantFinancialEventMapper financialEventMapper) {
        this.riskMapper = riskMapper;
        this.positionMapper = positionMapper;
        this.executionRecordMapper = executionRecordMapper;
        this.tradeMapper = tradeMapper;
        this.financialEventMapper = financialEventMapper;
    }

    /**
     * 检查开仓请求是否通过风险规则。
     */
    public boolean approveOpen(Long credentialId, String symbol, BigDecimal notional) {
        return approveOpen(credentialId, symbol, notional, "PAPER");
    }

    /**
     * 检查开仓请求是否通过风险规则。
     */
    public boolean approveOpen(Long credentialId, String symbol, BigDecimal notional, String runMode) {
        return approveOpen(credentialId, symbol, notional, runMode, null);
    }

    private boolean approveOpen(Long credentialId, String symbol, BigDecimal notional, String runMode, String currentKey) {
        return refusalReason(credentialId, symbol, notional, runMode, currentKey) == null;
    }

    private String refusalReason(Long credentialId, String symbol, BigDecimal notional, String runMode, String currentKey) {
        QuantRiskConfig risk = riskMapper.selectOne(new LambdaQueryWrapper<QuantRiskConfig>().eq(QuantRiskConfig::getCredentialId, credentialId));
        if (risk == null) {
            log.warn("交易前风控未通过[未配置风控] credentialId={} symbol={} runMode={} notional={}",
                credentialId, symbol, runMode, notional);
            return "未配置账户风控";
        }
        if (!Boolean.TRUE.equals(risk.getWhitelist())) {
            log.warn("交易前风控未通过[白名单未开启] credentialId={} symbol={} runMode={} notional={} whitelist={}",
                credentialId, symbol, runMode, notional, risk.getWhitelist());
            return "账户未启用交易白名单";
        }
        if ("LIVE".equalsIgnoreCase(runMode) && !Boolean.TRUE.equals(risk.getLiveEnabled())) {
            log.warn("交易前风控未通过[实盘交易未启用] credentialId={} symbol={} runMode={} notional={} liveEnabled={}",
                credentialId, symbol, runMode, notional, risk.getLiveEnabled());
            return "账户未启用实盘交易";
        }
        if (risk.getMaxOrderNotional() != null && risk.getMaxOrderNotional().signum() > 0 && notional.compareTo(risk.getMaxOrderNotional()) > 0) {
            log.warn("交易前风控未通过[单笔名义价值超限] credentialId={} symbol={} runMode={} notional={} maxOrderNotional={}",
                credentialId, symbol, runMode, notional, risk.getMaxOrderNotional());
            return "单笔订单名义金额超限";
        }
        // Stale or absent full Binance snapshot MUST NOT permit new LIVE exposure.
        // Closing/reducing exposure does not use requireOpenApproved.
        if ("LIVE".equalsIgnoreCase(runMode)
            && (positionFreshness == null || !positionFreshness.fresh(credentialId))) {
            log.warn("交易前风控未通过[交易所持仓快照缺失或过期] credentialId={} symbol={}", credentialId, symbol);
            return "交易所持仓快照缺失或过期";
        }
        if (risk.getMaxOpenPositions() != null && risk.getMaxOpenPositions() > 0) {
            Long count = positionMapper.selectCount(new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, credentialId).ne(QuantPosition::getQuantity,
                    BigDecimal.ZERO));
            if (count >= risk.getMaxOpenPositions()) {
                log.warn("交易前风控未通过[最大持仓数达到上限] credentialId={} symbol={} runMode={} notional={} currentOpenPositions={} maxOpenPositions={}",
                    credentialId, symbol, runMode, notional, count, risk.getMaxOpenPositions());
                return "账户持仓数量达到上限";
            }
        }
        if (risk.getMaxOrdersPerMinute() != null && risk.getMaxOrdersPerMinute() > 0) {
            LocalDateTime since = LocalDateTime.now().minusMinutes(1);
            Long count = executionRecordMapper.selectCount(new LambdaQueryWrapper<QuantExecutionRecord>().eq(QuantExecutionRecord::getCredentialId, credentialId)
                        .ge(QuantExecutionRecord::getCreatedAt, since).ne(QuantExecutionRecord::getExecutionStatus, "FAILED").ne(currentKey != null, QuantExecutionRecord::getIdempotencyKey, currentKey));
            if (count >= risk.getMaxOrdersPerMinute()) {
                log.warn("交易前风控未通过[每分钟下单频率超限] credentialId={} symbol={} runMode={} notional={} ordersLastMinute={} maxOrdersPerMinute={}",
                    credentialId, symbol, runMode, notional, count, risk.getMaxOrdersPerMinute());
                return "每分钟开仓次数达到上限";
            }
        }
        if (risk.getDailyLossLimit() != null && risk.getDailyLossLimit().signum() > 0) {
            LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
            List<QuantFinancialEvent> events = financialEventMapper.selectList(new LambdaQueryWrapper<QuantFinancialEvent>().select(QuantFinancialEvent::getAmount,
                    QuantFinancialEvent::getEventType).eq(QuantFinancialEvent::getCredentialId, credentialId).ge(QuantFinancialEvent::getEventTime, startOfDay).in(QuantFinancialEvent::getEventType, "REALIZED_PNL", "COMMISSION", "FUNDING_FEE"));
            BigDecimal realized = BigDecimal.ZERO;
            if (!events.isEmpty()) {
                for (QuantFinancialEvent event : events) {
                    realized = realized.add(event.getAmount() == null ? BigDecimal.ZERO : event.getAmount());
                }
            } else {
                // 兼容刚升级、Income History 尚未首次对账的数据库。
                List<QuantTrade> trades =
                        tradeMapper.selectList(new LambdaQueryWrapper<QuantTrade>().select(QuantTrade::getRealizedPnl, QuantTrade::getFundingFee).eq(QuantTrade::getCredentialId,
                                credentialId).ge(QuantTrade::getTradeTime, startOfDay));
                for (QuantTrade trade : trades) {
                    realized = realized.add(trade.getRealizedPnl() == null ? BigDecimal.ZERO : trade.getRealizedPnl()).add(trade.getFundingFee() == null ? BigDecimal.ZERO : trade.getFundingFee());
                }
            }
            boolean blocked = realized.compareTo(risk.getDailyLossLimit().negate()) <= 0;
            if (blocked) {
                log.warn("交易前风控未通过[当日亏损达到上限] credentialId={} symbol={} runMode={} notional={} realized={} dailyLossLimit={}",
                    credentialId, symbol, runMode, notional, realized, risk.getDailyLossLimit());
                return "当日亏损达到上限";
            }
        }
        return null;
    }

    /**
     * 读取必需数据，不满足条件时抛出异常。
     */
    public void requireOpenApproved(Long credentialId, String symbol, BigDecimal notional, String runMode) {
        requireOpenApproved(credentialId, symbol, notional, runMode, null);
    }

    /**
     * 当前请求已创建 PENDING，但不能在频率检查中把自身作为历史订单。
     */
    public void requireOpenApproved(Long credentialId, String symbol, BigDecimal notional, String runMode, String currentKey) {
        String reason = refusalReason(credentialId, symbol, notional, runMode, currentKey);
        if (reason != null) {
            throw new ServiceException("交易前风控未通过[" + reason + "]: " + symbol);
        }
    }
}
