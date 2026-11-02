package com.skillforge.core.shared.dto.resource;

/**
 * 通用删除/操作结果响应 DTO。
 *
 * @param status 操作状态
 */
public record DeleteResponse(
        String status) {
}
