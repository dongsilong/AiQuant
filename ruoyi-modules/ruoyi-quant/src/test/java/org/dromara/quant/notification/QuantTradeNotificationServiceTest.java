package org.dromara.quant.notification;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.notification.domain.QuantNotification;
import org.dromara.quant.notification.mapper.QuantNotificationMapper;
import org.dromara.quant.risk.mapper.QuantRiskConfigMapper;
import org.dromara.system.api.MessageService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class QuantTradeNotificationServiceTest {
    @Test
    void tradingCallerOnlyPersistsMailAndBackgroundWorkerClaimsIt() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "notification-test"), QuantNotification.class);
        var mapper = mock(QuantNotificationMapper.class);
        var properties = new QuantProperties();
        properties.getNotification().setDefaultEmails("test@example.invalid");
        var service = new QuantTradeNotificationService(mock(QuantRiskConfigMapper.class), mapper, properties,
            mock(MessageService.class), mock(JdbcTemplate.class));

        service.notifyOpen(null, null, "BTCUSDT", "LONG", BigDecimal.ONE, BigDecimal.TEN, 5, BigDecimal.ONE, "test");

        var rows = ArgumentCaptor.forClass(QuantNotification.class);
        verify(mapper, times(2)).insert(rows.capture());
        var mail = rows.getAllValues().stream().filter(row -> "EMAIL".equals(row.getChannel())).findFirst().orElseThrow();
        assertEquals("PENDING", mail.getStatus());
        assertNotNull(mail.getNextRetryAt());
        verify(mapper, never()).claim(any(), any(), any());

        mail.setId(1L);
        when(mapper.selectList(any())).thenReturn(List.of(mail));
        // 未抢占成功时不发送邮件；验证调度器仍消费持久化 Outbox。
        service.retryPendingNotifications();
        verify(mapper).claim(eq(1L), any(), any());
    }
}
