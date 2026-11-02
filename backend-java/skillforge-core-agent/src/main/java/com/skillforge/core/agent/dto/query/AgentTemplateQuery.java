package com.skillforge.core.agent.dto.query;

import io.swagger.v3.oas.annotations.media.Schema;
import com.skillforge.core.agent.model.AgentDistributionPolicy;
import org.springframework.web.bind.annotation.BindParam;

/**
 * 平台及机构自有共享定义的分页查询对象。
 *
 * <p>GET /api/v1/platform/agent-definitions 与 /api/v1/tenant/agent-definitions 的查询参数封装；
 * 缺省值在共享服务侧按 {@code ApiConstants.DEFAULT_PAGE / DEFAULT_PAGE_SIZE} 归一化。
 *
 * @param page     页码，缺省 1
 * @param limit    每页条数，缺省 20，最大 200
 * @param status   状态筛选（draft / published / deprecated / archived），可空
 * @param category 分类筛选，可空
 * @param name     名称包含筛选，可空；在分页前执行
 * @param distributionPolicy 分发策略，可空；在分页前执行
 */
public record AgentTemplateQuery(
        @Schema(description = "页码，缺省 1") Integer page,
        @Schema(description = "每页条数，缺省 20，最大 200") Integer limit,
        @Schema(description = "状态筛选：draft / published / deprecated / archived，可空") String status,
        @Schema(description = "分类筛选，可空") String category,
        @Schema(description = "名称包含筛选，可空，去除首尾空白") String name,
        @BindParam("distribution_policy")
        @Schema(description = "分发策略筛选，可空") AgentDistributionPolicy distributionPolicy) {
}
