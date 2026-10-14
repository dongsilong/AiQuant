package org.dromara.quant.market;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.config.QuantProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Tag("dev")
class MarketStreamHubIsolationTest {
    @Test
    void sameSymbolInLiveAndTestnetHasIndependentUpstreamAndReferenceReplay() throws Exception {
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        var liveReplay = new java.util.concurrent.CopyOnWriteArrayList<BigDecimal>();
        var testReplay = new java.util.concurrent.CopyOnWriteArrayList<BigDecimal>();
        var ready = new CountDownLatch(4);
        var liveTicks = new CountDownLatch(1);
        var testTicks = new CountDownLatch(1);
        var liveCallback = new java.util.concurrent.atomic.AtomicReference<Consumer<ExchangeGateway.MarketEvent>>();
        var testCallback = new java.util.concurrent.atomic.AtomicReference<Consumer<ExchangeGateway.MarketEvent>>();
        Instant open = Instant.now().minusSeconds(5);
        var liveHistory = java.util.List.of(
            new ExchangeGateway.Kline(open.minusSeconds(60), open.minusMillis(1), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, true),
            new ExchangeGateway.Kline(open, open.plusSeconds(60), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false));
        var testHistory = liveHistory.stream().map(c -> new ExchangeGateway.Kline(c.openTime(), c.closeTime(), BigDecimal.TEN,
            BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, c.closed())).toList();
        when(gateway.queryKlines("BTCUSDT", "1m", 200)).thenReturn(liveHistory);
        when(gateway.queryKlines("TESTNET", "BTCUSDT", "1m", 200)).thenReturn(testHistory);
        when(gateway.subscribeMarket(eq("BTCUSDT"), eq("1m"), any())).thenAnswer(invocation -> {
            liveCallback.set(invocation.getArgument(2));
            return mock(ExchangeGateway.MarketStreamSession.class);
        });
        when(gateway.subscribeMarket(eq("TESTNET"), eq("BTCUSDT"), eq("1m"), any())).thenAnswer(invocation -> {
            testCallback.set(invocation.getArgument(3));
            return mock(ExchangeGateway.MarketStreamSession.class);
        });
        var hub = new MarketStreamHub(gateway, scheduler, new QuantProperties());
        try {
            hub.subscribe("LIVE", "BTCUSDT", "1m", event -> {
                if (event.replay() && event.kline() != null) { liveReplay.add(event.kline().open()); ready.countDown(); }
                if ("TRADE".equals(event.type()) && !event.replay()) { liveTicks.countDown(); }
            }, ignored -> {});
            hub.subscribe("TESTNET", "BTCUSDT", "1m", event -> {
                if (event.replay() && event.kline() != null) { testReplay.add(event.kline().open()); ready.countDown(); }
                if ("TRADE".equals(event.type()) && !event.replay()) { testTicks.countDown(); }
            }, ignored -> {});
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(2, TimeUnit.SECONDS));
            assertEquals(java.util.List.of(BigDecimal.ONE, BigDecimal.ONE), liveReplay);
            assertEquals(java.util.List.of(BigDecimal.TEN, BigDecimal.TEN), testReplay);
            // Both environments must deliver real-time ticks independently after REST reference recovery.
            for (var callback : java.util.List.of(liveCallback.get(), testCallback.get())) {
                callback.accept(new ExchangeGateway.MarketEvent("MARK", "BTCUSDT", "1m",
                    BigDecimal.ONE, null, Instant.now()));
                callback.accept(new ExchangeGateway.MarketEvent("TRADE", "BTCUSDT", "1m",
                    BigDecimal.ONE, null, Instant.now()));
            }
            org.junit.jupiter.api.Assertions.assertTrue(liveTicks.await(2, TimeUnit.SECONDS), "LIVE realtime tick missing");
            org.junit.jupiter.api.Assertions.assertTrue(testTicks.await(2, TimeUnit.SECONDS), "TESTNET realtime tick missing");
            verify(gateway, times(1)).subscribeMarket(eq("BTCUSDT"), eq("1m"), any());
            verify(gateway, times(1)).subscribeMarket(eq("TESTNET"), eq("BTCUSDT"), eq("1m"), any());
        } finally { hub.close(); scheduler.shutdownNow(); }
    }

    private java.util.List<ExchangeGateway.Kline> history(String interval) {
        long seconds = interval.equals("15m") ? 900 : 60;
        var open = Instant.ofEpochSecond(Instant.now().getEpochSecond() / seconds * seconds);
        return java.util.List.of(new ExchangeGateway.Kline(open.minusSeconds(seconds), open.minusMillis(1), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, true),
            new ExchangeGateway.Kline(open, open.plusSeconds(seconds).minusMillis(1), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false));
    }

    @Test
    void downstreamOverflowReplaysFromConsumedInsteadOfReceivedTradeId() throws Exception {
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        when(gateway.queryKlines(any(), any(), anyInt())).thenAnswer(i -> history(i.getArgument(1)));
        when(gateway.queryKlinesFrom(any(), any(), any(), anyInt())).thenAnswer(i -> history(i.getArgument(1)));
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var properties = new QuantProperties(); properties.getMarket().setQueueCapacity(4); properties.getMarket().setReconnectDelaySeconds(1);
        var callback = new java.util.concurrent.atomic.AtomicReference<Consumer<ExchangeGateway.MarketEvent>>();
        when(gateway.subscribeMarket(any(), any(), any())).thenAnswer(invocation -> {
            callback.set(invocation.getArgument(2)); return mock(ExchangeGateway.MarketStreamSession.class);
        });
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1), recovery = new CountDownLatch(1);
        var fromId = new java.util.concurrent.atomic.AtomicLong();
        when(gateway.queryAggregateTradesFrom(any(), any(), anyLong(), anyInt())).thenAnswer(invocation -> {
            fromId.set(invocation.getArgument(2)); recovery.countDown(); return java.util.List.of();
        });
        MarketStreamHub hub = new MarketStreamHub(gateway, scheduler, properties);
        try {
            hub.subscribe("BTCUSDT", "1m", event -> {
                if ("TRADE".equals(event.type()) && event.sequenceId() == 10L) {
                    blocked.countDown();
                    try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
            });
            callback.get().accept(new ExchangeGateway.MarketEvent("TRADE", "BTCUSDT", "1m", BigDecimal.ONE, null, Instant.now(), 10L, Instant.now(), false));
            org.junit.jupiter.api.Assertions.assertTrue(blocked.await(1, TimeUnit.SECONDS));
            for (long id = 11; id <= 15; id++) {
                callback.get().accept(new ExchangeGateway.MarketEvent("TRADE", "BTCUSDT", "1m", BigDecimal.ONE, null, Instant.now(), id, Instant.now(), false));
                Thread.sleep(30);
            }
            org.junit.jupiter.api.Assertions.assertTrue(recovery.await(3, TimeUnit.SECONDS));
            assertEquals(10L, fromId.get(), "unconsumed trade 10 and queued trades must be replayed");
        } finally { release.countDown(); hub.close(); scheduler.shutdownNow(); }
    }

    @Test
    void consumerFailureDoesNotDisconnectOtherConsumers() throws Exception {
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        when(gateway.queryKlines(any(), any(), anyInt())).thenAnswer(i -> history(i.getArgument(1)));
        when(gateway.queryKlinesFrom(any(), any(), any(), anyInt())).thenAnswer(i -> history(i.getArgument(1)));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        @SuppressWarnings("unchecked")
        Consumer<ExchangeGateway.MarketEvent>[] callback = new Consumer[1];
        when(gateway.subscribeMarket(eq("BTCUSDT"), eq("15m"), any())).thenAnswer(invocation -> {
            callback[0] = invocation.getArgument(2);
            return mock(ExchangeGateway.MarketStreamSession.class);
        });
        MarketStreamHub hub = new MarketStreamHub(gateway, scheduler, new QuantProperties());
        try {
            AutoCloseable first = hub.subscribe("BTCUSDT", "15m", event -> {
                if ("TRADE".equals(event.type())) {
                    throw new IllegalStateException("consumer failed");
                }
            });
            AtomicInteger received = new AtomicInteger();
            AutoCloseable second = hub.subscribe("BTCUSDT", "15m", event -> {
                if ("TRADE".equals(event.type())) {
                    received.incrementAndGet();
                }
            });
            assertDoesNotThrow(() -> callback[0].accept(new ExchangeGateway.MarketEvent("TRADE", "BTCUSDT", "15m", null, null, Instant.now())));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (received.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, received.get());
            verify(gateway, times(1)).subscribeMarket(eq("BTCUSDT"), eq("15m"), any());
            first.close();
            second.close();
        } finally {
            hub.close();
            scheduler.shutdownNow();
        }
    }
    @Test
    void blockedListenerMustNotBlockAnotherSubscriberOrSocketReader() throws Exception {
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        when(gateway.queryKlines(any(), any(), anyInt())).thenAnswer(i -> history(i.getArgument(1)));
        when(gateway.queryKlinesFrom(any(), any(), any(), anyInt())).thenAnswer(i -> history(i.getArgument(1)));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        @SuppressWarnings("unchecked")
        Consumer<ExchangeGateway.MarketEvent>[] callback = new Consumer[1];
        when(gateway.subscribeMarket(any(), any(), any())).thenAnswer(invocation -> {
            callback[0] = invocation.getArgument(2);
            return mock(ExchangeGateway.MarketStreamSession.class);
        });
        MarketStreamHub hub = new MarketStreamHub(gateway, scheduler, new QuantProperties());
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch fast = new CountDownLatch(1);
        try {
            hub.subscribe("BTCUSDT", "1m", event -> {
                if ("TRADE".equals(event.type())) {
                    blocked.countDown();
                    try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
            });
            hub.subscribe("BTCUSDT", "1m", event -> { if ("TRADE".equals(event.type())) { fast.countDown(); } });
            long start = System.nanoTime();
            callback[0].accept(new ExchangeGateway.MarketEvent("TRADE", "BTCUSDT", "1m", BigDecimal.ONE, null, Instant.now()));
            org.junit.jupiter.api.Assertions.assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(300), "socket callback blocked");
            org.junit.jupiter.api.Assertions.assertTrue(blocked.await(1, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertTrue(fast.await(1, TimeUnit.SECONDS), "fast subscriber blocked by slow subscriber");
        } finally {
            release.countDown();
            hub.close();
            scheduler.shutdownNow();
        }
    }
    @Test
    void olderSameCandleUpdateCannotOverwriteNewerEvent() throws Exception {
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        when(gateway.queryKlines(any(), any(), anyInt())).thenAnswer(i -> history(i.getArgument(1)));
        when(gateway.queryKlinesFrom(any(), any(), any(), anyInt())).thenAnswer(i -> history(i.getArgument(1)));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        @SuppressWarnings("unchecked") Consumer<ExchangeGateway.MarketEvent>[] callback = new Consumer[1];
        when(gateway.subscribeMarket(any(), any(), any())).thenAnswer(invocation -> { callback[0] = invocation.getArgument(2); return mock(ExchangeGateway.MarketStreamSession.class); });
        var hub = new MarketStreamHub(gateway, scheduler, new QuantProperties());
        var processed = new CountDownLatch(1);
        var candles = new AtomicInteger();
        try {
            hub.subscribe("BTCUSDT", "1m", event -> {
                if ("KLINE".equals(event.type()) && !event.replay()) { candles.incrementAndGet(); }
                if ("TRADE".equals(event.type())) { processed.countDown(); }
            });
            var open = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            var time = Instant.now();
            var candle = new ExchangeGateway.Kline(open, open.plusSeconds(60), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false);
            callback[0].accept(new ExchangeGateway.MarketEvent("KLINE", "BTCUSDT", "1m", null, candle, time));
            callback[0].accept(new ExchangeGateway.MarketEvent("KLINE", "BTCUSDT", "1m", null, candle, time.minusMillis(1)));
            callback[0].accept(new ExchangeGateway.MarketEvent("TRADE", "BTCUSDT", "1m", BigDecimal.ONE, null, Instant.now()));
            org.junit.jupiter.api.Assertions.assertTrue(processed.await(2, TimeUnit.SECONDS));
            assertEquals(1, candles.get());
        } finally { hub.close(); scheduler.shutdownNow(); }
    }
}
