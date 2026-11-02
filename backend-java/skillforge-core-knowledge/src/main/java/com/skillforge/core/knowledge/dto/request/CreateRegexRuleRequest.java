package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * 正则抽取规则（设计 §4.4）。
 *
 * <p>本轮仅定义存储，不执行抽取。rules 结构见设计 §4.4 示例。
 *
 * @param name  规则名称（regex 类型内唯一）
 * @param rules 正则规则数组（name/target_entity/target_property/pattern/confidence/description）
 */
public record CreateRegexRuleRequest(
        @Schema(description = "规则名称（regex 类型内唯一）")
        @NotBlank(message = "规则名称不能为空")
        String name,
        @Schema(description = "正则规则数组（name/target_entity/target_property/pattern/confidence/description）")
        List<Map<String, Object>> rules) {
}
