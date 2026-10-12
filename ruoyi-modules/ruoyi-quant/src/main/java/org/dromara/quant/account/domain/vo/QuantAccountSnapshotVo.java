package org.dromara.quant.account.domain.vo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.quant.account.domain.QuantAccountSnapshot;
import org.dromara.quant.account.domain.QuantTrade;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易账户资产快照。
 */
@Data
@AutoMapper(target = QuantAccountSnapshot.class)
public class QuantAccountSnapshotVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
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
     * 钱包余额。
     */
    private BigDecimal walletBalance;
    /**
     * 可用余额。
     */
    private BigDecimal availableBalance;
    /**
     * 保证金余额。
     */
    private BigDecimal marginBalance;
    /**
     * 未实现盈亏。
     */
    private BigDecimal unrealizedPnl;
    /**
     * 已实现盈亏。
     */
    private BigDecimal realizedPnl;
    /**
     * 资金费字段；完整资金费事实以 quant_financial_event 为准。
     */
    private BigDecimal fundingFee;
    /**
     * 快照观测时间。
     */
    private LocalDateTime observedAt;
}
