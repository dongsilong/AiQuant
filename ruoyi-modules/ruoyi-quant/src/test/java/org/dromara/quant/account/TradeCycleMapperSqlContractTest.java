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
    void closeAccountBalancesAreResolvedFromAllCloseFillsInTheCycle() throws IOException {
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

        assertTrue(sql.contains("close_fill.cycle_id=c.id"));
        assertTrue(sql.contains("close_fill.fill_role='CLOSE'"));
        assertTrue(sql.contains("snapshot.closing_equity IS NOT NULL"));
        assertTrue(sql.contains("snapshot.wallet_balance IS NOT NULL"));
        assertTrue(sql.contains("ORDER BY snapshot.captured_at DESC"));
        assertFalse(sql.contains("last_close_fill"), "a missing snapshot on the final fill must not hide earlier cycle snapshots");
    }
}
