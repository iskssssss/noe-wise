package com.skillforge.core.knowledge.dto.query;

import io.swagger.v3.oas.annotations.media.Schema;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.web.bind.annotation.BindParam;

/**
 * 知识库文档分页查询对象。
 *
 * <p>GET /api/v1/knowledge/bases/documents 的查询参数封装，知识库标识通过查询参数传入
 * （kbId 不入查询对象）；缺省值在服务侧按
 * {@code ApiConstants.DEFAULT_PAGE / DEFAULT_PAGE_SIZE} 归一化。
 *
 * @param page     页码，缺省 1
 * @param pageSize 每页条数，缺省 20，最大 200
 * @param status   状态筛选，可空
 * @param titleQuery 当前文档标题字面包含筛选，可空
 */
public record KnowledgeDocumentQuery(
        @Schema(description = "页码，缺省 1") Integer page,
        @BindParam("page_size") @Schema(description = "每页条数，缺省 20，最大 200") Integer pageSize,
        @Schema(description = "状态筛选，可空") String status,
        @CodePointLength(max = 128, message = "标题关键词最多 128 个字符")
        @Schema(description = "当前文档标题关键词，大小写不敏感的字面包含，最多 128 个 Unicode 码点；空白不筛选")
        String titleQuery) {
}
