package org.dromara.quant.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

@Tag("dev")
class TradePageSliceTest {

    @Test
    void returnsRequestedPageAndOriginalTotal() {
        TradePageSlice<Integer> page = TradePageSlice.of(List.of(1, 2, 3, 4, 5), 2, 2);

        assertEquals(List.of(3, 4), page.rows());
        assertEquals(5, page.total());
    }
}
