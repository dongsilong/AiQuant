package org.dromara.quant.reconcile;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingReconciliationSchedulerTest {

    @Test
    void reconcileMustRemainScheduled() throws NoSuchMethodException {
        Method reconcile = TradingReconciliationScheduler.class.getMethod("reconcile");

        Scheduled scheduled = reconcile.getAnnotation(Scheduled.class);

        assertNotNull(scheduled, "交易事实对账必须保持定时触发，否则成交不会自动写入量化成交账本");
        assertEquals("#{@quantProperties.reconcile.intervalSeconds.toString()}", scheduled.fixedDelayString());
        assertEquals("15", scheduled.initialDelayString());
        assertEquals(TimeUnit.SECONDS, scheduled.timeUnit());
    }

    @Test
    void retriesTransientNetworkFailuresButNotInterruptedThreads() {
        assertEquals(true, TradingReconciliationScheduler.isTransientNetworkFailure(
            new IllegalStateException("timeout", new java.io.InterruptedIOException("timeout"))));
        assertEquals(false, TradingReconciliationScheduler.isTransientNetworkFailure(
            new IllegalStateException("invalid response")));
        Thread.currentThread().interrupt();
        try {
            assertEquals(false, TradingReconciliationScheduler.isTransientNetworkFailure(
                new java.io.InterruptedIOException("timeout")));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void retriesNetworkFailuresPreservedAsSafeExchangeDiagnostics() {
        assertEquals(true, TradingReconciliationScheduler.isTransientNetworkFailure(
            new org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException(
                "QUERY_ACCOUNT", 0, null, "ApiException",
                "com.binance.connector.client.common.ApiException", "java.net.SocketException")));
        assertEquals(true, TradingReconciliationScheduler.isTransientNetworkFailure(
            new org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException(
                "QUERY_ACCOUNT", 0, null, "ApiException",
                "com.binance.connector.client.common.ApiException", "java.net.SocketTimeoutException")));
        assertEquals(false, TradingReconciliationScheduler.isTransientNetworkFailure(
            new org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException(
                "QUERY_ACCOUNT", 400, null, "invalid request",
                "com.binance.connector.client.common.ApiException", "java.lang.IllegalArgumentException")));
        Thread.currentThread().interrupt();
        try {
            assertEquals(false, TradingReconciliationScheduler.isTransientNetworkFailure(
                new org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException(
                    "QUERY_ACCOUNT", 0, null, "interrupted",
                    "com.binance.connector.client.common.ApiException", "java.net.SocketException")));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void deferredRetriesUseTheServerDelayAndBoundedExponentialBackoff() {
        assertEquals(2_000L, TradingReconciliationScheduler.retryDelayMillis(2_000L, 1));
        assertEquals(4_000L, TradingReconciliationScheduler.retryDelayMillis(0L, 3));
        assertEquals(30_000L, TradingReconciliationScheduler.retryDelayMillis(0L, 10));
    }
}
