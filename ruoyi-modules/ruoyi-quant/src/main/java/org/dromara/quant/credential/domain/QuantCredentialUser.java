package org.dromara.quant.credential.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 凭证与系统用户授权关系。
 */
@Data
@TableName("quant_credential_user")
public class QuantCredentialUser {
    /**
     * 主键。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 交易凭证主键。
     */
    private Long credentialId;
    /**
     * 用户主键。
     */
    private Long userId;
}
