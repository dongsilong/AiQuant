package org.dromara.quant.paper.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PAPER 虚拟资金账户。
 */
@Data
@TableName("quant_paper_account")
public class QuantPaperAccount {
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
     * 计价资产，例如 USDT。
     */
    private String quoteAsset;
    /**
     * PAPER 模式初始余额。
     */
    private BigDecimal initialBalance;
    /**
     * PAPER 模式当前余额。
     */
    private BigDecimal balance;
    /**
     * 可用余额。
     */
    private BigDecimal availableBalance;
    /**
     * 已实现盈亏。
     */
    private BigDecimal realizedPnl;
    /**
     * 未实现盈亏。
     */
    private BigDecimal unrealizedPnl;
    /**
     * 最后更新时间。
     */
    private LocalDateTime updatedAt;
}
