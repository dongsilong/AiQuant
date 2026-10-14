package org.dromara.quant.exchange.binance;

import com.binance.connector.client.common.ApiClient;
import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.common.Pair;
import com.binance.connector.client.common.auth.Authentication;
import com.binance.connector.client.common.auth.BinanceAuthenticationFactory;
import com.binance.connector.client.common.auth.BinanceBaseAuthentication;
import com.binance.connector.client.common.auth.SignatureGeneratorFactory;
import com.binance.connector.client.common.configuration.ClientConfiguration;
import com.binance.connector.client.common.configuration.SignatureConfiguration;
import com.binance.connector.client.common.sign.SignatureGenerator;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.*;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.Interval;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.CryptoException;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Binance USDⓈ-M Futures SDK 12.x 适配器。SDK 类型只允许出现在本类。
 */
@Component
@Slf4j
public class BinanceGatewayAdapter implements ExchangeGateway {
    /**
     * Binance 服务器时间偏移刷新周期，单位毫秒。
     */
    private static final long CLOCK_OFFSET_REFRESH_MILLIS = 30_000L;
    /** Binance 签名请求允许的时间窗口，覆盖代理和网络抖动。 */
    private static final long RECV_WINDOW_MILLIS = 60_000L;
    /**
     * 量化模块配置。
     */
    private final QuantProperties properties;
    private final okhttp3.ConnectionPool restPool = new okhttp3.ConnectionPool();
    private final okhttp3.Dispatcher restDispatcher = new okhttp3.Dispatcher();
    /**
     * Binance 服务器时间偏移缓存。
     */
    private final Map<String, ClockOffset> clockOffsets = new ConcurrentHashMap<>();
    /** 同一环境只允许一个服务器时间刷新，其他线程复用结果。 */
    private final Map<String, Object> clockOffsetRefreshLocks = new ConcurrentHashMap<>();
    private final Map<String, TradingRules> tradingRules = new ConcurrentHashMap<>();
    private record TradingRules(long expiresAt, ExchangeInfo info) {}
    /** 进程内共享的 REST 请求节流时间点，避免同一出口 IP 触发 Binance 权重限制。 */
    private final java.util.concurrent.ConcurrentHashMap<Credential, java.util.concurrent.CompletableFuture<AccountSnapshot>> accountQueries = new java.util.concurrent.ConcurrentHashMap<>();
    private final RestRequestScheduler restScheduler = new RestRequestScheduler(250);
    /**
     * Binance 事件解析使用的 JSON 工具。
     */
    private final Gson gson = new Gson();
    private final java.util.concurrent.atomic.AtomicReference<String> currentWebsocketTransport =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.Set<StreamHandle> activeWebsocketStreams = ConcurrentHashMap.newKeySet();

    /**
     * 创建 BinanceGatewayAdapter 实例并初始化其依赖。
     */
    public BinanceGatewayAdapter(QuantProperties properties) {
        this.properties = properties;
        currentWebsocketTransport.set(validateWebsocketTransport(properties.getBinance().getWebsocketTransport()));
    }

