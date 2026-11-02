package com.skillforge.core.knowledge.dto.response;


/**
 * 上传文档响应 DTO。
 *
 * @param documentId 文档主键
 * @param chunks     分块数量
 * @param sourceVersion 初始不可变来源版本
 */
public record UploadDocumentResponse(
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
        Long documentId,
        int chunks,
        String sourceVersion) {
}
