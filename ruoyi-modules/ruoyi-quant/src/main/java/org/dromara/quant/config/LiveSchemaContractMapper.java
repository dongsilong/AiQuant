package org.dromara.quant.config;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MySQL/InnoDB metadata only. These queries never mutate application schema.
 */
@Mapper
public interface LiveSchemaContractMapper {
    @Options(timeout = 3)
    @Select("""
        SELECT table_name FROM information_schema.tables
        WHERE table_schema=DATABASE() AND engine='InnoDB'
        """)
    List<String> innodbTables();

    @Options(timeout = 3)
    @Select("""
        SELECT CONCAT(table_name,'|',GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')) AS contract_key
        FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND non_unique=0
        GROUP BY table_name,index_name
        """)
    List<String> uniqueContracts();

    @Options(timeout = 3)
    @Select("""
        SELECT CONCAT(table_name,'|',column_name)
        FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='quant_strategy_runtime_lease'
          AND column_name='lease_epoch' AND data_type='bigint' AND is_nullable='NO'
        """)
    List<String> leaseEpochColumns();

    @Options(timeout = 3)
    @Select("""
        SELECT CONCAT(table_name,'|',column_name)
        FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='quant_strategy_entry_guard'
          AND column_name='guard_type' AND data_type='varchar' AND is_nullable='NO'
        """)
    List<String> entryGuardColumns();

    @Options(timeout = 3)
    @Select("""
        SELECT column_name FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='quant_protection_order'
          AND column_name IN ('strategy_id','strategy_instance_id') AND data_type='bigint'
        """)
    List<String> protectionOwnerColumns();

    @Options(timeout = 3)
    @Select("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='quant_strategy_position_state' "
        + "AND column_name='maximum_return_rate' AND data_type='decimal'")
    List<String> positionReturnRateColumns();

}