    /**
     * 从 Binance 返回对象中安全读取字符串。
     */
    private static String string(Object bean, String getter) {
        try {
            Object value = bean.getClass().getMethod(getter).invoke(bean);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * 从 Binance 返回对象中安全读取数值。
     */
    private static Long number(Object bean, String getter) {
        String value = string(bean, getter);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 按主参数名、兼容别名和默认值读取 BigDecimal 参数。
     */
    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    /**
     * 把交易所时间戳转换为 Instant。
     */
    private static Instant instant(Long value) {
        return value == null ? Instant.EPOCH : Instant.ofEpochMilli(value);
    }

    /**
     * 从行情载荷读取最新成交价。
     */
    private static BigDecimal priceOf(SymbolPriceTickerResponse response) {
        Object actual = response.getActualInstance();
        if (actual instanceof SymbolPriceTickerResponse1 single) {
            return decimal(single.getPrice());
        }
        if (actual instanceof SymbolPriceTickerResponse2 list && !list.isEmpty()) {
            return decimal(list.get(0).getPrice());
        }
        throw new IllegalStateException("Binance 价格响应类型不支持: " + (actual == null ? "null" : actual.getClass().getName()));
    }

    /**
     * 从行情载荷读取标记价格。
     */
    private static BigDecimal markPriceOf(MarkPriceResponse response) {
        Object actual = response.getActualInstance();
        if (actual instanceof MarkPriceResponse1 single) {
            return decimal(single.getMarkPrice());
        }
        if (actual instanceof MarkPriceResponse2 list && !list.isEmpty()) {
            return decimal(list.get(0).getMarkPrice());
        }
        throw new IllegalStateException("Binance 标记价格响应类型不支持: " + (actual == null ? "null" : actual.getClass().getName()));
    }

    /**
     * 读取必需数据，不满足条件时抛出异常。
     */
    private static void requireCredential(Credential credential) {
        if (credential == null || credential.apiKey() == null || credential.secretKey() == null || credential.apiKey().isBlank() || credential.secretKey().isBlank()) {
            throw new IllegalArgumentException("认证交易请求缺少有效凭证");
        }
    }

    /**
     * 从公开行情 JSON 读取必需的小数值。
     */
    private static BigDecimal jsonDecimal(JsonObject data, String field) {
        if (!data.has(field) || data.get(field).isJsonNull()) {
            throw new IllegalStateException("Binance 公开行情缺少字段: " + field);
        }
        return decimal(data.get(field).getAsString());
    }

    /**
     * 验证交易所连通性。
     */
    @Override
    public ConnectivityResult testConnectivity() {
        try {
            api(null).testConnectivity();
            return new ConnectivityResult(true, "Binance 网络连接正常");
        } catch (RuntimeException ex) {
            return new ConnectivityResult(false, "Binance 网络连接失败");
        }
    }

    /**
     * 校验交易凭证与权限。
     */
    @Override
    public ConnectivityResult validateCredential(Credential credential) {
        try {
            if (credential == null) {
                return new ConnectivityResult(false, "缺少账户凭证");
            }
            ApiResponse<AccountInformationV3Response> account = api(credential, RestRequestScheduler.Kind.ACCOUNT).accountInformationV3(RECV_WINDOW_MILLIS);
            ApiResponse<FuturesAccountConfigurationResponse> configuration = api(credential).futuresAccountConfiguration(RECV_WINDOW_MILLIS);
            if (account.getData() == null || configuration.getData() == null) {
                return new ConnectivityResult(false, "Binance 凭证校验无响应");
            }
            return tradingPermissions(configuration.getData().getCanTrade(), configuration.getData().getCanWithdraw());
        } catch (RuntimeException ex) {
            return new ConnectivityResult(false, "Binance 连接失败");
        }
    }

    /**
     * 私有账户能力校验。只有交易所明确返回 canWithdraw=false 时，
     * 才能向 LIVE 闸门报告 withdrawalDisabled=true。
     * null/缺失值属于未知状态，必须拒绝而不是当作已禁用。
     *
     * <p>该字段不是 API Key 权限审计的替代品，运维仍须检查密钥权限。
     */
    static ConnectivityResult tradingPermissions(Boolean canTrade, Boolean canWithdraw) {
        boolean tradeAllowed = Boolean.TRUE.equals(canTrade);
        boolean withdrawalDisabled = Boolean.FALSE.equals(canWithdraw);
        if (!tradeAllowed) {
            return new ConnectivityResult(false, "账户没有 Futures Trade 权限", false, withdrawalDisabled, Instant.now());
        }
        if (!withdrawalDisabled) {
            return new ConnectivityResult(false, "未确认禁用提现能力，拒绝 LIVE 交易", true, false, Instant.now());
        }
        return new ConnectivityResult(true, "OK", true, true, Instant.now());
    }

    /**
     * 查询交易所交易规则和交易对元数据。
     */
    @Override
    public ExchangeInfo queryExchangeInfo() {
        return queryExchangeInfo(null);
    }

    @Override
    public synchronized SymbolRule queryTradingRule(Credential credential, String symbol) {
        String environment = credential != null && "TESTNET".equalsIgnoreCase(credential.runMode()) ? "TESTNET" : "LIVE";
        TradingRules cached = tradingRules.get(environment);
        if (cached == null || cached.expiresAt() <= System.currentTimeMillis()) {
            cached = new TradingRules(System.currentTimeMillis() + 60_000L, queryExchangeInfo(credential));
            tradingRules.put(environment, cached);
        }
        return cached.info().symbols().stream().filter(rule -> rule.symbol().equalsIgnoreCase(symbol)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("实际交易环境中没有有效交易规则: " + symbol));
    }

    private ExchangeInfo queryExchangeInfo(Credential credential) {
        try {
            ApiResponse<ExchangeInformationResponse> response = api(credential).exchangeInformation();
            List<SymbolRule> rules = new ArrayList<>();
            for (ExchangeInformationResponseSymbolsInner symbol : response.getData().getSymbols()) {
                if (!"TRADING".equals(symbol.getStatus())) {
                    continue;
                }
                BigDecimal priceTick = BigDecimal.ZERO;
                BigDecimal step = BigDecimal.ZERO;
                BigDecimal marketStep = BigDecimal.ZERO;
                BigDecimal minNotional = BigDecimal.ZERO;
                if (symbol.getFilters() == null) {
                    continue;
                }
                for (var filter : symbol.getFilters()) {
                    if ("PRICE_FILTER".equals(filter.getFilterType())) {
                        priceTick = decimal(filter.getTickSize());
                    }
                    if ("LOT_SIZE".equals(filter.getFilterType())) {
                        step = decimal(filter.getStepSize());
                    }
                    if ("MARKET_LOT_SIZE".equals(filter.getFilterType())) {
                        marketStep = decimal(filter.getStepSize());
                    }
                    if ("MIN_NOTIONAL".equals(filter.getFilterType()) || "NOTIONAL".equals(filter.getFilterType())) {
                        minNotional = decimal(filter.getNotional());
                    }
                }
                if (marketStep.signum() <= 0) {
                    marketStep = step;
                }
                if (priceTick.signum() <= 0 || step.signum() <= 0 || marketStep.signum() <= 0 || minNotional.signum() <= 0) {
                    continue;
                }
                rules.add(new SymbolRule(symbol.getSymbol(), symbol.getQuoteAsset(), symbol.getPricePrecision().intValue(), symbol.getQuantityPrecision().intValue(), priceTick,
                    step, marketStep, minNotional, symbol.getStatus()));
            }
            return new ExchangeInfo(rules);
        } catch (RuntimeException ex) {
            throw requestFailure("QUERY_TRADING_RULES", ex);
        }
    }

    /**
     * 查询历史 K 线。
     */
    @Override
    public List<Kline> queryKlines(String symbol, String interval, int limit) {
        return queryKlinesFrom(symbol, interval, null, limit);
    }

    @Override
    public List<Kline> queryKlinesFrom(String symbol, String interval, Instant from, int limit) {
        return queryKlinesFrom("LIVE", symbol, interval, from, limit);
    }

    @Override
    public List<Kline> queryKlinesFrom(String runMode, String symbol, String interval, Instant from, int limit) {
        try {
            var sdkInterval = Interval.fromValue(interval);
            ApiResponse<KlineCandlestickDataResponse> response = marketApi(runMode).klineCandlestickData(symbol.trim(), sdkInterval, from == null ? null : from.toEpochMilli(), null, (long) Math.min(1000, limit));
            List<Kline> result = new ArrayList<>(response.getData().size());
            long now = System.currentTimeMillis();
            for (var row : response.getData()) {
                Instant openTime = Instant.ofEpochMilli(Long.parseLong(row.get(0)));
                Instant closeTime = Instant.ofEpochMilli(Long.parseLong(row.get(6)));
                result.add(new Kline(openTime, closeTime, decimal(row.get(1)), decimal(row.get(2)), decimal(row.get(3)), decimal(row.get(4)), decimal(row.get(5)),
                    closeTime.toEpochMilli() <= now));
            }
            return result;
        } catch (RuntimeException ex) {
            throw new IllegalStateException("查询 Binance K 线失败", ex);
        }
    }

    @Override
    public List<MarketEvent> queryAggregateTradesFrom(String symbol, String interval, long fromId, int limit) {
        return queryAggregateTradesFrom("LIVE", symbol, interval, fromId, limit);
    }

    @Override
    public List<MarketEvent> queryAggregateTradesFrom(String runMode, String symbol, String interval, long fromId, int limit) {
        var data = marketApi(runMode).compressedAggregateTradesList(symbol, fromId, null, null, (long) Math.min(1000, limit)).getData();
        if (data == null) { return List.of(); }
        return data.stream().map(item -> new MarketEvent("TRADE", symbol, interval, decimal(item.getpLowerCase()), null,
            instant(item.getT()), item.getaLowerCase(), Instant.now(), true)).toList();
    }

    /**
     * 查询交易对最新价格。
     */
    @Override
    public PriceSnapshot queryPrice(String symbol) {
        return queryPrice("LIVE", symbol);
    }

    @Override
    public PriceSnapshot queryPrice(String runMode, String symbol) {
        try {
            String normalizedSymbol = symbol.trim();
            JsonObject trade = publicJson(runMode, "/fapi/v1/ticker/price", normalizedSymbol);
            JsonObject mark = publicJson(runMode, "/fapi/v1/premiumIndex", normalizedSymbol);
            return new PriceSnapshot(normalizedSymbol, jsonDecimal(trade, "price"), jsonDecimal(mark, "markPrice"), Instant.now());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("查询 Binance 实时价格失败", ex);
        }
    }

    /**
     * 读取 Binance 公开行情原始 JSON。SDK 12.0.x 对 ticker 的 oneOf 响应存在 actualInstance 为空的兼容问题，
     * 这里仍复用 SDK 的客户端、地址和超时配置，只绕过有问题的模型反序列化。
     */
    private JsonObject publicJson(String runMode, String path, String symbol) {
        ClientConfiguration config = restConfiguration(marketBaseUrl(runMode));
        ApiClient client = new ApiClient(config);
        List<Pair> query = new ArrayList<>(client.parameterToPair("symbol", symbol));
        okhttp3.Call call = client.buildCall(null, path, "GET", query, new ArrayList<>(), null, new HashMap<>(Map.of("Accept", "application/json")), new HashMap<>(),
            new HashMap<>(), new HashSet<>());
        ApiResponse<JsonObject> response = client.execute(call, JsonObject.class);
        JsonObject data = response.getData();
        if (data == null) {
            throw new IllegalStateException("Binance 公开行情响应为空: " + path);
        }
        return data;
    }

    /**
     * 订阅交易所公开行情流。
     */
    @Override
    public MarketStreamSession subscribeMarket(String symbol, String interval, Consumer<MarketEvent> listener) {
        return subscribeMarket("LIVE", symbol, interval, listener);
    }

    @Override
    public MarketStreamSession subscribeMarket(String runMode, String symbol, String interval, Consumer<MarketEvent> listener) {
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        String streamSymbol = normalized.toLowerCase(Locale.ROOT);
        String base = "TESTNET".equalsIgnoreCase(runMode) ? properties.getBinance().getTestnetMarketStreamUrl() : properties.getBinance().getMarketStreamUrl();
        Consumer<String> events = text -> {
            MarketEvent event = parseMarketEvent(text, normalized, interval);
            if (event != null) { listener.accept(event); }
        };
        Runnable disconnected = () -> listener.accept(new MarketEvent("DISCONNECTED", normalized, interval, null, null, Instant.now()));
        if (sdkStreamsEnabled()) {
            requireSdkStreamsCanConnect();
            BinanceSdkStreamTransport sdk = BinanceSdkStreamTransport.market(base, streamSymbol, interval, events, disconnected,
                properties.getBinance().getSdkStreamBacklogLimit());
            StreamHandle managed = trackWebsocket(sdk::close, disconnected);
            return new MarketStreamSession(managed::close);
        }
        String url = base + "?streams=" + streamSymbol + "@aggTrade/" + streamSymbol + "@markPrice@1s/" + streamSymbol + "@kline_" + interval;
        SocketConnection socket = new SocketConnection(url, events, disconnected);
        StreamHandle managed = trackWebsocket(socket::close, disconnected);
        return new MarketStreamSession(managed::close);
    }

    /** New subscriptions use this transport; existing subscriptions change only after safe reconnect. */
    boolean sdkStreamsEnabled() {
        return "BINANCE_SDK".equals(currentWebsocketTransport.get());
    }

    /** An SDK stream can bypass the configured HTTP proxy; reject instead of silently connecting directly. */
    private void requireSdkStreamsCanConnect() {
        QuantProperties.Proxy proxy = properties.getProxy();
        if (proxy != null && proxy.getHost() != null && !proxy.getHost().isBlank()) {
            throw new IllegalStateException("BINANCE_SDK Streams 尚未实现代理配置；请选 RAW_OKHTTP 传输");
        }
    }

    /** Node-local current mode; cluster deployments must switch every trading node. */
    public String websocketTransport() {
        return currentWebsocketTransport.get();
    }

    /**
     * Atomically choose the transport for future subscriptions and force old sessions to reconnect.
     * The market and account stream hubs receive DISCONNECTED and perform existing recovery/replay.
     * This is NOT a zero-gap migration; operators must verify LIVE protections and user-data health.
     *
     * @return number of sessions instructed to reconnect
     */
    public synchronized int switchWebsocketTransport(String target) {
        String normalized = validateWebsocketTransport(target);
        if (normalized.equals(currentWebsocketTransport.get())) { return 0; }
        currentWebsocketTransport.set(normalized);
        int count = 0;
        for (StreamHandle stream : java.util.List.copyOf(activeWebsocketStreams)) {
            stream.reconnect();
            count++;
        }
        log.warn("Binance WebSocket transport changed to {} on this node, reconnecting {} streams", normalized, count);
        return count;
    }

    private static String validateWebsocketTransport(String input) {
        String value = input == null ? "" : input.trim().toUpperCase(Locale.ROOT);
        if (!"RAW_OKHTTP".equals(value) && !"BINANCE_SDK".equals(value)) {
            throw new IllegalArgumentException("WebSocket transport must be RAW_OKHTTP or BINANCE_SDK");
        }
        return value;
    }

    private StreamHandle trackWebsocket(Runnable close, Runnable notifyDisconnected) {
        StreamHandle handle = new StreamHandle(close, notifyDisconnected);
        activeWebsocketStreams.add(handle);
        return handle;
    }

    private final class StreamHandle {
        private final Runnable closeTransport;
        private final Runnable notifyDisconnected;
        private final AtomicBoolean stopped = new AtomicBoolean();

        StreamHandle(Runnable closeTransport, Runnable notifyDisconnected) {
            this.closeTransport = closeTransport;
            this.notifyDisconnected = notifyDisconnected;
        }

        void close() {
            if (stopped.compareAndSet(false, true)) {
                activeWebsocketStreams.remove(this);
                try { closeTransport.run(); }
                catch (RuntimeException ex) { log.warn("Binance WebSocket close failed: {}", ex.getClass().getSimpleName()); }
            }
        }

        void reconnect() {
            if (stopped.get()) { return; }
            close();
            try { notifyDisconnected.run(); }
            catch (RuntimeException ex) { log.warn("Binance WebSocket reconnect notification failed"); }
        }
    }

    /** Original protocol transport: deterministic fail/replay path when SDK stream backlog is unsuitable. */
    private MarketEvent parseMarketEvent(String text, String symbol, String interval) {
        JsonObject root = gson.fromJson(text, JsonObject.class);
        JsonObject data = root.has("data") ? root.getAsJsonObject("data") : root;
        if (!data.has("e")) { return null; }
        if (!data.has("s") || !symbol.equalsIgnoreCase(data.get("s").getAsString())) {
            throw new IllegalStateException("Binance行情交易对与订阅不一致");
        }
        Instant time = Instant.ofEpochMilli(data.get("E").getAsLong());
        return switch (data.get("e").getAsString()) {
            case "aggTrade" -> new MarketEvent("TRADE", symbol, interval, data.get("p").getAsBigDecimal(), null, time, data.get("a").getAsLong(), Instant.now(), false);
            case "markPriceUpdate" -> new MarketEvent("MARK", symbol, interval, data.get("p").getAsBigDecimal(), null, time);
            case "kline" -> {
                JsonObject k = data.getAsJsonObject("k");
                if (!interval.equals(k.get("i").getAsString()) || !symbol.equalsIgnoreCase(k.get("s").getAsString())) {
                    throw new IllegalStateException("Binance K线交易对或周期与订阅不一致");
                }
                Kline candle = new Kline(Instant.ofEpochMilli(k.get("t").getAsLong()), Instant.ofEpochMilli(k.get("T").getAsLong()),
                    k.get("o").getAsBigDecimal(), k.get("h").getAsBigDecimal(), k.get("l").getAsBigDecimal(), k.get("c").getAsBigDecimal(), k.get("v").getAsBigDecimal(), k.get("x").getAsBoolean());
                yield new MarketEvent("KLINE", symbol, interval, null, candle, time);
            }
            default -> null;
        };
    }

    /** OkHttp 负责 ping/pong；重连、补偿及消费隔离统一由 Hub 管理。 */
    private final class SocketConnection extends okhttp3.WebSocketListener implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean connected = new AtomicBoolean();
        private final Consumer<String> messages;
        private final Runnable disconnected;
        private final okhttp3.WebSocket socket;

        private SocketConnection(String url, Consumer<String> messages, Runnable disconnected) {
            this.messages = messages;
            this.disconnected = disconnected;
            var builder = new okhttp3.OkHttpClient.Builder().connectionPool(restPool).dispatcher(restDispatcher)
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(false);
            QuantProperties.Proxy proxy = properties.getProxy();
            if (proxy != null && proxy.getHost() != null && !proxy.getHost().isBlank() && proxy.getPort() != null) {
                builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.getHost(), proxy.getPort())));
            }
            socket = builder.build().newWebSocket(new okhttp3.Request.Builder().url(url).build(), this);
        }

