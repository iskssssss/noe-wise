-- V43: 计费流水最小单元化 —— usage_events 原子事件表 + model_price_overrides 租户单价覆盖
-- 权威设计：docs/02-架构与设计/03-数据模型设计.md 计费与审计（目标设计，N28）
-- 背景：对齐「流水只记录最小单元（每次 AI 调用），其余统计基于最小单元聚合」，
--   参考业界成熟模式（LiteLLM SpendLogs 写时计价 / Langfuse Trace 树 / Stripe Meter Event 原子事件）：
--   1) usage_events 只 INSERT 不 UPDATE（事件不可变，可对账可审计），RLS 仅开 INSERT+SELECT
--   2) 最小单元：llm.chat / llm.embedding / llm.rerank（每次 AI 调用一条，写时计价）
--   3) request 请求根事件：每条用户请求一条（quantity=1、tokens=0、无金额），配额计数与链路根
--   4) request_id 归组：一次请求全部事件共享（全链路聚合/展开键）；parent_event_id 父子引用
--   5) occurred_at（业务时间，账单口径）与 ingested_at（落库时间，处理对账口径）双时间戳
--   6) 关联维度显式列：end_user/session/channel/agent/root input/run/execution/服务商/模型

-- 1) usage_events：AI 用量原子事件表（每次 AI 调用/每次用户请求一条）
CREATE TABLE public.nw_usage_events (
    id                  bigint NOT NULL,             -- 主键雪花 ID
    tenant_id           bigint NOT NULL,             -- 租户 ID（RLS 隔离维度）
    event_type          character varying(32) NOT NULL,  -- request / llm.chat / llm.embedding / llm.rerank
    provider            character varying(64),       -- 服务商：openai_compatible / dashscope / ...（计费只认字符串，不关心协议）
    model               character varying(128),      -- 实际模型名（Agent 级配置覆盖时取 Agent 模型）
    occurred_at         timestamp with time zone NOT NULL,  -- 事件时间（调用发生时刻，账单/统计口径）
    ingested_at         timestamp with time zone NOT NULL,  -- 落库时间（处理时间，对账口径）
    quantity            integer DEFAULT 1 NOT NULL,  -- 计量单位数：llm.chat=1；llm.embedding=输入 token 估算；llm.rerank=文档数；request=1
    tokens              integer,                     -- llm 类总 token（llm.chat/llm.embedding）
    prompt_tokens       integer,                     -- llm.chat 输入 token（服务商返回 usage）
    completion_tokens   integer,                     -- llm.chat 输出 token（服务商返回 usage）
    input_amount_cents  bigint,                      -- 输入计价金额（分，写时按单价计算；无单价为 NULL 不臆造）
    output_amount_cents bigint,                      -- 输出计价金额（分）
    total_amount_cents  bigint,                      -- 合计计价金额（分）
    currency            character varying(8) DEFAULT 'CNY' NOT NULL,
    event_id            character varying(96) NOT NULL,  -- 业务幂等 identifier（重试 lambda 外生成防双计）
    request_id          character varying(64),       -- 链路根：一次用户请求（runChat）共享，全链路聚合/展开键
    parent_event_id     bigint,                      -- 父子引用：内层调用指向外层事件（Langfuse 树）
    session_id          character varying(64),
    end_user_id         bigint,
    channel_id          character varying(64),
    agent_id            bigint,
    root_input_id       text,
    run_id              text,
    execution_id        text,
    scope_id            character varying(64),       -- 三方透传实体标识（按三方实体出账单）
    scope_type          character varying(32),
    call_point          character varying(32),       -- 调用点固定枚举（agentic.action / chat.request 等）
    meta                jsonb DEFAULT '{}'::jsonb NOT NULL,  -- 补充细节（plan_id/step_id/模型参数/错误信息等）
    created_at          timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT skillforge_usage_events_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_usage_events_idem UNIQUE (tenant_id, event_id)
);

ALTER TABLE public.nw_usage_events FORCE ROW LEVEL SECURITY;

-- 事件不可变物理表达：RLS 仅开放 INSERT + SELECT，无 UPDATE/DELETE 策略；
-- 纠错用补偿事件，绝不改写原事件；唯一清理路径 = 平台运维脚本（权限高于租户）。
CREATE POLICY skillforge_usage_events_tenant_insert ON public.nw_usage_events
    FOR INSERT WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));
CREATE POLICY skillforge_usage_events_tenant_select ON public.nw_usage_events
    FOR SELECT USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));

