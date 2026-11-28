package org.dromara.quant.runtime.service;

import org.dromara.quant.runtime.domain.QuantStrategyHedgeRound;
import org.dromara.quant.runtime.mapper.QuantStrategyHedgeRoundMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class StrategyHedgeRoundServiceTest {
    private final QuantStrategyHedgeRoundMapper mapper = mock(QuantStrategyHedgeRoundMapper.class);
    private final StrategyHedgeRoundService service = new StrategyHedgeRoundService(mapper);

    @Test
    void closesRoundOnlyAfterBothHedgeLegsAreConfirmedClosed() {
        QuantStrategyHedgeRound round = round();
        round.setId(11L);
        round.setVersion(0);
        round.setHedgeRoundKey("round-1");
        round.setStatus(StrategyHedgeRoundService.STATUS_OPEN);
        round.setLongLegStatus(StrategyHedgeRoundService.LEG_OPEN);
        round.setShortLegStatus(StrategyHedgeRoundService.LEG_OPEN);
        when(mapper.selectOne(any())).thenReturn(round);
        when(mapper.updateById(any(QuantStrategyHedgeRound.class))).thenReturn(1);

        service.markLegClosed("round-1", "LONG");

        assertEquals(StrategyHedgeRoundService.LEG_CLOSED, round.getLongLegStatus());
        assertEquals(StrategyHedgeRoundService.LEG_OPEN, round.getShortLegStatus());
        assertEquals(StrategyHedgeRoundService.STATUS_CLOSING, round.getStatus());

        service.markLegClosed("round-1", "SHORT");

        assertEquals(StrategyHedgeRoundService.LEG_CLOSED, round.getLongLegStatus());
        assertEquals(StrategyHedgeRoundService.LEG_CLOSED, round.getShortLegStatus());
        assertEquals(StrategyHedgeRoundService.STATUS_CLOSED, round.getStatus());
        verify(mapper, times(2)).updateById(round);
    }

    @Test
    void claimCreatesOneRoundWithIndependentLegStates() {
        when(mapper.insert(any(QuantStrategyHedgeRound.class))).thenReturn(1);
        QuantStrategyHedgeRound round = round();

        assertTrue(service.claim(round));
        assertNotNull(round.getHedgeRoundKey());
        assertEquals("BTCUSDT", round.getSymbol());
        assertEquals(StrategyHedgeRoundService.STATUS_OPENING, round.getStatus());
        assertEquals(StrategyHedgeRoundService.LEG_NOT_STARTED, round.getLongLegStatus());
        assertEquals(StrategyHedgeRoundService.LEG_NOT_STARTED, round.getShortLegStatus());
        assertEquals(0, round.getVersion());
    }

    @Test
    void duplicateKlineOrActiveRoundDoesNotClaimAgain() {
        when(mapper.insert(any(QuantStrategyHedgeRound.class))).thenThrow(new DuplicateKeyException("duplicate"));

        assertFalse(service.claim(round()));
    }

    @Test
    void claimRejectsMissingOrNonPositiveEquitySnapshot() {
        QuantStrategyHedgeRound round = round();
        round.setEntryEquitySnapshot(BigDecimal.ZERO);

        assertThrows(IllegalArgumentException.class, () -> service.claim(round));
        verify(mapper, never()).insert(any(QuantStrategyHedgeRound.class));
    }

    @Test
    void signalReadyRoundRequiresReferencePriceAndOriginalEventTime() {
        QuantStrategyHedgeRound round = round();
        round.setStatus(StrategyHedgeRoundService.STATUS_SIGNAL_READY);

        assertThrows(IllegalArgumentException.class, () -> service.claim(round));
        verify(mapper, never()).insert(any(QuantStrategyHedgeRound.class));

        round.setReferencePrice(new BigDecimal("100"));
        round.setSignalEventTime(LocalDateTime.of(2026, 11, 25, 15, 0, 1));
        when(mapper.insert(any(QuantStrategyHedgeRound.class))).thenReturn(1);

        assertTrue(service.claim(round));
    }

    @Test
    void rejectedOptimisticUpdateRestoresVersion() {
        QuantStrategyHedgeRound round = round();
        round.setId(9L);
        round.setVersion(4);
        when(mapper.updateById(any(QuantStrategyHedgeRound.class))).thenAnswer(call -> {
            round.setVersion(5);
            return 0;
        });

        assertThrows(IllegalStateException.class, () -> service.save(round));
        assertEquals(4, round.getVersion());
    }

    private QuantStrategyHedgeRound round() {
        QuantStrategyHedgeRound round = new QuantStrategyHedgeRound();
        round.setStrategyId(1L);
        round.setInstanceId(2L);
        round.setCredentialId(3L);
        round.setSymbol(" btcusdt ");
        round.setEntryKlineOpenTime(LocalDateTime.of(2026, 11, 25, 15, 0));
        round.setEntryEquitySnapshot(new BigDecimal("10000"));
        round.setMarginRatio(new BigDecimal("0.10"));
        round.setLeverage(10);
        return round;
    }
}
