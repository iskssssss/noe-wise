package com.skillforge.core.billing.export;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.BindParam;

/** GET 查询字段；所有身份过滤均在认证机构或管理目标约束内进一步收窄。时间窗为含起点、不含终点。 */
public record PlatformUsageQuery(
        @BindParam("integration_id") String integrationId,
        @BindParam("deployment_id") Long deploymentId,
        @BindParam("tenant_id") Long tenantId,
        @BindParam("agent_id") Long agentId,
        @BindParam("agent_definition_version") String agentDefinitionVersion,
        @BindParam("end_user_id") Long endUserId,
        @BindParam("session_id") String sessionId,
        @BindParam("run_id") String runId,
        @BindParam("channel_id") String channelId,
        @BindParam("business_scope_id") String businessScopeId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        String cursor, Integer limit,
        @BindParam("group_binding_id") Long groupBindingId) {

    /** 为空查询建立统一默认值，避免控制器各自发明时间范围或游标。 */
    public static PlatformUsageQuery empty() {
        return new PlatformUsageQuery(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
    }
}
