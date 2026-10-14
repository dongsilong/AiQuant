package org.dromara.quant.market;

import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class UserDataStreamHubTest {
    @Test
    void testnetOrderFillEventReachesStrategyListenerAndAccountCaches() throws Exception {
        var gateway = mock(ExchangeGateway.class);
        var execution = mock(TradeExecutionApplicationService.class);
        var positionCache = mock(AccountPositionCache.class);
        var balanceCache = mock(AccountBalanceCache.class);
        var preparation = mock(org.dromara.quant.execution.TradingPreparationService.class);
        var credential = new QuantCredential();
        credential.setId(72L);
        credential.setRunMode("TESTNET");
        var exchange = new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET");
        when(execution.toCredential(credential)).thenReturn(exchange);
        var receiver = new AtomicReference<Consumer<ExchangeGateway.UserDataEvent>>();
        when(gateway.subscribeUserData(eq(exchange), any())).thenAnswer(invocation -> {
            receiver.set(invocation.getArgument(1));
            return new ExchangeGateway.UserDataStreamSession("test", Instant.now(), () -> {}, () -> {}, () -> true);
        });
        var hub = new UserDataStreamHub(gateway, execution, positionCache, balanceCache, preparation);
        var forwarded = new java.util.concurrent.CountDownLatch(1);
        try {
            var subscription = hub.subscribe(credential, event -> {
                if ("ORDER_TRADE_UPDATE".equals(event.eventType())) { forwarded.countDown(); }
            });
            var fill = new ExchangeGateway.UserDataEvent("ORDER_TRADE_UPDATE", Instant.now(),
                "{\\\"e\\\":\\\"ORDER_TRADE_UPDATE\\\",\\\"o\\\":{\\\"X\\\":\\\"FILLED\\\"}}");
            receiver.get().accept(fill);
            verify(positionCache).onEvent(72L, fill);
            verify(balanceCache).onEvent(72L, fill);
            verify(preparation).onEvent(72L, fill);
            assertTrue(forwarded.await(2, java.util.concurrent.TimeUnit.SECONDS),
                "TESTNET order fill must reach the strategy reconciliation listener");
            verify(gateway, times(1)).subscribeUserData(eq(exchange), any());
            subscription.close();
        } finally { hub.destroy(); }
    }

    @Test
    void subscriptionUpdatesCacheDirectlyAndLastUnsubscribeRemovesIt() throws Exception {
        var gateway = mock(ExchangeGateway.class);
        var execution = mock(TradeExecutionApplicationService.class);
        var cache = mock(AccountPositionCache.class);
        var credential = new QuantCredential();
        credential.setId(1L);
        var exchange = new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET");
        when(execution.toCredential(credential)).thenReturn(exchange);
        var receiver = new AtomicReference<Consumer<ExchangeGateway.UserDataEvent>>();
        when(gateway.subscribeUserData(eq(exchange), any())).thenAnswer(invocation -> {
            receiver.set(invocation.getArgument(1));
            return new ExchangeGateway.UserDataStreamSession("test", Instant.now(), () -> {}, () -> {}, () -> true);
        });
        var hub = new UserDataStreamHub(gateway, execution, cache, mock(AccountBalanceCache.class), mock(org.dromara.quant.execution.TradingPreparationService.class));
        try {
            var first = hub.subscribe(credential, event -> {});
            var second = hub.subscribe(credential, event -> {});
            verify(gateway, times(1)).subscribeUserData(eq(exchange), any());
            var ordered = inOrder(cache, gateway);
            ordered.verify(cache).begin(eq(1L), eq(exchange), any());
            ordered.verify(gateway).subscribeUserData(eq(exchange), any());
            var event = new ExchangeGateway.UserDataEvent("ACCOUNT_UPDATE", Instant.now(), "{}");
            receiver.get().accept(event);
            // 不等待异步消费队列，接收回调返回时缓存已处理该事件。
            verify(cache).onEvent(1L, event);
            first.close();
            verify(cache, never()).remove(1L);
            second.close();
            verify(cache).remove(1L);
        } finally { hub.destroy(); }
    }
}
