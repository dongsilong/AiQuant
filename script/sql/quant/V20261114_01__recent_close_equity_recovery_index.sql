-- Keep the short-window close-snapshot recovery scan index-backed.
CREATE INDEX idx_quant_trade_cycle_recent_closed
    ON quant_trade_cycle (status, closed_at, id);
