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
}
