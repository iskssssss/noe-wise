-- 工作室普通聊天 Run 固定发布版本与实际执行成员；子 Run 使用独立 Runtime Session。
-- 计量必须保留完整 Runtime Session 身份，不能截断委派 Session 或与主会话混记。
DROP VIEW public.billing_effective_usage;
ALTER TABLE public.nw_usage_events ALTER COLUMN session_id TYPE varchar(240);
COMMENT ON COLUMN public.nw_usage_events.session_id IS '完整 Runtime Session 标识，包含原生委派的独立 Session；不得截断';

CREATE VIEW public.billing_effective_usage WITH (security_invoker=true) AS
SELECT e.*, c.effective_amount, c.effective_currency, c.effective_rate,
       CASE WHEN e.event_type='request' THEN 'not_applicable'
            WHEN c.effective_status IS DISTINCT FROM 'priced' THEN COALESCE(c.effective_status,'unpriced')
            WHEN c.effective_amount IS NULL THEN 'unresolved'
            WHEN c.effective_currency IS DISTINCT FROM 'CNY' AND c.effective_rate IS NULL THEN 'unresolved'
            WHEN COALESCE(a.gaps,0)>0 THEN 'unresolved' ELSE 'priced' END AS cost_status,
       CASE WHEN c.effective_status='priced' AND c.effective_amount IS NOT NULL
              AND (c.effective_currency='CNY' OR c.effective_rate IS NOT NULL)
            THEN c.effective_amount * CASE WHEN c.effective_currency='CNY' THEN 1 ELSE c.effective_rate END + COALESCE(a.delta,0)
            ELSE a.delta END AS confirmed_cost_cents
FROM public.nw_usage_events e
LEFT JOIN LATERAL (SELECT r.* FROM public.nw_usage_events r WHERE r.tenant_id=e.tenant_id
    AND r.adjustment_of_event_id=e.id AND r.usage_event_kind='reconciliation'
    ORDER BY r.ingested_at DESC,r.id DESC LIMIT 1) r ON true
CROSS JOIN LATERAL (SELECT CASE WHEN r.id IS NULL THEN e.total_amount ELSE r.total_amount END AS effective_amount,
    CASE WHEN r.id IS NULL THEN e.currency ELSE r.currency END AS effective_currency,
    CASE WHEN r.id IS NULL THEN e.exchange_rate_to_cny ELSE r.exchange_rate_to_cny END AS effective_rate,
    CASE WHEN r.id IS NULL AND e.event_type IN ('llm.chat','llm.embedding') AND e.usage_source IS DISTINCT FROM 'PROVIDER_REPORTED' THEN 'unresolved'
         WHEN r.id IS NULL THEN e.amount_status ELSE r.amount_status END AS effective_status) c
LEFT JOIN LATERAL (SELECT SUM(CASE WHEN x.amount_status='priced' THEN x.total_amount *
    CASE WHEN x.currency='CNY' THEN 1 ELSE x.exchange_rate_to_cny END END) AS delta,
    COUNT(*) FILTER(WHERE x.amount_status IS DISTINCT FROM 'priced' OR x.total_amount IS NULL
        OR (x.currency IS DISTINCT FROM 'CNY' AND x.exchange_rate_to_cny IS NULL)) AS gaps
    FROM public.nw_usage_events x WHERE x.tenant_id=e.tenant_id AND x.adjustment_of_event_id=e.id
    AND x.usage_event_kind='adjustment') a ON true
WHERE e.usage_event_kind='measured';
COMMENT ON VIEW public.billing_effective_usage IS '原子用量的有效成本投影；最新对账确认替代原金额，调整仅按增减额追加，金额单位为分';

-- 同一绑定支持 Agent 会话和工作室会话；新契约不读取旧工作室绑定格式。
CREATE TABLE public.nw_runtime_agent_run_bindings (
    tenant_id varchar(160) NOT NULL,
    run_id varchar(240) NOT NULL,
    input_id varchar(240) NOT NULL,
    session_id varchar(240) NOT NULL,
    target_kind varchar(20) NOT NULL CHECK(target_kind IN ('AGENT','TEAM')),
    target_id varchar(240) NOT NULL,
    end_user_id bigint NOT NULL CHECK(end_user_id > 0),
    actor_agent_id varchar(240) NOT NULL,
    role varchar(20) NOT NULL CHECK(role IN ('PRIMARY','DELEGATE')),
    parent_run_id varchar(240),
    scope_snapshot jsonb NOT NULL,
    frozen_member jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,run_id),
    UNIQUE(tenant_id,session_id,input_id),
    CHECK(target_kind <> 'AGENT' OR target_id=actor_agent_id),
    CHECK((role='PRIMARY' AND parent_run_id IS NULL) OR (role='DELEGATE' AND parent_run_id IS NOT NULL)),
    FOREIGN KEY(tenant_id,parent_run_id) REFERENCES public.nw_runtime_agent_run_bindings(tenant_id,run_id)
);
CREATE INDEX agent_runtime_agent_parent_runs ON public.nw_runtime_agent_run_bindings(tenant_id,parent_run_id) WHERE parent_run_id IS NOT NULL;
ALTER TABLE public.nw_runtime_agent_run_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_agent_run_bindings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_agent_run_bindings
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
COMMENT ON TABLE public.nw_runtime_agent_run_bindings IS 'Agent 主 Run 与委派子 Run 的不可变执行绑定，工作室为可选范围';
COMMENT ON COLUMN public.nw_runtime_agent_run_bindings.scope_snapshot IS '原请求者、主 Run、冻结委派范围及目标配置；仅服务端读取';
COMMENT ON COLUMN public.nw_runtime_agent_run_bindings.frozen_member IS '实际执行 Agent 的私有配置，仅该 Agent 执行可见';
