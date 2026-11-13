package com.skillforge.core.knowledge.dto.knowledge;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 文档重新索引响应 DTO。
 *
 * @param documentId 文档主键
 * @param chunks     参与重算的分块数
 * @param sourceVersion 本次提交的新来源版本
 */
public record ReindexResponse(
        @Schema(description = "文档主键（雪花 ID）") Long documentId,
        @Schema(description = "参与重算的分块数") int chunks,
        @Schema(description = "本次提交的新来源版本") String sourceVersion) {
}
