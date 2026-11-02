package com.skillforge.core.billing.metering;

import com.skillforge.core.shared.tracing.TraceContext;

/**
 * 单次计量调用的关联维度上下文（显式列投影）。
 *
 * <p>由调用点经 {@link MeterScope#open} 提供；缺失维度留 null（不臆造）。
 * {@code eventId} 为业务幂等 identifier（重试 lambda 外生成防双计）；
 * 调用点未提供时由 {@link CostRecorder} 按 requestId+callPoint 兜底生成。
 */
public record CostContext(
        String eventId,
        String requestId,
        String rootInputId,
        String sessionId,
        Long endUserId,
        String channelId,
        Long agentId,
        String runId,
        String attributionRunId,
        Long workflowInstanceId,
        String executionId,
        String scopeId,
        String scopeType,
        String callPoint,
        TraceContext traceContext) {

    /**
     * 构建器（null 字段不写入，避免 Builder 样板）。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 与父上下文合并：子上下文非空字段优先，空字段继承父（requestId 等链路字段透传）。
     *
     * @param parent 父上下文（嵌套 scope），可空
     * @param child  子上下文，非空
     * @return 合并后的上下文
     */
    static CostContext merge(CostContext parent, CostContext child) {
        if (parent == null) {
            return child;
        }
        if (parent.endUserId() != null && child.endUserId() != null
                && !parent.endUserId().equals(child.endUserId())) {
            throw new IllegalArgumentException(
                    "nested cost context cannot change entry end_user_id");
        }
        return new CostContext(
                child.eventId() != null ? child.eventId() : parent.eventId(),
                child.requestId() != null ? child.requestId() : parent.requestId(),
                child.rootInputId() != null ? child.rootInputId() : parent.rootInputId(),
                child.sessionId() != null ? child.sessionId() : parent.sessionId(),
                child.endUserId() != null ? child.endUserId() : parent.endUserId(),
                child.channelId() != null ? child.channelId() : parent.channelId(),
                child.agentId() != null ? child.agentId() : parent.agentId(),
                child.runId() != null ? child.runId() : parent.runId(),
                child.attributionRunId() != null ? child.attributionRunId()
                        : child.runId() != null && !java.util.Objects.equals(child.runId(), parent.runId())
                            ? null : parent.attributionRunId(),
                child.workflowInstanceId() != null ? child.workflowInstanceId() : parent.workflowInstanceId(),
                child.executionId() != null ? child.executionId() : parent.executionId(),
                child.scopeId() != null ? child.scopeId() : parent.scopeId(),
                child.scopeType() != null ? child.scopeType() : parent.scopeType(),
                child.callPoint() != null ? child.callPoint() : parent.callPoint(),
                child.traceContext() != null ? child.traceContext() : parent.traceContext());
    }

    /** 上下文构建器。 */
    public static class Builder {

        /** 事件 ID。 */
        private String eventId;

        /** 请求根 ID。 */
        private String requestId;

        /** 权威根 Input ID。 */
        private String rootInputId;

        /** 会话 ID。 */
        private String sessionId;

        /** C 端用户 ID。 */
        private Long endUserId;

        /** 渠道 ID。 */
        private String channelId;

        /** Agent ID。 */
        private Long agentId;

        /** Agent Runtime Run ID。 */
        private String runId;

        /** 可信宿主已核验的冻结资源来源；实际效果 Run 仍由 runId 记录。 */
        private String attributionRunId;

        /** 工作流实例 ID。 */
        private Long workflowInstanceId;

        /** Runtime Execution ID。 */
        private String executionId;

        /** 三方透传实体标识。 */
        private String scopeId;

        /** 三方透传类型。 */
        private String scopeType;

        /** 调用点枚举取值。 */
        private String callPoint;

        /** 持久输入因果上下文。 */
        private TraceContext traceContext;

        /** 事件 ID。 */
        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        /** 请求根 ID。 */
        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        /** 权威根 Input ID。 */
        public Builder rootInputId(String rootInputId) {
            this.rootInputId = rootInputId;
            return this;
        }

        /** 会话 ID。 */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** C 端用户 ID。 */
        public Builder endUserId(Long endUserId) {
            this.endUserId = endUserId;
            return this;
        }

        /** 渠道 ID。 */
        public Builder channelId(String channelId) {
            this.channelId = channelId;
            return this;
        }

        /** Agent ID。 */
        public Builder agentId(Long agentId) {
            this.agentId = agentId;
            return this;
        }

        /** Agent Runtime Run ID。 */
        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder attributionRunId(String attributionRunId) {
            this.attributionRunId = attributionRunId;
            return this;
        }

        /** 工作流实例 ID。 */
        public Builder workflowInstanceId(Long workflowInstanceId) {
            this.workflowInstanceId = workflowInstanceId;
            return this;
        }

        /** Runtime Execution ID。 */
        public Builder executionId(String executionId) {
            this.executionId = executionId;
            return this;
        }

        /** 三方透传实体标识。 */
        public Builder scopeId(String scopeId) {
            this.scopeId = scopeId;
            return this;
        }

        /** 三方透传类型。 */
        public Builder scopeType(String scopeType) {
            this.scopeType = scopeType;
            return this;
        }

        /** 调用点枚举取值。 */
        public Builder callPoint(String callPoint) {
            this.callPoint = callPoint;
            return this;
        }

        /** 持久输入因果上下文。 */
        public Builder traceContext(TraceContext traceContext) {
            this.traceContext = traceContext;
            return this;
        }

        /** 构建上下文。 */
        public CostContext build() {
            if (endUserId != null && endUserId <= 0) {
                throw new IllegalArgumentException("endUserId must be positive when supplied");
            }
            String resolvedRootInputId = rootInputId != null
                    ? rootInputId : traceContext == null ? null : traceContext.rootInputId();
            return new CostContext(eventId, requestId, resolvedRootInputId, sessionId, endUserId, channelId,
                    agentId, runId, attributionRunId, workflowInstanceId, executionId,
                    scopeId, scopeType, callPoint, traceContext);
        }
    }
}
