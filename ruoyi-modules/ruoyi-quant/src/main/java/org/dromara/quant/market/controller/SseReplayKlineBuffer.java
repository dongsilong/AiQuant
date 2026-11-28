package org.dromara.quant.market.controller;

import org.dromara.quant.exchange.ExchangeGateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Bounds the historical K-line burst sent to a terminal SSE subscriber. */
final class SseReplayKlineBuffer {
    private static final int MAX_REPLAY_KLINES = 4;
    private static final int MAX_ONE_MINUTE_REPLAY_KLINES = 8;
    private final ArrayDeque<ExchangeGateway.MarketEvent> replayKlines = new ArrayDeque<>(MAX_REPLAY_KLINES);

    void publish(ExchangeGateway.MarketEvent event, Consumer<Object> downstream, boolean compactReplay) {
        long startedAt = System.nanoTime();
        int replayCount = 0;
        synchronized (this) {
            if (event.replay()) {
                if ("KLINE".equals(event.type()) && event.kline() != null) {
                    int maxReplayKlines = "1m".equals(event.interval()) ? MAX_ONE_MINUTE_REPLAY_KLINES : MAX_REPLAY_KLINES;
                    if (replayKlines.size() == maxReplayKlines) replayKlines.removeFirst();
                    replayKlines.addLast(event);
                }
                return;
            }
            if ("KLINE_READY".equals(event.type()) || "CONNECTED".equals(event.type())) {
                List<ExchangeGateway.MarketEvent> buffered = new ArrayList<>(replayKlines);
                replayKlines.clear();
                replayCount = buffered.size();
                if (compactReplay && !buffered.isEmpty()) {
                    ExchangeGateway.MarketEvent first = buffered.get(0);
                    downstream.accept(Map.of(
                        "type", "KLINE_SNAPSHOT",
                        "symbol", first.symbol(),
                        "interval", first.interval(),
                        "klines", buffered.stream().map(ExchangeGateway.MarketEvent::kline).toList()
                    ));
                } else {
                    buffered.forEach(downstream);
                }
            }
            downstream.accept(event);
        }
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        if (elapsedMs >= 100) {
            org.slf4j.LoggerFactory.getLogger(SseReplayKlineBuffer.class).warn(
                "SSE K线回放处理耗时异常 type={} interval={} replayCount={} elapsedMs={} thread={}",
                event.type(), event.interval(), replayCount, elapsedMs, Thread.currentThread().getName());
        }
    }
}
