package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 文档详情响应 DTO。
 *
 * @param id               文档主键
 * @param kbId             所属知识库 ID
 * @param title            文档标题
 * @param sourceType       来源类型：file / projection
 * @param status           处理状态：ready / processing / failed
 * @param sourceVersion    摄取版本，用于知识库 Revision ready/publish
 * @param content          文档正文（供编辑回显）
 * @param originalFilename 原始文件名（文件上传来源），可空
 * @param extension        文件扩展名（txt / md / pdf / docx），可空
 * @param sizeBytes        文件大小（字节），可空
 * @param parsedBy         解析方式：direct 直读 / tika 解析，可空
 * @param createdAt        创建时间
 */
public record DocumentDetailResponse(
        @Schema(description = "文档主键（雪花 ID）") Long id,
        @Schema(description = "所属知识库 ID") Long kbId,
        @Schema(description = "文档标题") String title,
        @Schema(description = "来源类型：file 文件导入 / projection 机构知识投影") String sourceType,
        @Schema(description = "处理状态：ready 已就绪 / processing 处理中 / failed 处理失败") String status,
        @Schema(description = "摄取版本，用于知识库 Revision ready/publish") String sourceVersion,
        @Schema(description = "文档正文，供编辑回显") String content,
        @Schema(description = "原始文件名（文件上传来源），可空") String originalFilename,
        @Schema(description = "文件扩展名（txt / md / pdf / docx），可空") String extension,
        @Schema(description = "文件大小（字节），可空") Long sizeBytes,
        @Schema(description = "解析方式：direct 直读 / tika 解析，可空") String parsedBy,
        @Schema(description = "创建时间") Instant createdAt) {
}
