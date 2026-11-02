package com.skillforge.platform.dto.capability;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** 平台创建公共 Draft Version 请求；发布后版本正文不可变。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateCapabilityVersionRequest(
        @NotBlank @Size(max = 128) String versionKey,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 4000) String description,
        @NotBlank @Size(max = 128) String contentHash,
        @NotNull Map<String, Object> descriptor) {
}
