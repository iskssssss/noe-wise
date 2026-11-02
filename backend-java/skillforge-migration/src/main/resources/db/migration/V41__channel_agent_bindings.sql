-- V41: 渠道-单 Agent 绑定表
-- 渠道关联单个 Agent，作为渠道级路由目标。主键/外键为雪花 bigint。

CREATE TABLE public.nw_channel_agent_bindings (
    id bigint NOT NULL,
    channel_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    agent_id bigint NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    traffic_percentage integer DEFAULT 100 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE public.nw_channel_agent_bindings FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_channel_agent_bindings_tenant_isolation ON public.nw_channel_agent_bindings
    USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)))
    WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));

ALTER TABLE ONLY public.nw_channel_agent_bindings
    ADD CONSTRAINT skillforge_channel_agent_bindings_pkey PRIMARY KEY (id);

-- 同一渠道不可重复绑定同一 Agent
ALTER TABLE ONLY public.nw_channel_agent_bindings
    ADD CONSTRAINT uq_cab_channel_agent UNIQUE (channel_id, agent_id);

-- 每渠道至多一个默认 Agent
CREATE UNIQUE INDEX idx_cab_default ON public.nw_channel_agent_bindings (channel_id) WHERE is_default;

-- 路由查询索引：按渠道+租户过滤、按优先级倒序（ChannelAgentBindingHandler 查询模式）
CREATE INDEX idx_cab_channel_tenant ON public.nw_channel_agent_bindings (channel_id, tenant_id, priority);

ALTER TABLE ONLY public.nw_channel_agent_bindings
    ADD CONSTRAINT fk_cab_channel FOREIGN KEY (channel_id) REFERENCES public.nw_channels(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.nw_channel_agent_bindings
    ADD CONSTRAINT fk_cab_agent FOREIGN KEY (agent_id) REFERENCES public.nw_agents(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.nw_channel_agent_bindings
    ADD CONSTRAINT fk_cab_tenant FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);

COMMENT ON TABLE public.nw_channel_agent_bindings IS '渠道-单 Agent 绑定：渠道级路由目标（优先级高于渠道 Group 绑定）；请求带 target 时按渠道命中绑定 Agent（运行时校验 published 状态）';
COMMENT ON COLUMN public.nw_channel_agent_bindings.id IS '主键（雪花 bigint）';
COMMENT ON COLUMN public.nw_channel_agent_bindings.channel_id IS '渠道 ID（FK channels，级联删除）';
COMMENT ON COLUMN public.nw_channel_agent_bindings.tenant_id IS '所属租户 ID（FK tenants）';
COMMENT ON COLUMN public.nw_channel_agent_bindings.agent_id IS 'Agent ID（FK agents，级联删除）';
COMMENT ON COLUMN public.nw_channel_agent_bindings.priority IS '路由优先级（数值大优先）';
COMMENT ON COLUMN public.nw_channel_agent_bindings.is_default IS '是否为渠道默认 Agent（每渠道至多一个 true，部分唯一索引约束）';
COMMENT ON COLUMN public.nw_channel_agent_bindings.traffic_percentage IS '灰度流量百分比（0-100，路由随机命中）';
COMMENT ON COLUMN public.nw_channel_agent_bindings.created_at IS '创建时间';
