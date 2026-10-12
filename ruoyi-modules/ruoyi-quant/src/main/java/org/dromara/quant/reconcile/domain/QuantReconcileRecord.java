package org.dromara.quant.reconcile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 交易所与本地账本对账记录。
 */
@Data
@TableName("quant_reconcile_record")
public class QuantReconcileRecord {
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
     * 对账类型。
     */
    private String reconcileType;
    /**
     * 对账结果。
     */
    private String result;
    /**
     * 对账明细 JSON。
     */
    private String detailJson;
    /**
     * 完成对账的时间。
     */
    private LocalDateTime reconciledAt;
}
