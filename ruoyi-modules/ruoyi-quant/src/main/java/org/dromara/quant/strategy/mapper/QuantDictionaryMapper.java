package org.dromara.quant.strategy.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 量化模块读取系统字典所需的只读查询。
 */
@Mapper
public interface QuantDictionaryMapper {

    Long countKlinePeriod(@Param("interval") String interval);
}
