package org.dromara.quant.config.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户自选交易对关系实体。
 */
@Data
@TableName("quant_symbol_favorite")
public class QuantSymbolFavorite {
    /**
     * 主键。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 交易对主键。
     */
    private Long symbolId;
    /**
     * 用户主键。
     */
    private Long userId;
}

