package com.im.common.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.im.common.ProtocolException;

import java.nio.charset.StandardCharsets;

/**
 * 统一 JSON 序列化工具。
 *
 * <p>只暴露必要的两个方向：对象 → JSON 字符串 / 字节，JSON → 对象。
 * 反序列化失败统一转换为 {@link ProtocolException}（{@code INVALID_PAYLOAD}），
 * 避免上层到处捕获 Jackson 的受检异常。</p>
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private JsonUtil() {
    }

    /** 对象转 JSON 字符串。 */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("序列化失败: " + value.getClass().getName(), e);
        }
    }

    /** 对象转 UTF-8 JSON 字节。 */
    public static byte[] toJsonBytes(Object value) {
        return toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    /** JSON 字符串反序列化。 */
    public static <T> T fromJson(String json, Class<T> type) throws ProtocolException {
        return fromJson(json.getBytes(StandardCharsets.UTF_8), type);
    }

    /** UTF-8 JSON 字节反序列化。 */
    public static <T> T fromJson(byte[] json, Class<T> type) throws ProtocolException {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new ProtocolException(com.im.common.ErrorCode.INVALID_PAYLOAD,
                    "反序列化 " + type.getSimpleName() + " 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从负载中提取 {@code requestId}，用于请求-响应配对。
     *
     * @param json UTF-8 JSON 负载
     * @return requestId；缺失或非法时返回空字符串
     */
    public static String requestId(byte[] json) {
        if (json == null || json.length == 0) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(json);
            com.fasterxml.jackson.databind.JsonNode id = node.get("requestId");
            return id == null || id.isNull() ? "" : id.asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
