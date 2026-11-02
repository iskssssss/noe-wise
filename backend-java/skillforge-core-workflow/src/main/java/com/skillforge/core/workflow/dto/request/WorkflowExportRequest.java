package com.skillforge.core.workflow.dto.request;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流包导出请求 DTO。
 *
 * <p>画布数据是动态结构，本类通过 {@link JsonAnySetter} 将请求体顶层字段
 * 收口到 {@code canvasData}，保持前端既有扁平 JSON 契约不变。
 */
public class WorkflowExportRequest {

    /** 画布数据映射，键为画布字段名。 */
    private final Map<String, Object> canvasData = new LinkedHashMap<>();

    /**
     * 反序列化任意顶层字段到画布数据。
     *
     * @param key   字段名
     * @param value 字段值
     */
    @JsonAnySetter
    public void put(String key, Object value) {
        canvasData.put(key, value);
    }

    /**
     * 获取完整画布数据。
     *
     * @return 画布数据映射
     */
    @JsonAnyGetter
    public Map<String, Object> canvasData() {
        return canvasData;
    }
}
