package org.dromara.config.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.config.domain.QuantCredential;

import java.io.Serial;
import java.io.Serializable;

/**
 * 凭证新增、修改和查询参数。
 */
@Data
@AutoMapper(target = QuantCredential.class, reverseConvertGenerate = false)
public class QuantCredentialBo implements Serializable {
    /**
     * 序列化版本号。
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键。
     */
    @NotNull(message = "主键不能为空", groups = EditGroup.class)
    private Long id;

    /**
     * 用户主键。
     */
    @NotNull(message = "所属用户不能为空", groups = AddGroup.class)
    private Long userId;

    /**
     * 凭证/账户显示名称。
     */
    @NotBlank(message = "账户名称不能为空", groups = {AddGroup.class, EditGroup.class})
    private String accountName;

    /**
     * PAPER 可为空；TESTNET/LIVE 由服务层按 runMode 校验。
     */
    private String apiKey;

    /**
     * 修改时为空表示保留原 Secret。
     */
    private String secretKey;

    /**
     * PAPER 可为空；TESTNET/LIVE 由服务层按 runMode 校验。
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
     * 备注。
     */
    private String remark;
}
