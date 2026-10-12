package org.dromara.quant.risk.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 风险配置实体，保存单笔限额、日亏损、持仓数和异常动作等风控参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quant_risk_config")
public class QuantRiskConfig extends BaseEntity {
    /**
     * 序列化版本号。
     */
    @Serial
    private static final long serialVersionUID = 1L;
    /**
     * 主键。
     */
    @TableId
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
    private java.math.BigDecimal maxOrderNotional;
    /**
     * 单日最大允许亏损。
     */
    private java.math.BigDecimal dailyLossLimit;
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
