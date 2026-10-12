package org.dromara.quant.risk.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;

import lombok.Data;

import org.dromara.quant.risk.domain.QuantRiskConfig;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 风险配置展示对象。
 */
@Data
@AutoMapper(target = QuantRiskConfig.class)
public class QuantRiskConfigVo implements Serializable {
    /**
     * 序列化版本号。
     */
    @Serial
    private static final long serialVersionUID = 1L;
    /**
     * 主键。
     */
    private Long id;
    /**
     * 交易凭证主键。
     */
    private Long credentialId;
    /**
     * 该凭证是否允许 LIVE 实盘交易。
     */
    private Boolean liveEnabled;
    /**
     * 单笔订单最大名义金额。
     */
    private BigDecimal maxOrderNotional;
    /**
     * 单日最大允许亏损。
     */
    private BigDecimal dailyLossLimit;
    /**
     * 最大同时持仓数量。
     */
    private Integer maxOpenPositions;
    /**
     * 每分钟最大下单次数。
     */
    private Integer maxOrdersPerMinute;
    /**
     * WebSocket 断连时的风控动作。
     */
    private String websocketDisconnectAction;
    /**
     * 持仓不一致时的风控动作。
     */
    private String positionMismatchAction;
    /**
     * 保护单失败时的风控动作。
     */
    private String protectionFailureAction;
    /**
     * 允许交易的交易对白名单。
     */
    private Boolean whitelist;
    /**
     * 风险/交易通知邮箱。
     */
    private String notificationEmails;
}
