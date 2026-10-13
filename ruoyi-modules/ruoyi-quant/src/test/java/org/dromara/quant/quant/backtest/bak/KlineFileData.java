package org.dromara.quant.quant.backtest.bak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.dromara.quant.quant.backtest.KlineData;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KlineFileData {
    private String symbol;
    private String interval;
    private Integer year;
    private Integer data_count;
    private List<KlineData> data;
}
