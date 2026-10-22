package org.dromara.quant.notification.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.notification.domain.QuantNotification;

import java.time.LocalDateTime;

/**
 * QuantNotificationMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
@Mapper
public interface QuantNotificationMapper extends BaseMapperPlus<QuantNotification, QuantNotification> {
    /**
     * 原子抢占待发送通知，避免多节点重复投递。
     */

    int claim(@Param("id") Long id, @Param("ownerToken") String ownerToken, @Param("leaseUntil") LocalDateTime leaseUntil);
}
