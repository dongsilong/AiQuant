package org.dromara.quant.notification.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 持久化通知 Outbox：交易通知先入库，再尝试投递，失败由调度器重试。
 */
@Data
@TableName("quant_notification")
public class QuantNotification {
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
     * 通知渠道，例如 EMAIL。
     */
    private String channel;
    /**
     * 通知接收方。
     */
    private String recipient;
    /**
     * 通知主题。
     */
    private String subject;
    /**
     * 通知正文。
     */
    private String content;
    /**
     * 当前状态。
     */
    private String status;
    /**
     * 通知失败后的重试次数。
     */
    private Integer retryCount;
    /**
     * 下一次允许重试的时间。
     */
    private LocalDateTime nextRetryAt;
    /**
     * 创建时间。
     */
    private LocalDateTime createdAt;
    /**
     * 当前发送任务持有者。
     */
    private String ownerToken;
    /**
     * 当前发送任务租约截止时间。
     */
    private LocalDateTime leaseUntil;
    /**
     * 实际发送时间。
     */
    private LocalDateTime sentAt;
    /**
     * 最近一次发送错误。
     */
    private String lastError;
}
