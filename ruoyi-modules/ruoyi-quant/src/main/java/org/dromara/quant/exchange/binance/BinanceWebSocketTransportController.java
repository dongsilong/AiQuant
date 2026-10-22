package org.dromara.quant.exchange.binance;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Privileged, node-local WebSocket transport control.
 * No transport-switch permission is assigned to regular trading accounts by default.
 * The action interrupts active streams and forces the account/market hubs to reconnect.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/quant/infrastructure/websocket")
public class BinanceWebSocketTransportController {
    private final BinanceGatewayAdapter adapter;

    @SaCheckPermission("quant:transport:view")
    @GetMapping("/transport")
    public R<Map<String, Object>> current() {
        return R.ok(Map.of("transport", adapter.websocketTransport(), "scope", "THIS_NODE"));
    }

    /**
     * 显式切换当前节点的 WebSocket 传输并重建相关订阅。
     */
    @SaCheckPermission("quant:transport:switch")
    @PostMapping("/transport")
    public R<Map<String, Object>> change(@RequestParam String mode, @RequestParam(defaultValue = "false") boolean acknowledgeReconnectionRisk) {
        if (!acknowledgeReconnectionRisk) {
            throw new IllegalArgumentException("切换 WebSocket 会触发账户和行情流重订阅，必须显式确认");
        }
        int restarted = adapter.switchWebsocketTransport(mode);
        return R.ok(Map.of("transport", adapter.websocketTransport(), "scope", "THIS_NODE", "streamsReconnected", restarted));
    }
}
