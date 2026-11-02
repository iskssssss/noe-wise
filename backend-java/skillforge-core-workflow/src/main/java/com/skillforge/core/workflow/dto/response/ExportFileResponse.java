package com.skillforge.core.workflow.dto.response;

/**
 * 导出文件项响应 DTO。
 *
 * @param name    文件名
 * @param content 文件内容
 */
public record ExportFileResponse(
        String name,
        String content) {
}
