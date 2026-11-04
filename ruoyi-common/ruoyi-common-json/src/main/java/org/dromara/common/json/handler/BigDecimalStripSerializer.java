package org.dromara.common.json.handler;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.annotation.JacksonStdImpl;
import tools.jackson.databind.ser.jdk.NumberSerializer;

import java.math.BigDecimal;

/**
 * BigDecimal 以字符串形式输出，并去除小数末尾无意义的零。
 *
 * 保留所有有效数字，不执行舍入；使用普通十进制表示，避免科学计数法。
 */
@JacksonStdImpl
public class BigDecimalStripSerializer extends NumberSerializer {

    /** 共享序列化器实例。 */
    public static final BigDecimalStripSerializer INSTANCE = new BigDecimalStripSerializer();

    private BigDecimalStripSerializer() {
        super(BigDecimal.class);
    }

    @Override
    public void serialize(Number value, JsonGenerator gen, SerializationContext provider) {
        BigDecimal decimal = (BigDecimal) value;
        gen.writeString(decimal.signum() == 0 ? "0" : decimal.stripTrailingZeros().toPlainString());
    }
}