-- 统计索引：时间窗 / 请求归组 / 用户及 Runtime 因果身份
CREATE INDEX idx_skillforge_usage_events_tenant_occurred ON public.nw_usage_events (tenant_id, occurred_at);
CREATE INDEX idx_skillforge_usage_events_request ON public.nw_usage_events (tenant_id, request_id);
CREATE INDEX idx_skillforge_usage_events_user ON public.nw_usage_events (tenant_id, end_user_id, occurred_at);
CREATE INDEX idx_skillforge_usage_events_agent ON public.nw_usage_events (tenant_id, agent_id, occurred_at);
CREATE INDEX idx_skillforge_usage_events_root_input ON public.nw_usage_events (tenant_id, root_input_id, occurred_at);
CREATE INDEX idx_skillforge_usage_events_run ON public.nw_usage_events (tenant_id, run_id, occurred_at);
CREATE INDEX idx_skillforge_usage_events_execution ON public.nw_usage_events (tenant_id, execution_id, occurred_at);

COMMENT ON TABLE public.nw_usage_events IS 'AI 用量原子事件表：每次 AI 调用（llm.chat/llm.embedding/llm.rerank）与每条用户请求（request）一条；只 INSERT 不可变（RLS 仅 INSERT+SELECT），token/金额/次数统计全部基于本表聚合（N28 计费流水最小单元化）';
COMMENT ON COLUMN public.nw_usage_events.id IS '主键雪花 ID';
COMMENT ON COLUMN public.nw_usage_events.tenant_id IS '所属租户 ID（RLS 隔离维度）';
COMMENT ON COLUMN public.nw_usage_events.event_type IS '事件类型：request（请求根事件，quantity=1/tokens=0/无金额）| llm.chat（每次 LLM chat/completions 调用）| llm.embedding（每次 embedding 调用）| llm.rerank（每次 rerank 调用）；后续花费类型枚举扩展';
COMMENT ON COLUMN public.nw_usage_events.provider IS 'AI 服务商标识（openai_compatible / dashscope 等）；计费只认字符串不关心协议，新增服务商无需改表';
COMMENT ON COLUMN public.nw_usage_events.model IS '实际调用模型名（Agent 级 LLM 配置覆盖时取 Agent 模型）';
COMMENT ON COLUMN public.nw_usage_events.occurred_at IS '事件时间（AI 调用发生时刻，账单/统计口径；与 ingested_at 分离支持对账）';
COMMENT ON COLUMN public.nw_usage_events.ingested_at IS '落库时间（处理时间，可观测对账口径）';
COMMENT ON COLUMN public.nw_usage_events.quantity IS '计量单位数：llm.chat=1；llm.embedding=输入 token 估算（chars/4）；llm.rerank=文档数；request=1';
COMMENT ON COLUMN public.nw_usage_events.tokens IS 'llm 类总 token（llm.chat 取服务商 usage.total_tokens；llm.embedding 为输入估算）';
COMMENT ON COLUMN public.nw_usage_events.prompt_tokens IS 'llm.chat 输入 token（服务商 usage.prompt_tokens；未返回为 NULL）';
COMMENT ON COLUMN public.nw_usage_events.completion_tokens IS 'llm.chat 输出 token（服务商 usage.completion_tokens；未返回为 NULL）';
COMMENT ON COLUMN public.nw_usage_events.input_amount_cents IS '输入计价金额（分）：prompt_tokens × 输入单价（百万 token 计）；无单价为 NULL 不臆造价格';
COMMENT ON COLUMN public.nw_usage_events.output_amount_cents IS '输出计价金额（分）：completion_tokens × 输出单价';
COMMENT ON COLUMN public.nw_usage_events.total_amount_cents IS '合计计价金额（分）= input + output（llm.embedding/llm.rerank 按其计量口径计算）';
COMMENT ON COLUMN public.nw_usage_events.currency IS '计价币种（默认 CNY；与单价配置一致）';
COMMENT ON COLUMN public.nw_usage_events.event_id IS '业务幂等 identifier：{request_id}:{call_point}:{seq}，重试 lambda 外生成防双计；UNIQUE(tenant_id, event_id)';
COMMENT ON COLUMN public.nw_usage_events.request_id IS '链路根：一次用户请求（runChat）共享，WHERE request_id=? 即一次请求全链路总流水，展开即最小单元明细';
COMMENT ON COLUMN public.nw_usage_events.parent_event_id IS '父子引用：工作流/agentic 内层调用指向外层事件 ID（嵌套链路可观测）';
COMMENT ON COLUMN public.nw_usage_events.session_id IS '会话 ID（可空：摄取/后台任务无会话上下文）';
COMMENT ON COLUMN public.nw_usage_events.end_user_id IS 'C 端用户 ID（可空：摄取/后台任务无用户上下文）';
COMMENT ON COLUMN public.nw_usage_events.channel_id IS '渠道 ID（可空）';
COMMENT ON COLUMN public.nw_usage_events.agent_id IS 'Agent ID（可空）';
COMMENT ON COLUMN public.nw_usage_events.root_input_id IS '权威根 Input ID；用于重建一次问题的完整因果链';
COMMENT ON COLUMN public.nw_usage_events.run_id IS 'Agent Runtime Run ID';
COMMENT ON COLUMN public.nw_usage_events.execution_id IS 'Runtime Execution ID';
COMMENT ON COLUMN public.nw_usage_events.scope_id IS '三方透传实体标识（B 端 SaaS 按 scope 出账单；可空）';
COMMENT ON COLUMN public.nw_usage_events.scope_type IS '三方透传类型标识（可空）';
COMMENT ON COLUMN public.nw_usage_events.call_point IS '模型或外部能力调用点，由当前调用方记录，例如 chat.request / workflow.llm_node / profile.summarize / profile.merge / knowledge.embed / knowledge.rerank';
COMMENT ON COLUMN public.nw_usage_events.meta IS '补充细节 JSONB（plan_id/step_id/模型参数/输入条数/错误信息等，不做统计维度）';
COMMENT ON COLUMN public.nw_usage_events.created_at IS '记录创建时间';

