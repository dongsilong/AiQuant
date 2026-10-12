package org.dromara.quant.credential.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

import org.dromara.common.encrypt.annotation.EncryptField;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 币安账户凭证。Secret 只在服务端解密，禁止进入 VO。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quant_credential")
public class QuantCredential extends BaseEntity {
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
     * 用户主键。
     */
    private Long userId;
    /**
     * 凭证/账户显示名称。
     */
    private String accountName;
    /**
     * 交易所 API Key。
     */
    @EncryptField
    private String apiKey;

    /**
     * 交易所 Secret Key；应按系统安全策略加密或保护。
     */
    @EncryptField
    private String secretKey;
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
    private java.time.LocalDateTime lastConnectTime;
    /**
     * 逻辑删除标记。
     */
    @TableLogic
    private Integer delFlag;
}
