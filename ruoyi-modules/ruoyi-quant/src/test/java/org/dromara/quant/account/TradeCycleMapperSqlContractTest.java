package org.dromara.quant.account;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class TradeCycleMapperSqlContractTest {

    @Test
    void closeAccountBalancesUseOnlySnapshotForFinalCloseOrder() throws IOException {
        String mapperXml;
        try (InputStream input = getClass().getResourceAsStream("/mapper/quant/TradeCycleMapper.xml")) {
            if (input == null) {
                throw new IllegalStateException("TradeCycleMapper.xml is not on the test classpath");
            }
            mapperXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int selectStart = mapperXml.indexOf("<select id=\"selectCycles\"");
        int selectEnd = mapperXml.indexOf("</select>", selectStart);
        assertTrue(selectStart >= 0 && selectEnd > selectStart, "selectCycles SQL must exist");
        String sql = mapperXml.substring(selectStart, selectEnd);

        assertTrue(sql.contains("LEFT JOIN quant_trade_cycle_fill last_close_fill"));
        assertTrue(sql.contains("last_close_fill.id=("));
        assertTrue(sql.contains("WHERE f.cycle_id=c.id"));
        assertTrue(sql.contains("AND f.fill_role='CLOSE'"));
        assertTrue(sql.contains("ORDER BY f.trade_time DESC,f.id DESC"));
        assertTrue(sql.contains("LEFT JOIN quant_trade_close_equity ce"));
        assertTrue(sql.contains("ce.exchange_order_id=last_close_fill.exchange_order_id"));

        assertTrue(sql.contains("ce.closing_equity AS closingEquity"));
        assertTrue(sql.contains("ce.wallet_balance AS walletBalance"));
        assertTrue(sql.contains("ce.unrealized_pnl AS unrealizedPnl"));
        assertTrue(sql.contains("DATE_FORMAT(ce.captured_at"));
        assertTrue(sql.contains("END AS closingEquityCapturedAt"));
        assertTrue(sql.contains("ce.capture_source AS closingEquityCaptureSource"));
        assertTrue(sql.contains("DATE_FORMAT(ce.close_event_time"));
        assertTrue(sql.contains("DATE_FORMAT(ce.account_update_transaction_time"));

        assertFalse(sql.contains("close_fill.cycle_id=c.id"),
            "balance fields must not fall back to snapshots from earlier close orders");
        assertFalse(sql.contains("ORDER BY snapshot.captured_at DESC"),
            "balance fields must come from one final-order snapshot, not independently selected snapshots");
    }
}
