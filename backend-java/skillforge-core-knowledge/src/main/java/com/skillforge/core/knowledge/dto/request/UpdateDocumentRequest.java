package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 更新文档请求 DTO。
 *
 * <p>字段可空，空值表示不修改；正文实际变化时才按知识库参数重新分块并重算变化分块的向量。
 * 仅标题变化时，省略正文或回送相同正文均复用原文与分块集合。
 *
 * @param title   文档标题
 * @param content 文档正文，省略或与当前正文相同均保留原分块
 * @param expectedSourceVersion 客户端读取到的当前版本，必填并用于 CAS
 */
public record UpdateDocumentRequest(
        @Schema(description = "文档标题，空值不修改") String title,
        @Schema(description = "文档正文，省略或与当前正文相同均保留原分块；实际变化才重新分块") String content,
        @JsonProperty("expected_source_version") @NotBlank
        @Schema(description = "客户端读取到的当前 source_version，必填并用于 CAS")
        String expectedSourceVersion) {
}
