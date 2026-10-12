package org.dromara.quant;

import java.math.BigDecimal;

public class MathTest {
    public static void main(String[] args) {
        BigDecimal open = new BigDecimal("1");
        BigDecimal close = new BigDecimal("1");
        BigDecimal value = close.subtract(open).multiply(open);
        value = BigDecimal.ONE;
        System.out.println(value);
        double atan = Math.atan(value.doubleValue());
        double degrees = Math.toDegrees(atan);
        System.out.println(degrees);
    }
}
