package org.dromara.config.strategy.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.strategy.StrategyPlugin;
import org.dromara.quant.config.strategy.context.StrategyContext;
import org.dromara.quant.config.strategy.metadata.StrategyDescriptor;
import org.dromara.quant.config.strategy.signal.EntrySignal;
import org.dromara.quant.config.strategy.signal.ExitSignal;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public final class KlineSlopeRateStrategy implements StrategyPlugin {

    /**
     * 策略插件唯一编码。
     */
    public static final String CODE = "kline_slope_rate";

    /**
     * 策略插件版本。
     */
    private static final String VERSION = "1.0";


    @Override
    public StrategyDescriptor descriptor() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("entryAngleDegrees", 60.0d);
        example.put("firstProfitTakingRate", 0.10d);
        example.put("firstClosePositionRate", 0.50d);
        example.put("stepRatio", 0.10d);
        example.put("localStopLossRate", 0.05d);
        return new StrategyDescriptor(CODE, VERSION, "实时K线斜率阶梯止盈", example);

    }

    @Override
    public void validateConfig(Map<String, Object> params) {

    }

    @Override
    public Optional<EntrySignal> evaluateEntry(StrategyContext context) {
        System.out.println("+++++  evaluateEntry:");
        return Optional.empty();
    }

    @Override
    public List<ExitSignal> evaluateExit(StrategyContext context) {
        System.out.println("----- evaluateExit:");
        return List.of();
    }
}
