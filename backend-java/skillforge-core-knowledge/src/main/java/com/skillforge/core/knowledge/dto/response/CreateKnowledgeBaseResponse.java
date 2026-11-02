package com.skillforge.core.knowledge.dto.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 创建知识库响应 DTO。
 *
 * @param id   知识库主键
 * @param name 知识库名称
 */
public record CreateKnowledgeBaseResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String name) {
}
