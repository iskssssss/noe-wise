package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 更新分段请求 DTO。
 *
 * @param content 分段新内容，必填；保存后创建完整新版本
 * @param expectedSourceVersion 客户端读取到的当前版本，必填并用于 CAS
 */
public record UpdateChunkRequest(
        @NotBlank
        @Schema(description = "分段新内容，必填；保存后创建完整新版本") String content,
        @JsonProperty("expected_source_version") @NotBlank
        @Schema(description = "客户端读取到的当前 source_version，必填并用于 CAS")
        String expectedSourceVersion) {
}
