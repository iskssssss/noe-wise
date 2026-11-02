package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 更新文档请求 DTO。
 *
 * <p>字段可空，空值表示不修改；content 有值时按知识库分段参数重新分块并重算向量。
 *
 * @param title   文档标题
 * @param content 文档正文（传入即创建完整新版本）
 * @param expectedSourceVersion 客户端读取到的当前版本，必填并用于 CAS
 */
public record UpdateDocumentRequest(
        @Schema(description = "文档标题，空值不修改") String title,
        @Schema(description = "文档正文，传入即创建完整新版本") String content,
        @JsonProperty("expected_source_version") @NotBlank
        @Schema(description = "客户端读取到的当前 source_version，必填并用于 CAS")
        String expectedSourceVersion) {
}
