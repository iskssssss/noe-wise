package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * 结构化映射规则（设计 §4.2）。
 *
 * <p>本轮仅定义存储，不执行抽取。rules/relationRules 的结构见设计 §4.2 示例。
 *
 * @param name          规则名称（mapping 类型内唯一）
 * @param sourceType    数据源类型：json | xml | csv | db
 * @param rules         实体映射规则数组（entity_type/mappings）
 * @param relationRules 关系映射规则数组（relation_type/source_extraction/target_extraction/property_mappings）
 */
public record CreateMappingRuleRequest(
        @Schema(description = "规则名称（mapping 类型内唯一）")
        @NotBlank(message = "规则名称不能为空")
        String name,
        @Schema(description = "数据源类型：json | xml | csv | db")
        @NotBlank(message = "数据源类型不能为空")
        String sourceType,
        @Schema(description = "实体映射规则数组（entity_type/mappings），可空")
        List<Map<String, Object>> rules,
        @Schema(description = "关系映射规则数组（relation_type/source_extraction/target_extraction/property_mappings），可空")
        List<Map<String, Object>> relationRules) {
}
