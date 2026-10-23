package org.dromara.quant.market.controller;

import org.dromara.quant.exchange.ExchangeGateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Bounds the historical K-line burst sent to a terminal SSE subscriber. */
final class SseReplayKlineBuffer {
    private static final int MAX_REPLAY_KLINES = 4;
    private final ArrayDeque<ExchangeGateway.MarketEvent> replayKlines = new ArrayDeque<>(MAX_REPLAY_KLINES);

    synchronized void publish(ExchangeGateway.MarketEvent event, Consumer<ExchangeGateway.MarketEvent> downstream) {
        if (event.replay()) {
            if ("KLINE".equals(event.type()) && event.kline() != null) {
                if (replayKlines.size() == MAX_REPLAY_KLINES) replayKlines.removeFirst();
                replayKlines.addLast(event);
            }
            return;
        }
        if ("CONNECTED".equals(event.type())) {
            List<ExchangeGateway.MarketEvent> buffered = new ArrayList<>(replayKlines);
            replayKlines.clear();
            buffered.forEach(downstream);
        }
        downstream.accept(event);
    }
}
