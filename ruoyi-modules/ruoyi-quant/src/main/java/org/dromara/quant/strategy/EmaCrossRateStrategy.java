//package org.dromara.quant.strategy;
//
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Component;
//
//import java.util.LinkedHashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//
//@Slf4j
//@Component
//public final class EmaCrossRateStrategy implements StrategyPlugin {
//    /**
//     * 策略插件唯一编码。
//     */
//    public static final String CODE = "ema_cross_rate";
//    /**
//     * 策略插件版本。
//     */
//    private static final String VERSION = "1.0";
//
//    @Override
//    public StrategyDescriptor descriptor() {
//        Map<String, Object> example = new LinkedHashMap<>();
//        example.put("entryAngleDegrees", 60);
//        example.put("firstProfitTakingRate", 0.10);
//        example.put("firstClosePositionRate", 0.50);
//        example.put("stepRatio", 0.10);
//        example.put("localStopLossRate", 0.05);
//        return new StrategyDescriptor(CODE, VERSION, "实时K线斜率阶梯止盈-V2", example);
//    }
//
//    @Override
//    public void validateConfig(Map<String, Object> params) {
//        Object entryAngleDegrees = params.get("entryAngleDegrees");
//
//    }
//
//    @Override
//    public Optional<EntrySignal> evaluateEntry(StrategyContext context) {
//        return Optional.empty();
//    }
//
//    @Override
//    public List<ExitSignal> evaluateExit(StrategyContext context) {
//        return List.of();
//    }
//}
