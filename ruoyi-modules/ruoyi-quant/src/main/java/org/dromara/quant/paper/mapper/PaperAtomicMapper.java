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
    @Options(timeout = 3)
    @Select("SELECT * FROM quant_paper_account WHERE credential_id=#{credentialId} FOR UPDATE")
    @Results({@Result(property = "id", column = "id", id = true), @Result(property = "credentialId", column = "credential_id"), @Result(property = "balance", column = "balance")
        , @Result(property = "availableBalance", column = "available_balance"), @Result(property = "realizedPnl", column = "realized_pnl"), @Result(property = "unrealizedPnl",
        column = "unrealized_pnl")})
    QuantPaperAccount lockAccount(@Param("credentialId") Long credentialId);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_paper_account
        SET available_balance=available_balance-#{margin}, updated_at=NOW()
        WHERE id=#{accountId} AND available_balance>=#{margin}
        """)
    int reserveMargin(@Param("accountId") Long accountId, @Param("margin") BigDecimal margin);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_paper_account
        SET available_balance=available_balance+#{margin}, updated_at=NOW()
        WHERE id=#{accountId}
        """)
    int refundMargin(@Param("accountId") Long accountId, @Param("margin") BigDecimal margin);

    @Options(timeout = 3)
    @Select("SELECT id FROM quant_position WHERE id=#{positionId} FOR UPDATE")
    Long lockPosition(@Param("positionId") Long positionId);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_paper_account
        SET balance=balance+#{realized},
            available_balance=available_balance+#{releasedMargin}+#{realized},
            realized_pnl=realized_pnl+#{realized},
            unrealized_pnl=unrealized_pnl-#{oldPnl},
            updated_at=NOW()
        WHERE id=#{accountId}
        """)
    int settleClose(@Param("accountId") Long accountId, @Param("realized") BigDecimal realized, @Param("releasedMargin") BigDecimal releasedMargin,
                    @Param("oldPnl") BigDecimal oldPnl);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_paper_account SET unrealized_pnl=#{total}, updated_at=NOW()
        WHERE credential_id=#{credentialId}
        """)
    int updateUnrealized(@Param("credentialId") Long credentialId, @Param("total") BigDecimal total);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_paper_account
        SET unrealized_pnl=unrealized_pnl+#{pnl}-#{oldPnl}, updated_at=NOW()
        WHERE credential_id=#{credentialId}
        """)
    int adjustUnrealized(@Param("credentialId") Long credentialId, @Param("pnl") BigDecimal pnl, @Param("oldPnl") BigDecimal oldPnl);

    @Options(timeout = 3)
    @Select("""
        SELECT id FROM quant_execution_record
        WHERE credential_id=#{credentialId} AND idempotency_key=#{key}
        FOR UPDATE
        """)
    Long lockExecution(@Param("credentialId") Long credentialId, @Param("key") String key);
}
