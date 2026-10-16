package org.dromara.binance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binance USDⓈ-M Futures 服务器时间同步工具。
 * <p>
 * 通过公共时间接口获取服务器时间，计算本机时间偏移量。
 */
public final class BinanceTimeSync {

    private static final String SERVER_TIME_URL = "https://fapi.binance.com/fapi/v1/time";

    private static final Pattern SERVER_TIME_PATTERN = Pattern.compile("\"serverTime\"\\s*:\\s*(\\d+)");

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * 本机时间相对于服务器时间的偏移量，单位毫秒。
     */
    private static volatile long timeOffset;

    /**
     * 是否已完成时间同步。
     */
    private static volatile boolean synced;

    private BinanceTimeSync() {
    }

    /**
     * 同步 Binance 服务器时间。
     * <p>
     * 使用请求前后的本机时间中点估算网络延迟影响。
     */
    public static synchronized void sync() throws Exception {
        long requestTime = System.currentTimeMillis();

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(SERVER_TIME_URL)).timeout(Duration.ofSeconds(5)).GET().build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        long responseTime = System.currentTimeMillis();

        if (response.statusCode() != 200) {
            throw new IllegalStateException("获取 Binance 服务器时间失败，HTTP 状态码：" + response.statusCode() + "，响应内容：" + response.body());
        }

        Matcher matcher = SERVER_TIME_PATTERN.matcher(response.body());

        if (!matcher.find()) {
            throw new IllegalStateException("无法解析 Binance 服务器时间：" + response.body());
        }

        long serverTime = Long.parseLong(matcher.group(1));

        long localMidpoint = requestTime + (responseTime - requestTime) / 2;

        timeOffset = serverTime - localMidpoint;
        synced = true;

        System.out.println("Binance 服务器时间同步成功");
        System.out.println("本机时间偏移量：" + timeOffset + " ms");
        System.out.println("校准后时间：" + currentTimeMillis());
    }

    /**
     * 获取校准后的当前时间戳。
     * <p>
     * 注意：只有调用方主动使用此方法时，
     * 时间偏移量才会应用于时间戳。
     *
     * @return Unix 毫秒时间戳
     */
    public static long currentTimeMillis() {
        return System.currentTimeMillis() + timeOffset;
    }

    /**
     * 判断是否已同步。
     *
     * @return true 表示已同步
     */
    public static boolean isSynced() {
        return synced;
    }

    /**
     * 获取当前时间偏移量。
     *
     * @return 时间偏移量，单位毫秒
     */
    public static long getTimeOffset() {
        return timeOffset;
    }
}