        @Override public void onOpen(okhttp3.WebSocket socket, okhttp3.Response response) { connected.set(!closed.get()); }
        @Override public void onMessage(okhttp3.WebSocket socket, String text) {
            if (closed.get()) { return; }
            try { messages.accept(text); } catch (RuntimeException ex) {
                // 不输出私有事件正文/URL，防止 listenKey 或账户信息进入错误日志。
                log.warn("Binance WebSocket 事件处理失败: {}", ex.getClass().getSimpleName());
                fail(); socket.cancel();
            }
        }
        @Override public void onFailure(okhttp3.WebSocket socket, Throwable failure, okhttp3.Response response) {
            log.warn("Binance WebSocket传输失败: errorType={}, httpStatus={}", failure.getClass().getSimpleName(), response == null ? null : response.code());
            fail();
        }
        @Override public void onClosing(okhttp3.WebSocket socket, int code, String reason) { socket.close(code, null); fail(); }
        private void fail() {
            connected.set(false);
            if (closed.compareAndSet(false, true)) { disconnected.run(); }
        }
        @Override public void close() { closed.set(true); connected.set(false); socket.cancel(); }
    }

    /**
     * 查询交易账户资产信息。
     */
    @Override
    public AccountSnapshot queryAccount(Credential credential) {
        var result = new java.util.concurrent.CompletableFuture<AccountSnapshot>();
        var running = accountQueries.putIfAbsent(credential, result);
        if (running != null) {
            try { return running.get(15, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new org.dromara.quant.exchange.ExchangeMarketPort.RequestDeferredException();
            } catch (java.util.concurrent.TimeoutException ex) {
                throw new org.dromara.quant.exchange.ExchangeMarketPort.RequestDeferredException();
            } catch (java.util.concurrent.ExecutionException ex) {
                if (ex.getCause() instanceof RuntimeException error) { throw error; }
                throw new IllegalStateException("账户查询失败");
            }
        }
        try {
            AccountSnapshot snapshot = queryAccountFresh(credential);
            result.complete(snapshot);
            return snapshot;
        } catch (RuntimeException ex) {
            result.completeExceptionally(ex);
            throw ex;
        } finally { accountQueries.remove(credential, result); }
    }

    @Override
    public AccountSnapshot queryAccountFresh(Credential credential) {
        try {
            ApiResponse<AccountInformationV3Response> response = api(credential, RestRequestScheduler.Kind.ACCOUNT).accountInformationV3(RECV_WINDOW_MILLIS);
            var data = response.getData();
            return new AccountSnapshot(decimal(data.getTotalWalletBalance()), decimal(data.getAvailableBalance()), decimal(data.getTotalMarginBalance()),
                decimal(data.getTotalUnrealizedProfit()));
        } catch (RuntimeException ex) { throw requestFailure("QUERY_ACCOUNT", ex); }
    }

    /**
     * 查询交易所收入/财务流水。
     */
    @Override
    public List<IncomeSnapshot> queryIncomeHistory(Credential credential, String symbol, String incomeType, TimeRange range, Long limit) {
        requireCredential(credential);
        Long start = range == null || range.from() == null ? null : range.from().toEpochMilli();
        Long end = range == null || range.to() == null ? null : range.to().toEpochMilli();
        IncomeType type = incomeType == null || incomeType.isBlank() ? null : IncomeType.fromValue(incomeType);
        GetIncomeHistoryResponse data = api(credential).getIncomeHistory(symbol, type, start, end, null, limit == null ? 1000L : limit, RECV_WINDOW_MILLIS).getData();
        if (data == null) {
            return List.of();
        }
        List<IncomeSnapshot> result = new ArrayList<>();
        for (GetIncomeHistoryResponseInner item : data) {
            result.add(new IncomeSnapshot(item.getSymbol(), String.valueOf(item.getIncomeType()), decimal(item.getIncome()), item.getAsset(), item.getTranId(), item.getInfo(),
                instant(item.getTime())));
        }
        return result;
    }

    /**
     * 查询账户持仓模式。
     */
    @Override
    public PositionMode queryPositionMode(Credential credential) {
        requireCredential(credential);
        var data = api(credential).getCurrentPositionMode(RECV_WINDOW_MILLIS).getData();
        return new PositionMode(Boolean.TRUE.equals(data.getDualSidePosition()));
    }

    /**
     * 设置账户持仓模式。
     */
    @Override
    public void setPositionMode(Credential credential, boolean hedgeMode) {
        requireCredential(credential);
        ChangePositionModeRequest request = new ChangePositionModeRequest();
        request.setDualSidePosition(hedgeMode ? "TRUE" : "FALSE");
        try {
            api(credential).changePositionMode(request);
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            if (message == null || (!message.contains("No need to change") && !message.contains("-4059"))) {
                throw new IllegalStateException("设置 Binance 持仓模式失败", ex);
            }
        }
    }

    /**
     * 设置交易对保证金模式。
     */
    private static com.binance.connector.client.common.ApiException apiError(RuntimeException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof com.binance.connector.client.common.ApiException error) { return error; }
            current = current.getCause();
        }
        return null;
    }

    private static Integer errorCode(com.binance.connector.client.common.ApiException error) {
        try {
            return com.google.gson.JsonParser.parseString(error.getResponseBody()).getAsJsonObject().get("code").getAsInt();
        } catch (RuntimeException ignored) { return null; }
    }

    private static ExchangeRequestException requestFailure(String stage, RuntimeException ex) {
        if (ex instanceof ExchangeRequestException safe) { return safe; }
        var error = apiError(ex);
        String message = ex.getClass().getSimpleName();
        if (error != null) {
            try {
                message = com.google.gson.JsonParser.parseString(error.getResponseBody()).getAsJsonObject().get("msg").getAsString();
            } catch (RuntimeException ignored) { }
        }
        // 不附带原始异常，避免 SDK 的 URL、签名和请求头进入日志。
        return new ExchangeRequestException(stage,
            error == null ? null : error.getCode(), error == null ? null : errorCode(error), message);
    }

    @Override
    public void setMarginType(Credential credential, String symbol, String marginType) {
        requireCredential(credential);
        ChangeMarginTypeRequest request = new ChangeMarginTypeRequest();
        request.setSymbol(symbol);
        request.setMarginType("ISOLATED".equalsIgnoreCase(marginType) ? MarginType.ISOLATED : MarginType.CROSSED);
        try {
            api(credential, true).changeMarginType(request);
        } catch (RuntimeException ex) {
            com.binance.connector.client.common.ApiException apiError = apiError(ex);
            if (apiError != null && apiError.getCode() == 400 && Integer.valueOf(-4046).equals(errorCode(apiError))) {
                return;
            }
            throw requestFailure("SET_MARGIN_TYPE", ex);
        }
    }

    /**
     * 设置交易对杠杆。
     */
    @Override
    public void setLeverage(Credential credential, String symbol, int leverage) {
        requireCredential(credential);
        ChangeInitialLeverageRequest request = new ChangeInitialLeverageRequest();
        request.setSymbol(symbol);
        request.setLeverage((long) leverage);
        try {
            api(credential, true).changeInitialLeverage(request);
        } catch (RuntimeException ex) {
            throw requestFailure("SET_LEVERAGE", ex);
        }
    }

    /**
     * Read Binance positionRisk V2 without filtering out zero-quantity rows.
     * A single zero-quantity row can carry active account-symbol leverage and
     * isolated/cross mode; account aggregates or cached open positions cannot.
     *
     * Every returned LONG/SHORT/BOTH row must agree. Missing getters, empty
     * data or conflicting facts fail closed rather than acknowledging settings.
     */
    @Override
    public org.dromara.quant.exchange.ExchangeAccountPort.SymbolTradingSettings querySymbolTradingSettings(
        Credential credential, String symbol) {
        requireCredential(credential);
        if (symbol == null || !symbol.matches("[a-zA-Z0-9]{2,32}")) {
            throw new IllegalArgumentException("Binance 交易设置回查缺少有效交易对");
        }
        var rows = api(credential, RestRequestScheduler.Kind.ACCOUNT)
            .positionInformationV2(symbol.trim().toUpperCase(Locale.ROOT), RECV_WINDOW_MILLIS)
            .getData();
        if (rows == null || rows.isEmpty()) {
            throw new IllegalStateException("Binance 未返回可核实的交易对保证金/杠杆配置");
        }
        String expectedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
        String actualMode = null;
        Integer actualLeverage = null;
        for (var row : rows) {
            String actualSymbol = string(row, "getSymbol");
            if (!expectedSymbol.equalsIgnoreCase(actualSymbol)) {
                throw new IllegalStateException("Binance 配置回查返回了不匹配的交易对");
            }
            String isolated = string(row, "getIsolated");
            String mode = null;
            if ("true".equalsIgnoreCase(isolated)) mode = "ISOLATED";
            else if ("false".equalsIgnoreCase(isolated)) mode = "CROSSED";
            if (mode == null) {
                String raw = string(row, "getMarginType");
                if ("ISOLATED".equalsIgnoreCase(raw)) mode = "ISOLATED";
                else if ("CROSS".equalsIgnoreCase(raw) || "CROSSED".equalsIgnoreCase(raw))
                    mode = "CROSSED";
            }
            String rawLeverage = string(row, "getLeverage");
            if (mode == null || rawLeverage == null) {
                throw new IllegalStateException("Binance 配置回查缺少保证金模式或杠杆");
            }
            int leverage;
            try {
                leverage = Integer.parseInt(rawLeverage);
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("Binance 返回的杠杆值无效");
            }
            if (leverage < 1 || leverage > 125
                || actualMode != null && !actualMode.equals(mode)
                || actualLeverage != null && actualLeverage != leverage) {
                throw new IllegalStateException("Binance 不同持仓方向的保证金/杠杆配置不一致");
            }
            actualMode = mode;
            actualLeverage = leverage;
        }
        return new org.dromara.quant.exchange.ExchangeAccountPort.SymbolTradingSettings(
            expectedSymbol, actualMode, actualLeverage);
    }

    /**
     * 查询交易所当前持仓。
     */
    @Override
    public List<PositionSnapshot> queryPositions(Credential credential, String symbol) {
        var response = api(credential, RestRequestScheduler.Kind.ACCOUNT).positionInformationV3(symbol == null ? null : symbol.trim(), RECV_WINDOW_MILLIS).getData();
        var positions = response.stream().filter(item -> symbol == null || symbol.equalsIgnoreCase(item.getSymbol()))
            .filter(item -> decimal(item.getPositionAmt()).signum() != 0).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (positions.isEmpty()) {
            return List.of();
        }
        var leverageByPosition =
            api(credential, RestRequestScheduler.Kind.ACCOUNT).positionInformationV2(symbol == null ? null : symbol.trim(), RECV_WINDOW_MILLIS).getData().stream().collect(java.util.stream.Collectors.toMap(item -> item.getSymbol() + ":" + item.getPositionSide(), item -> Integer.valueOf(item.getLeverage()), (first, second) -> first));
        return positions.stream().map(item -> new PositionSnapshot(item.getSymbol(), item.getPositionSide(), decimal(item.getPositionAmt()), decimal(item.getEntryPrice()), decimal(item.getMarkPrice()), decimal(item.getUnRealizedProfit()), decimal(item.getLiquidationPrice()), decimal(item.getPositionInitialMargin()), leverageByPosition.get(item.getSymbol() + ":" + item.getPositionSide()), null, null, item.getUpdateTime() == null ? null : Instant.ofEpochMilli(item.getUpdateTime()))).toList();
    }

    /**
     * 查询活动订单。
     */
    @Override
    public List<OrderSnapshot> queryOrders(Credential credential, String symbol) {
        ApiResponse<CurrentAllOpenOrdersResponse> response = api(credential).currentAllOpenOrders(symbol == null ? null : symbol.trim(), RECV_WINDOW_MILLIS);
        if (response == null || response.getData() == null) {
            return List.of();
        }
        return response.getData().stream().map(item -> new OrderSnapshot(item.getSymbol(), item.getOrderId(), item.getClientOrderId(), item.getSide(), item.getType(),
            item.getStatus(), decimal(item.getPrice()), decimal(item.getOrigQty()), decimal(item.getExecutedQty()), instant(item.getUpdateTime()), decimal(string(item, "getAvgPrice")))).toList();
    }

    /**
     * 查询指定订单。
     */
    @Override
    public OrderSnapshot queryOrder(Credential credential, String symbol, Long orderId, String clientOrderId) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("查询订单必须指定交易对");
        }
        if (orderId == null && (clientOrderId == null || clientOrderId.isBlank())) {
            throw new IllegalArgumentException("查询订单必须指定 orderId 或 clientOrderId");
        }
        if ("PAPER".equalsIgnoreCase(credential.runMode())) {
            return null;
        }
        Object data;
        try {
            data = api(credential, RestRequestScheduler.Kind.ACCOUNT).queryOrder(symbol.trim(), orderId, clientOrderId, RECV_WINDOW_MILLIS).getData();
        } catch (RuntimeException ex) {
            var error = apiError(ex);
            if (error != null && error.getCode() == 400 && Integer.valueOf(-2013).equals(errorCode(error))) {
                return null;
            }
            throw requestFailure("QUERY_ORDER", ex);
        }
        if (data == null) {
            return null;
        }
        Long actualOrderId = number(data, "getOrderId");
        if (actualOrderId == null) {
            actualOrderId = orderId;
        }
        String actualClientOrderId = string(data, "getClientOrderId");
        if (actualClientOrderId == null || actualClientOrderId.isBlank()) {
            actualClientOrderId = clientOrderId;
        }
        String actualSymbol = string(data, "getSymbol");
        if (actualSymbol == null || actualSymbol.isBlank()) {
            actualSymbol = symbol.trim();
        }
        String status = string(data, "getStatus");
        if (status == null || status.isBlank()) {
            status = "UNKNOWN";
        }
        String type = string(data, "getType");
        if (type == null || type.isBlank()) {
            type = "UNKNOWN";
        }
        return new OrderSnapshot(actualSymbol, actualOrderId, actualClientOrderId, string(data, "getSide"), type, status, decimal(string(data, "getPrice")), decimal(string(data,
            "getOrigQty")), decimal(string(data, "getExecutedQty")), instant(number(data, "getUpdateTime")), decimal(string(data, "getAvgPrice")));
    }

    /**
     * 分页查询账户成交。
     */
    @Override
    public List<TradeSnapshot> queryTrades(Credential credential, String symbol, TimeRange range) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("查询成交记录必须指定交易对");
        }
        Long from = range == null || range.from() == null ? null : range.from().toEpochMilli();
        Long to = range == null || range.to() == null ? null : range.to().toEpochMilli();
        ApiResponse<AccountTradeListResponse> response = api(credential).accountTradeList(symbol == null ? null : symbol.trim(), null, from, to, null, 1000L, RECV_WINDOW_MILLIS);
        return response.getData().stream().map(item -> new TradeSnapshot(item.getSymbol(), item.getId(), item.getOrderId(), item.getSide(), decimal(item.getQty()),
            decimal(item.getPrice()), decimal(item.getCommission()), decimal(item.getRealizedPnl()), instant(item.getTime()))).toList();
    }

    /**
     * 查询历史订单。
     */
    @Override
    public List<OrderSnapshot> queryOrderHistory(Credential credential, String symbol, TimeRange range, Long limit) {
        requireCredential(credential);
        Long start = range == null || range.from() == null ? null : range.from().toEpochMilli();
        Long end = range == null || range.to() == null ? null : range.to().toEpochMilli();
        var data = api(credential).allOrders(symbol, null, start, end, limit == null ? 1000L : limit, RECV_WINDOW_MILLIS).getData();
        if (data == null) {
            return List.of();
        }
        return data.stream().map(item -> new OrderSnapshot(item.getSymbol(), item.getOrderId(), item.getClientOrderId(), item.getSide(), item.getType(), item.getStatus(),
            decimal(item.getPrice()), decimal(item.getOrigQty()), decimal(item.getExecutedQty()), instant(item.getUpdateTime()), decimal(string(item, "getAvgPrice")))).toList();
    }

    /**
     * 查询交易所条件/保护订单。
     */
    @Override
    public List<ProtectionOrderSnapshot> queryProtectionOrders(Credential credential, String symbol) {
        requireCredential(credential);
        var data = api(credential).currentAllAlgoOpenOrders("CONDITIONAL", symbol, null, RECV_WINDOW_MILLIS).getData();
        if (data == null) {
            return List.of();
        }
        return data.stream().map(item -> new ProtectionOrderSnapshot(string(item, "getSymbol"), number(item, "getAlgoId"), string(item, "getClientAlgoId"), string(item,
            "getAlgoStatus"), string(item, "getSide"), string(item, "getPositionSide"), string(item, "getOrderType"), decimal(string(item, "getQuantity")), decimal(string(item,
            "getTriggerPrice")), instant(number(item, "getUpdateTime")), null, Boolean.valueOf(string(item, "getReduceOnly")), Boolean.valueOf(string(item, "getClosePosition")))).filter(item -> symbol == null || symbol.equalsIgnoreCase(item.symbol())).toList();
    }

    @Override
    public ProtectionOrderSnapshot queryProtectionOrder(Credential credential, Long orderId, String clientOrderId, String symbol) {
        requireCredential(credential);
        com.binance.connector.client.derivatives_trading_usds_futures.rest.model.QueryAlgoOrderResponse data;
        try {
            data = api(credential).queryAlgoOrder(orderId, clientOrderId, RECV_WINDOW_MILLIS).getData();
        } catch (com.binance.connector.client.common.ApiException ex) {
            try {
                JsonObject error = com.google.gson.JsonParser.parseString(ex.getResponseBody()).getAsJsonObject();
                if (error.has("code") && error.get("code").getAsInt() == -2013) {
                    throw new ProtectionOrderAbsentException();
                }
            } catch (ProtectionOrderAbsentException absent) {
                throw absent;
            } catch (RuntimeException ignored) {
                // 保留网络/权限/限流等未知结果，不能当作订单不存在。
            }
            throw ex;
        }
        if (data == null) { return null; }
        return new ProtectionOrderSnapshot(string(data, "getSymbol"), number(data, "getAlgoId"), string(data, "getClientAlgoId"), string(data, "getAlgoStatus"),
            string(data, "getSide"), string(data, "getPositionSide"), string(data, "getOrderType"), decimal(string(data, "getQuantity")),
            decimal(string(data, "getTriggerPrice")), instant(number(data, "getUpdateTime")), decimal(string(data, "getActualQty")), null, null,
            number(data, "getActualOrderId"), decimal(string(data, "getActualPrice")));
    }

    /**
     * 向交易所提交普通订单。
     */
    @Override
    public OrderResult placeOrder(OrderRequest request) {
        if (!request.live()) {
            return new OrderResult(null, request.clientOrderId(), "FILLED");
        }
        requireOrderDeadline(request);
        requireCredential(request.credential());
        Side side = Side.fromValue(request.side().toUpperCase());
        OrderType type = OrderType.fromValue(request.type().toUpperCase());
        NewOrderRequest order =
            new NewOrderRequest().symbol(request.symbol()).side(side).type(type).quantity(request.quantity().doubleValue()).newClientOrderId(request.clientOrderId()).newOrderRespType(NewOrderRespType.RESULT);
        if (request.positionSide() != null && !request.positionSide().isBlank()) {
            order.positionSide(request.positionSide().toUpperCase());
        }
        if (request.price() != null) {
            order.price(request.price().doubleValue());
        }
        if (request.reduceOnly() && !hedgePositionSide(request.positionSide())) {
            order.reduceOnly(ReduceOnly.TRUE);
        }
        // api() 内含限流排队和时间同步；准备结束后仍必须检查原始信号截止时间。
        DerivativesTradingUsdsFuturesRestApi client;
        try {
            client = api(request.credential());
        } catch (RuntimeException ex) {
            throw new OrderNotSentException("交易请求准备失败，订单未发送");
        }
        requireOrderDeadline(request);
        ApiResponse<NewOrderResponse> response;
        try {
            response = client.newOrder(order);
        } catch (com.binance.connector.client.common.ApiException ex) {
            Integer code = null;
            String message = null;
            try {
                JsonObject error = com.google.gson.JsonParser.parseString(ex.getResponseBody()).getAsJsonObject();
                code = error.get("code").getAsInt();
                message = error.get("msg").getAsString();
            } catch (RuntimeException ignored) { }
            // 只分类已知参数拒绝；503、网络超时及不明确响应仍交给UNKNOWN恢复。
            if (ex.getCode() == 400 && code != null && Set.of(-4023, -4004, -4005, -1111, -4164).contains(code)) {
                tradingRules.remove("TESTNET".equalsIgnoreCase(request.credential().runMode()) ? "TESTNET" : "LIVE");
                throw new OrderRejectedException(code, message);
            }
            throw requestFailure("PLACE_ORDER", ex);
        }
        NewOrderResponse data = response.getData();
        return new OrderResult(data.getOrderId(), data.getClientOrderId(), data.getStatus());
    }

    private static void requireOrderDeadline(OrderRequest request) {
        if (request.validUntil() != null && !Instant.now().isBefore(request.validUntil())) {
            throw new OrderNotSentException("开仓信号在交易请求准备期间过期，订单未发送，等待新的行情");
        }
    }

    /**
     * 撤销交易所普通订单。
     */
    @Override
    public void cancelOrder(CancelOrderRequest request) {
        if (!request.live()) {
            return;
        }
        requireCredential(request.credential());
        api(request.credential(), true).cancelOrder(request.symbol(), request.orderId(), request.clientOrderId(), RECV_WINDOW_MILLIS);
    }

    /**
     * 提交交易所条件/保护订单。
     */
    @Override
    public ProtectionOrderResult placeProtectionOrder(ProtectionOrderRequest request) {
        if (!request.live()) {
            return new ProtectionOrderResult(null, request.clientOrderId(), "PAPER_ACCEPTED");
        }
        requireCredential(request.credential());
        NewAlgoOrderRequest algo =
            new NewAlgoOrderRequest().algoType(AlgoType.CONDITIONAL).symbol(request.symbol()).side(Side.fromValue(request.side().toUpperCase())).type(OrderType.fromValue((request.orderType() == null ? "STOP_MARKET" : request.orderType()).toUpperCase())).quantity(request.quantity().doubleValue()).triggerPrice(request.stopPrice().doubleValue()).workingType(WorkingType.CONTRACT_PRICE).clientAlgoId(request.clientOrderId());
        if (request.positionSide() != null && !request.positionSide().isBlank()) {
            algo.positionSide(request.positionSide().toUpperCase());
        }
        if (!hedgePositionSide(request.positionSide())) {
            algo.reduceOnly(ReduceOnly.TRUE);
        }
        ApiResponse<NewAlgoOrderResponse> response;
        try {
            response = api(request.credential(), true).newAlgoOrder(algo);
        } catch (RuntimeException ex) {
            var error = apiError(ex);
            Integer code = error == null ? null : errorCode(error);
            if (error != null && error.getCode() == 400 && code != null && Set.of(-4023, -4004, -4005, -1111, -4164, -4014, -2021).contains(code)) {
                tradingRules.remove("TESTNET".equalsIgnoreCase(request.credential().runMode()) ? "TESTNET" : "LIVE");
                throw new OrderRejectedException(code, "保护单参数被交易所明确拒绝");
            }
            throw requestFailure("PLACE_PROTECTION_ORDER", ex);
        }
        NewAlgoOrderResponse data = response.getData();
        return new ProtectionOrderResult(data.getAlgoId(), data.getClientAlgoId(), data.getAlgoStatus());
    }

    /**
     * 撤销交易所条件/保护订单。
     */
    @Override
    public void cancelProtectionOrder(CancelProtectionOrderRequest request) {
        if (!request.live()) {
            return;
        }
        if (request.orderId() == null && (request.clientOrderId() == null || request.clientOrderId().isBlank())) {
            throw new IllegalArgumentException("撤销保护单必须指定 orderId 或 clientOrderId");
        }
        requireCredential(request.credential());
        api(request.credential(), true).cancelAlgoOrder(request.orderId(), request.clientOrderId(), RECV_WINDOW_MILLIS);
    }

    /**
     * 订阅账户私有事件流。
     */
    @Override
    public UserDataStreamSession subscribeUserData(Credential credential, Consumer<UserDataEvent> listener) {
        requireCredential(credential);
        String listenKey = api(credential).startUserDataStream().getData().getListenKey();
        if (listenKey == null || listenKey.isBlank()) { throw new IllegalStateException("Binance 用户数据流未返回 listenKey"); }
        boolean testnet = "TESTNET".equalsIgnoreCase(credential.runMode());
        String base = testnet ? properties.getBinance().getTestnetUserDataStreamUrl() : "wss://fstream.binance.com/private/ws/";
        Consumer<String> events = text -> listener.accept(parseUserDataEvent(text));
        Runnable disconnected = () -> listener.accept(new UserDataEvent("DISCONNECTED", Instant.now(), null));
        if (sdkStreamsEnabled()) {
            try {
                requireSdkStreamsCanConnect();
                // SDK userData(listenKey) subscribes via /private/stream (not the legacy /private/ws/{key} endpoint).
                String sdkBase = base.endsWith("/private/ws/") ? base.substring(0, base.length() - "/private/ws/".length()) + "/stream" : base;
                BinanceSdkStreamTransport sdk = BinanceSdkStreamTransport.user(sdkBase, listenKey, events, disconnected,
                    properties.getBinance().getSdkStreamBacklogLimit());
                StreamHandle managed = trackWebsocket(() -> {
                    sdk.close();
                    try { api(credential).closeUserDataStream(); } catch (RuntimeException ignored) { }
                }, disconnected);
                return new UserDataStreamSession(listenKey, Instant.now(), () -> api(credential).keepaliveUserDataStream(),
                    managed::close, sdk::connected);
            } catch (RuntimeException ex) {
                try { api(credential).closeUserDataStream(); } catch (RuntimeException ignored) { }
                throw ex;
            }
        }
        SocketConnection socket = new SocketConnection(base + listenKey, events, disconnected);
        StreamHandle managed = trackWebsocket(() -> {
            socket.close();
            try { api(credential).closeUserDataStream(); } catch (RuntimeException ignored) { }
        }, disconnected);
        return new UserDataStreamSession(listenKey, Instant.now(), () -> api(credential).keepaliveUserDataStream(),
            managed::close, socket.connected::get);
    }

    private UserDataEvent parseUserDataEvent(String text) {
        JsonObject json = gson.fromJson(text, JsonObject.class);
        String type = json.has("e") ? json.get("e").getAsString() : "UNKNOWN";
        Instant time = json.has("E") ? Instant.ofEpochMilli(json.get("E").getAsLong()) : Instant.now();
        return new UserDataEvent(type, time, text);
    }

    /**
     * 根据凭证运行模式创建 Binance API 客户端。
     */
    /** 公开行情仅选择环境，不携带账户签名或密钥。 */
    private DerivativesTradingUsdsFuturesRestApi marketApi(String runMode) {
        return new DerivativesTradingUsdsFuturesRestApi(restClient(new ApiClient(restConfiguration(marketBaseUrl(runMode)))));
    }

    private String marketBaseUrl(String runMode) {
        return "TESTNET".equalsIgnoreCase(runMode) ? properties.getBinance().getTestnetUrl() : properties.getBinance().getBaseUrl();
    }

    private DerivativesTradingUsdsFuturesRestApi api(Credential credential) {
        return api(credential, RestRequestScheduler.Kind.BACKGROUND);
    }

    private DerivativesTradingUsdsFuturesRestApi api(Credential credential, boolean urgent) {
        return api(credential, urgent ? RestRequestScheduler.Kind.TRADE : RestRequestScheduler.Kind.BACKGROUND);
    }

    private DerivativesTradingUsdsFuturesRestApi api(Credential credential, RestRequestScheduler.Kind kind) {
        boolean testnet = credential != null && "TESTNET".equalsIgnoreCase(credential.runMode());
        ClientConfiguration config = restConfiguration(testnet ? properties.getBinance().getTestnetUrl() : properties.getBinance().getBaseUrl(), kind);
        if (credential != null) {
            SignatureConfiguration signature = new SignatureConfiguration();
            signature.setApiKey(credential.apiKey());
            signature.setSecretKey(credential.secretKey());
            config.setSignatureConfiguration(signature);
            long offset = clockOffset(config.getUrl());
            return new DerivativesTradingUsdsFuturesRestApi(restClient(new ApiClient(config, new OffsetAuthenticationFactory(offset))));
        }
        return new DerivativesTradingUsdsFuturesRestApi(restClient(new ApiClient(config)));
    }

    private ApiClient restClient(ApiClient client) {
        // 共享连接池，仍保留 SDK 的每请求签名与代理配置；有限超时避免阻塞本地风控。
        client.setHttpClient(client.getHttpClient().newBuilder().connectionPool(restPool).dispatcher(restDispatcher)
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS).readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(false).build());
        return client;
    }

    /**
     * REST、公开行情和服务器时间请求使用相同的可选 HTTP 代理；未配置时由 SDK 直连。
     */
    ClientConfiguration restConfiguration(String baseUrl) {
        return restConfiguration(baseUrl, RestRequestScheduler.Kind.BACKGROUND);
    }

    private ClientConfiguration restConfiguration(String baseUrl, RestRequestScheduler.Kind kind) {
        long queueStarted = System.nanoTime();
        restScheduler.acquire(kind);
        log.debug("Binance REST 获准执行 kind={} queueMillis={}", kind,
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queueStarted));
        ClientConfiguration config = new ClientConfiguration();
        config.setUrl(baseUrl);
        // SDK 默认会自动重试 429，容易放大 IP 权重；由上层低频调度和退避处理。
        config.setRetries(0);
        QuantProperties.Proxy proxy = properties.getProxy();
        if (proxy == null || (proxy.getHost() == null || proxy.getHost().isBlank()) && proxy.getPort() == null) {
            log.debug("Binance REST 请求未配置代理，使用直连: baseUrl={}", baseUrl);
            return config;
        }
        if (proxy.getHost() == null || proxy.getHost().isBlank() || proxy.getPort() == null || proxy.getPort() < 1 || proxy.getPort() > 65535) {
            throw new IllegalStateException("量化代理配置需要有效的 host 和 port");
        }
        config.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.getHost().trim(), proxy.getPort())));
