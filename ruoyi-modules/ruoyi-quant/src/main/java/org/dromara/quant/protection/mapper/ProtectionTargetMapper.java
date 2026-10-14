package org.dromara.quant.protection.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Atomic protection intent ownership and short transaction SQL.
 * No external Binance request may execute inside these mapper statements.
 */
@Mapper
public interface ProtectionTargetMapper {
    @Options(timeout = 3)
    @Insert("""
        INSERT INTO quant_protection_target
          (target_key,credential_id,position_id,position_cycle_key,position_side,
           protection_type,target_version,step_index,confirmed_step,updated_at)
        VALUES (#{key},#{credentialId},#{positionId},#{cycleKey},#{side},
                #{type},0,0,0,CURRENT_TIMESTAMP)
        """)
    int insertTarget(@Param("key") String key, @Param("credentialId") Long credentialId,
                     @Param("positionId") Long positionId, @Param("cycleKey") String cycleKey,
                     @Param("side") String side, @Param("type") String type);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_protection_target
        SET owner_token=#{owner},lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP)
        WHERE target_key=#{key} AND (owner_token IS NULL OR lease_until<CURRENT_TIMESTAMP)
        """)
    int acquireLease(@Param("key") String key, @Param("owner") String owner);

    @Options(timeout = 3)
    @Select("""
        SELECT confirmed_step FROM quant_protection_target
        WHERE credential_id=#{credentialId} AND position_cycle_key=#{cycleKey}
          AND position_side=#{side} AND protection_type='STEP_TP'
        """)
    List<Integer> confirmedSteps(@Param("credentialId") Long credentialId,
                                 @Param("cycleKey") String cycleKey, @Param("side") String side);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_protection_target
        SET lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP)
        WHERE target_key=#{key} AND owner_token=#{owner} AND lease_until>CURRENT_TIMESTAMP
        """)
    int renewLease(@Param("key") String key, @Param("owner") String owner);

    @Options(timeout = 3)
    @Select("""
        SELECT COUNT(*) FROM quant_protection_target
        WHERE target_key=#{key} AND owner_token=#{owner} AND lease_until>CURRENT_TIMESTAMP
        """)
    Long countOwned(@Param("key") String key, @Param("owner") String owner);

    @Options(timeout = 3)
    @Select("SELECT * FROM quant_protection_target WHERE target_key=#{key}")
    Map<String,Object> getTarget(@Param("key") String key);

    @Options(timeout = 3)
    @Select("SELECT * FROM quant_protection_target WHERE target_key=#{key} FOR UPDATE")
    Map<String,Object> lockTarget(@Param("key") String key);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_protection_target
        SET target_version=#{version},step_index=#{stepIndex},quantity=#{quantity},
            trigger_price=#{triggerPrice},order_type=#{orderType},
            pending_order_id=#{pendingId},previous_order_id=#{previousId},updated_at=CURRENT_TIMESTAMP
        WHERE target_key=#{key} AND owner_token=#{owner} AND lease_until>CURRENT_TIMESTAMP
        """)
    int prepareTarget(@Param("key") String key, @Param("owner") String owner,
                      @Param("version") long version, @Param("stepIndex") int stepIndex,
                      @Param("quantity") BigDecimal quantity, @Param("triggerPrice") BigDecimal triggerPrice,
                      @Param("orderType") String orderType, @Param("pendingId") Long pendingId,
                      @Param("previousId") Long previousId);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_protection_target
        SET current_order_id=#{currentId},pending_order_id=NULL,step_index=#{stepIndex},
            confirmed_step=#{confirmedStep},quantity=#{quantity},trigger_price=#{triggerPrice},
            order_type=#{orderType},updated_at=CURRENT_TIMESTAMP
        WHERE target_key=#{key} AND owner_token=#{owner} AND lease_until>CURRENT_TIMESTAMP
        """)
    int confirmTarget(@Param("key") String key, @Param("owner") String owner,
                      @Param("currentId") Long currentId, @Param("stepIndex") int stepIndex,
                      @Param("confirmedStep") int confirmedStep, @Param("quantity") BigDecimal quantity,
                      @Param("triggerPrice") BigDecimal triggerPrice, @Param("orderType") String orderType);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_protection_target SET pending_order_id=NULL,updated_at=CURRENT_TIMESTAMP
        WHERE target_key=#{key} AND owner_token=#{owner} AND lease_until>CURRENT_TIMESTAMP
        """)
    int clearPending(@Param("key") String key, @Param("owner") String owner);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_protection_target SET previous_order_id=NULL,updated_at=CURRENT_TIMESTAMP
        WHERE target_key=#{key} AND owner_token=#{owner} AND lease_until>CURRENT_TIMESTAMP
        """)
    int clearPrevious(@Param("key") String key, @Param("owner") String owner);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_protection_target SET owner_token=NULL,lease_until=NULL
        WHERE target_key=#{key} AND owner_token=#{owner}
        """)
    int releaseLease(@Param("key") String key, @Param("owner") String owner);
}
