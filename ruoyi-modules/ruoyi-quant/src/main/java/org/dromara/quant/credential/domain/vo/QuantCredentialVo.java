package org.dromara.quant.credential.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;

import lombok.Data;

import org.dromara.common.translation.annotation.Translation;
import org.dromara.common.translation.constant.TransConstant;
import org.dromara.quant.credential.domain.QuantCredential;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 凭证脱敏视图，永远不返回 Secret。
 */
@Data
@AutoMapper(target = QuantCredential.class)
public class QuantCredentialVo implements Serializable {
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
     * 用户主键。
     */
    private Long userId;
    /**
     * 用户名称。
     */
    @Translation(type = TransConstant.USER_ID_TO_NAME, mapper = "userId")
    private String userName;

    /**
     * 凭证/账户显示名称。
     */
    private String accountName;
    /**
     * 脱敏后的 API Key。
     */
    private String maskedApiKey;
    /**
     * 交易所请求签名类型。
     */
    private String signatureType;
    /**
     * 运行模式：PAPER、TESTNET 或 LIVE。
     */
    private String runMode;
    /**
     * 当前状态。
     */
    private String status;
    /**
     * 交易所连接状态。
     */
    private String connectionStatus;
    /**
     * 是否具备合约交易权限。
     */
    private Boolean tradePermission;
    /**
     * 是否确认提现能力已禁用。
     */
    private Boolean withdrawalDisabled;
    /**
     * 备注。
     */
    private String remark;
    /**
     * 最近一次成功连接时间。
     */
    private LocalDateTime lastConnectTime;
    /**
     * 创建时间。
     */
    private LocalDateTime createTime;
}
