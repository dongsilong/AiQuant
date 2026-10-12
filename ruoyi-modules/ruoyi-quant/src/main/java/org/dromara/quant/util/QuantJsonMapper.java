package org.dromara.quant.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;

/**
 * 量化模块内部 JSON 工具。
 * <p>ObjectMapper 创建完成后只读共享，避免依赖 Spring 容器中的 ObjectMapper Bean。
 */
public final class QuantJsonMapper {

    /**
     * 量化模块共享 JSON 序列化器。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private QuantJsonMapper() {
    }

    /**
     * 将 JSON 对象转换为参数 Map。
     *
     * @param json JSON 字符串
     * @return 参数 Map；空 JSON 返回空 Map
     */
    public static Map<String, Object> readMap(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    /**
     * 将参数 Map 序列化为 JSON。
     *
     * @param params 参数 Map
     * @return JSON 字符串
     */
    public static String writeMap(Map<String, Object> params) throws Exception {
        return MAPPER.writeValueAsString(params == null ? Collections.emptyMap() : params);
    }
}
