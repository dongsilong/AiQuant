package org.dromara.common.mybatis.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SQL 日志配置。
 *
 * @author Lion Li
 */
@Data
@ConfigurationProperties(prefix = "mybatis-plus.sql-log")
public class SqlLogProperties {

    /**
     * 是否开启完整 SQL 输出。
     */
    private Boolean enabled = false;

    /**
     * 输出方式，可选 console、log。
     */
    private String output = "console";

    private Boolean logSuccess = true;

    private Boolean logError = true;

    private Long slowThresholdMs = 0L;

    /** 是否在异常/慢 SQL 日志中包含完整 SQL 和参数。默认关闭，避免敏感信息进入日志。 */
    private Boolean includeSql = false;

}
