package org.dromara.quant.quant.backtest.bak;

public enum TrendType {
    STRONG_BULL("强多头"),
    WEAK_BULL("偏多头"),
    SIDEWAYS("震荡"),
    WEAK_BEAR("偏空头"),
    STRONG_BEAR("强空头");

    private final String displayName;

    TrendType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
