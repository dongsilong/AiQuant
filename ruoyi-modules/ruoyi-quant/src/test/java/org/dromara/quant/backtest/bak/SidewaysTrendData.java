package org.dromara.quant.backtest.bak;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;

/**
 * 震荡趋势数据类
 */
@Data
public class SidewaysTrendData {
    @ExcelProperty(value = "序号")
    private Integer index;

    @ExcelProperty(value = "开始时间")
    private String startOpenTime;

    @ExcelProperty(value = "结束时间")
    private String endOpenTime;

    @ExcelProperty(value = "起始点位")
    private Double startPrice;

    @ExcelProperty(value = "最高点位")
    private Double highestPrice;

    @ExcelProperty(value = "最低点位")
    private Double lowestPrice;

    @ExcelProperty(value = "振幅%")
    private Double priceChangePercent;

    @ExcelProperty(value = "持续周数")
    private Integer barCount;
}