-- 2) model_price_overrides：租户级模型单价覆盖（平台默认见 skillforge.model-prices 配置，优先级租户覆盖 > 平台默认）
CREATE TABLE public.nw_model_price_overrides (
    id                       bigint NOT NULL,             -- 主键雪花 ID
    tenant_id                bigint NOT NULL,             -- 租户 ID（RLS 隔离维度）
    provider                 character varying(64) NOT NULL,  -- 服务商（openai_compatible / dashscope 等）
    model                    character varying(128) NOT NULL, -- 模型名
    input_price_per_million  numeric(18,6) NOT NULL,      -- 每百万输入 token 单价（分）
    output_price_per_million numeric(18,6) NOT NULL,      -- 每百万输出 token 单价（分）
    currency                 character varying(8) DEFAULT 'CNY' NOT NULL,
    created_at               timestamp with time zone DEFAULT now() NOT NULL,
    updated_at               timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT skillforge_model_price_overrides_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_model_price_overrides UNIQUE (tenant_id, provider, model)
);

ALTER TABLE public.nw_model_price_overrides FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_model_price_overrides_tenant_insert ON public.nw_model_price_overrides
    FOR INSERT WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));
CREATE POLICY skillforge_model_price_overrides_tenant_select ON public.nw_model_price_overrides
    FOR SELECT USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));
CREATE POLICY skillforge_model_price_overrides_tenant_update ON public.nw_model_price_overrides
    FOR UPDATE USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)))
    WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));

COMMENT ON TABLE public.nw_model_price_overrides IS '租户级模型单价覆盖：按 (tenant, provider, model) 覆盖平台默认单价（skillforge.model-prices），计价优先级租户覆盖 > 平台默认；强类型列禁止 Map';
COMMENT ON COLUMN public.nw_model_price_overrides.id IS '主键雪花 ID';
COMMENT ON COLUMN public.nw_model_price_overrides.tenant_id IS '所属租户 ID（RLS 隔离维度）';
COMMENT ON COLUMN public.nw_model_price_overrides.provider IS 'AI 服务商标识';
COMMENT ON COLUMN public.nw_model_price_overrides.model IS '模型名（与 usage_events.model 匹配）';

COMMENT ON COLUMN public.nw_model_price_overrides.input_price_per_million IS '每百万输入 token 单价（分）';
COMMENT ON COLUMN public.nw_model_price_overrides.output_price_per_million IS '每百万输出 token 单价（分）';
COMMENT ON COLUMN public.nw_model_price_overrides.currency IS '计价币种（默认 CNY）';
COMMENT ON COLUMN public.nw_model_price_overrides.created_at IS '创建时间';
COMMENT ON COLUMN public.nw_model_price_overrides.updated_at IS '更新时间';
