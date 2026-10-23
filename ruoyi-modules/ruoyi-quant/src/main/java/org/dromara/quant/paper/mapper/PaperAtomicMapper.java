package org.dromara.quant.paper.mapper;

import org.apache.ibatis.annotations.*;
import org.dromara.quant.paper.domain.QuantPaperAccount;

import java.math.BigDecimal;

/**
 * PAPER 本地账本的原子 SQL。
 *
 * <p>这里只封装数据库操作；调用方负责在同一 Spring 事务内串行化
 * 执行记录、持仓和保证金变化，禁止拆分成先读后写的非原子 CRUD。
 */
@Mapper
public interface PaperAtomicMapper {

    QuantPaperAccount lockAccount(@Param("credentialId") Long credentialId);

    int reserveMargin(@Param("accountId") Long accountId, @Param("margin") BigDecimal margin);

    int refundMargin(@Param("accountId") Long accountId, @Param("margin") BigDecimal margin);

    Long lockPosition(@Param("positionId") Long positionId);

    int settleClose(@Param("accountId") Long accountId, @Param("realized") BigDecimal realized, @Param("releasedMargin") BigDecimal releasedMargin,
                    @Param("oldPnl") BigDecimal oldPnl);

    int updateUnrealized(@Param("credentialId") Long credentialId, @Param("total") BigDecimal total);

    int adjustUnrealized(@Param("credentialId") Long credentialId, @Param("pnl") BigDecimal pnl, @Param("oldPnl") BigDecimal oldPnl);

    Long lockExecution(@Param("credentialId") Long credentialId, @Param("key") String key);
}
