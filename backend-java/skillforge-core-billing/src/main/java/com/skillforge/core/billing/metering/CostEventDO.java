package com.skillforge.core.billing.metering;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import com.skillforge.core.billing.export.PlatformUsageGrantHop;
import lombok.Data;

/**
 * 用量事件实体，对应数据库表 {@code usage_events}。
 *
 * <p>「最小单元」原子事件：每次 AI 调用、知识源操作或 Workflow 外部动作
 * 与每条用户请求（request）各一条；只 INSERT 不可变（RLS 仅开放 INSERT+SELECT），
 * token/金额/次数统计全部基于本表聚合。详见数据模型设计「计费与审计」目标设计（N28）。
 */
@Data
@TableName(value = "nw_usage_events", autoResultMap = true)
public class CostEventDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属租户 ID（RLS 隔离维度）。 */
    private Long tenantId;

    /** 可信入口种类：external、native 或 system。 */
    private String originKind;
    /** 已认证并持久保存的发起主体；系统任务使用真实任务责任引用。 */
    private String actorRef;
    /** 费用与业务责任主体；不从模型上下文补造。 */
    private String responsibilityRef;

    /** 机构中的执行接入身份，不从模型或用户输入推断。 */
    private String integrationId;

    /** 受信业务范围绑定身份，例如机构内门店。 */
    private String businessScopeId;

    /** 本 Run 冻结的 Agent 定义版本，恢复与对账时不读取当前版本。 */
    private String agentDefinitionVersion;

    /** 原定义所有者；非 Agent 来源为空，机构自有 Agent 为 tenant。 */
    private String sourceOwnerKind;
    /** 原定义所属机构；platform 或非 Agent 来源为空。 */
    private Long sourceOwnerTenantId;
    /** 原共享定义稳定 ID；机构自有 Agent 不制造共享定义 ID。 */
    private Long agentDefinitionId;
    /** 实际执行实例冻结的发布记录和修订。 */
    private Long agentRevisionId;
    private Integer agentRevision;

    /** Run冻结的受控部署身份；自有Agent和非Agent来源为空。 */
    private Long deploymentId;
    /** 原 Run 冻结的部署及末端授权修订，非部署来源为空。 */
    private Long deploymentRevision;
    private Long grantId;
    private Long grantRevision;
    /** 子表保存的有序冻结授权链，与用量正文共享事务和保留周期。 */
    @TableField(exist = false)
    private List<PlatformUsageGrantHop> grantChain = List.of();

    /** 群身份来自实际 Run target 或已冻结后台任务，普通个人调用为空。 */
    private Long groupBindingId;
    /** 原请求者与系统实际执行主体分开保存，格式由持久来源契约定义。 */
    private String requesterKind;
    private String requesterRef;
    /** 后台任务原始输入来源，不能被技术模型会话覆盖。 */
    private String originSessionId;
    private String originEventId;
    /** 后台任务冻结的业务责任实例，实际模型执行者仍由 agentId 表示。 */
    private Long responsibilityAgentId;
    /** 系统用途调用的不可变逻辑 Run，配置与逻辑调用引用由其精确解析。 */
    private Long systemAgentRunId;
    private String systemAgentConfigurationRef;
    private String systemAgentInvocationRef;
    /** 事件类型：request / llm.chat / llm.embedding / llm.rerank / knowledge.source / workflow.external。 */
    private String eventType;

    /** AI 服务商标识。 */
    private String provider;

    /** 实际模型名。 */
    private String model;

    /** 事件时间（调用发生时刻，账单/统计口径）。 */
    private Instant occurredAt;

    /** 落库时间（处理时间，对账口径）。 */
    private Instant ingestedAt;

    /** 计量单位数。 */
    private Integer quantity;
    /** 原媒体操作冻结的平台模型版本；其他事件为空，不按当前模型追溯。 */
    private Long pricingModelId;
    private Long pricingModelRevision;
    /** 供应商报告的原始媒体计量，NULL 表示尚未报告。 */
    private Long imageCount;
    private Long videoCount;
    private Long characters;
    private BigDecimal durationSeconds;
    /** quantity 的真实单位：request、call 或 document；token 数量在专用列。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String measurementUnit;

    /** llm 类总 token。 */
    private Long tokens;

    /** llm.chat 输入 token。 */
    private Long promptTokens;

    /** llm.chat 输出 token。 */
    private Long completionTokens;

    /** 输入 token 中命中供应商缓存的子集。 */
    private Long cachedInputTokens;

    /** 输入 token 中写入供应商缓存的子集。 */
    private Long cacheWriteInputTokens;

    /** 输出 token 中模型推理的子集。 */
    private Long reasoningOutputTokens;

    /** token 计量来源：PROVIDER_REPORTED / ESTIMATED / UNKNOWN。 */
    private String usageSource;

    /** 同一 attempt 的供应商计量报告修订号，金额事件不使用。 */
    private Long reportRevision;

    /** measured 完整模型终态快照消费的真实报告水位；不占用供应商 reportRevision，其他事实为空。 */
    private Long usageSnapshotRevision;

    /** 估算规则版本；usageSource=ESTIMATED 时必填。 */
    private String estimatorVersion;

    /** 输入计价金额（分）。 */
    private Long inputAmountCents;

    /** 输出计价金额（分）。 */
    private Long outputAmountCents;

    /** 合计计价金额（分）。 */
    private Long totalAmountCents;

    /** 输入精确金额（分，保留小数）。 */
    private java.math.BigDecimal inputAmount;

    /** 输出精确金额（分，保留小数）。 */
    private java.math.BigDecimal outputAmount;

    /** 合计精确金额（分，保留小数）。 */
    private java.math.BigDecimal totalAmount;

    /** 写时冻结的汇率快照（相对 CNY；无换汇时为 1）。 */
    private java.math.BigDecimal exchangeRateToCny;

    /** 计价事实快照版本，供历史对账解释。 */
    private String pricingSnapshotVersion;

    /** 计价币种。 */
    private String currency;

    /** 账本事实类型：measured / reconciliation / adjustment。 */
    private String usageEventKind;

    /** 金额事实状态：priced / unpriced / unresolved。 */
    private String amountStatus;

    /** 对账或补偿事件引用的原始用量事件。 */
    private Long adjustmentOfEventId;

    /** 业务幂等 identifier（UNIQUE(tenant_id, event_id)）。 */
    private String eventId;

    /** 逻辑外部调用 ID；为空表示本事件不由外部调用守卫产生。 */
    private String invocationId;

    /** 实际外部调用尝试 ID；同一逻辑重试使用不同值。 */
    private String attemptId;

    /** 链路根：一次用户请求共享。 */
    private String requestId;

    /** 权威根 Input ID。 */
    private String rootInputId;

    /** 父子引用：内层调用指向外层事件。 */
    private Long parentEventId;

    /** 会话 ID。 */
    private String sessionId;

    /** 原入口 C 端用户；后台提取保留来源作者，实际调用者由 requesterKind/ref 明确。 */
    private Long endUserId;

    /** 渠道 ID。 */
    private String channelId;

    /** Agent ID。 */
    private Long agentId;

    /** Agent Runtime Run ID。 */
    private String runId;

    /** 工作流实例 ID。 */
    private Long workflowInstanceId;

    /** Runtime Execution ID。 */
    private String executionId;

    /** 三方透传实体标识。 */
    private String scopeId;

    /** 三方透传类型。 */
    private String scopeType;

    /** 调用点固定枚举。 */
    private String callPoint;

    /** 补充细节 JSONB（plan_id/step_id/模型参数等，不做统计维度）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> meta;

    /** 记录创建时间。 */
    private Instant createdAt;
}
