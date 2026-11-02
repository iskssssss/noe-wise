package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * LLM 抽取 Prompt 规则（设计 §4.3）。
 *
 * <p>本轮仅定义存储，不执行抽取。模板变量与输出映射见设计 §4.3 示例。
 *
 * @param name           规则名称（prompt 类型内唯一）
 * @param entityTypes    抽取目标实体类型列表
 * @param promptTemplate Prompt 模板全文（含 {document_text} 等变量）
 * @param outputMappings 输出字段 → 实体属性映射数组（source/target_entity/target_property/extra）
 * @param confidenceRules 置信度规则数组（condition/confidence）
 */
public record CreatePromptRuleRequest(
        @Schema(description = "规则名称（prompt 类型内唯一）")
        @NotBlank(message = "规则名称不能为空")
        String name,
        @Schema(description = "抽取目标实体类型列表，可空")
        List<String> entityTypes,
        @Schema(description = "Prompt 模板全文（含 {document_text} 等变量）")
        @NotBlank(message = "Prompt 模板不能为空")
        String promptTemplate,
        @Schema(description = "输出字段 → 实体属性映射数组（source/target_entity/target_property/extra），可空")
        List<Map<String, Object>> outputMappings,
        @Schema(description = "置信度规则数组（condition/confidence），可空")
        List<Map<String, Object>> confidenceRules) {
}