//        log.info("Binance REST 请求启用 HTTP 代理: baseUrl={}, proxyHost={}, proxyPort={}", baseUrl, proxy.getHost().trim(), proxy.getPort());
        return config;
    }

    /**
     * 在同一进程内限制 REST 请求启动速率。
     */

    /**
     * 获取 Binance 服务器时间偏移。
     */
    private long clockOffset(String baseUrl) {
        long now = System.currentTimeMillis();
        ClockOffset cached = clockOffsets.get(baseUrl);
        if (cached != null && now - cached.updatedAt() < CLOCK_OFFSET_REFRESH_MILLIS) {
            return cached.value();
        }
        synchronized (clockOffsetRefreshLocks.computeIfAbsent(baseUrl, ignored -> new Object())) {
            // 等待中的线程必须再次检查缓存，不能重复发起同一轮刷新。
            cached = clockOffsets.get(baseUrl);
            if (cached != null && System.currentTimeMillis() - cached.updatedAt() < CLOCK_OFFSET_REFRESH_MILLIS) {
                return cached.value();
            }
            long refreshStartedAt = System.currentTimeMillis();
            ClientConfiguration config = restConfiguration(baseUrl);
            long requestStartedAt = System.currentTimeMillis();
            ApiResponse<CheckServerTimeResponse> response = new DerivativesTradingUsdsFuturesRestApi(restClient(new ApiClient(config))).checkServerTime();
            long requestFinishedAt = System.currentTimeMillis();
            CheckServerTimeResponse data = response.getData();
            if (data == null || data.getServerTime() == null) {
                throw new IllegalStateException("无法获取 Binance 服务端时间");
            }
            long localMidpoint = requestStartedAt + (requestFinishedAt - requestStartedAt) / 2;
            long offset = data.getServerTime() - localMidpoint;
            clockOffsets.put(baseUrl, new ClockOffset(offset, requestFinishedAt));
            log.info("Binance 服务端时间偏移已刷新: baseUrl={}, offsetMillis={}, recvWindowMillis={}, syncElapsedMillis={}, requestElapsedMillis={}",
                baseUrl, offset, RECV_WINDOW_MILLIS, requestFinishedAt - refreshStartedAt, requestFinishedAt - requestStartedAt);
            return offset;
        }
    }


    static boolean hedgePositionSide(String positionSide) {
        return "LONG".equalsIgnoreCase(positionSide) || "SHORT".equalsIgnoreCase(positionSide);
    }

    /**
     * Binance 服务器时间偏移缓存。
     *
     * @param value     内部状态值
     * @param updatedAt 最后更新时间
     */
    private record ClockOffset(long value, long updatedAt) {
    }

    /**
     * 支持服务器时间偏移的 Binance 鉴权工厂。
     */
    private static final class OffsetAuthenticationFactory extends BinanceAuthenticationFactory {
        /**
         * Binance 服务器时间偏移。
         */
        private final long offset;

        /**
         * 创建带服务器时间偏移支持的 Binance 鉴权工厂。
         */
        private OffsetAuthenticationFactory(long offset) {
            this.offset = offset;
        }

        /**
         * 创建带服务器时间偏移的 Binance 鉴权实现。
         */
        @Override
        public Authentication getAuthentication(SignatureConfiguration configuration) {
            SignatureGenerator generator = new SignatureGeneratorFactory().getSignatureGenerator(configuration);
            return generator == null ? null : new OffsetSignatureAuthentication(configuration.getApiKey(), generator, offset);
        }
    }

    /**
     * Mirrors the request-body classification used by the official SDK's HTTP
     * transport without depending on OkHttp's non-public internal classes.
     * Keep this rule aligned with connector updates to prevent signature drift.
     */
    static boolean requiresRequestBody(String method) {
        if (method == null) { return false; }
        return switch (method.toUpperCase(java.util.Locale.ROOT)) {
            case "POST", "PUT", "PATCH", "PROPPATCH", "REPORT" -> true;
            default -> false;
        };
    }

    /**
     * 在 Binance 签名参数中应用服务器时间偏移的鉴权实现。
     */
    private static final class OffsetSignatureAuthentication extends BinanceBaseAuthentication {
        /**
         * 交易所 API Key。
         */
        private final String apiKey;
        /**
         * Binance 请求签名生成器。
         */
        private final SignatureGenerator generator;
        /**
         * Binance 服务器时间偏移。
         */
        private final long offset;

        /**
         * 创建应用服务器时间偏移的 Binance 签名鉴权器。
         */
        private OffsetSignatureAuthentication(String apiKey, SignatureGenerator generator, long offset) {
            this.apiKey = apiKey;
            this.generator = generator;
            this.offset = offset;
        }

        /**
         * 在签名参数中应用服务器时间偏移。
         */
        @Override
        public void applyToParams(List<Pair> queryParams, Map<String, String> headerParams, Map<String, String> cookieParams, String payload, String method, URI uri) {
            headerParams.put("X-MBX-APIKEY", apiKey);
            queryParams.add(new Pair("timestamp", String.valueOf(System.currentTimeMillis() + offset)));
            String input = joinQueryParameters(queryParams);
            if (requiresRequestBody(method) && payload != null && !payload.isEmpty()) {
                input += payload;
            }
            try {
                queryParams.add(new Pair("signature", generator.signAsString(input)));
            } catch (CryptoException ex) {
                throw new IllegalStateException("Binance 请求签名失败", ex);
            }
        }
    }
}

