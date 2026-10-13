package org.dromara.quant.quant.backtest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KlineData {
    private Long openTime;
//    private String openTime;
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Double volume;
    private Long closeTime;
    private Double quoteAssetVolume;
    private Integer numberOfTrades;
    private Double takerBuyBaseAssetVolume;
    private Double takerBuyQuoteAssetVolume;

    public Long getOpenTime() {
        return openTime;
    }

    public Long getCloseTime() {
        return closeTime;
    }

    public String getOpen() {
        return open != null ? String.valueOf(open) : "";
    }

    public String getHigh() {
        return high != null ? String.valueOf(high) : "";
    }

    public String getLow() {
        return low != null ? String.valueOf(low) : "";
    }

    public String getClose() {
        return close != null ? String.valueOf(close) : "";
    }

    public String getVolume() {
        return volume != null ? String.valueOf(volume) : "";
    }
}
