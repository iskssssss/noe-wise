-- 供应商计量报告使用现有追加账本；金额对账和计量修订互不覆盖。
DROP VIEW public.billing_effective_usage;
ALTER TABLE public.nw_usage_events
    ALTER COLUMN event_id TYPE varchar(512),
    ALTER COLUMN tokens TYPE bigint,
    ALTER COLUMN prompt_tokens TYPE bigint,
    ALTER COLUMN completion_tokens TYPE bigint,
    ALTER COLUMN cached_input_tokens TYPE bigint,
    ALTER COLUMN cache_write_input_tokens TYPE bigint,
    ALTER COLUMN reasoning_output_tokens TYPE bigint,
    ADD COLUMN report_revision bigint;
ALTER TABLE public.nw_usage_events DROP CONSTRAINT ck_skillforge_usage_events_kind;
ALTER TABLE public.nw_usage_events ADD CONSTRAINT ck_skillforge_usage_events_kind
    CHECK (usage_event_kind IN ('measured','reconciliation','adjustment','usage_report'));
ALTER TABLE public.nw_usage_events ADD CONSTRAINT ck_usage_report_identity CHECK (
    (usage_event_kind='usage_report' AND report_revision IS NOT NULL AND report_revision>=0 AND attempt_id IS NOT NULL)
    OR (usage_event_kind<>'usage_report' AND report_revision IS NULL));
ALTER TABLE public.nw_usage_events ADD CONSTRAINT ck_usage_report_measurement CHECK (
    usage_event_kind<>'usage_report' OR (
        usage_source IN ('PROVIDER_REPORTED','UNKNOWN')
        AND (prompt_tokens IS NULL OR prompt_tokens>=0)
        AND (completion_tokens IS NULL OR completion_tokens>=0)
        AND (cached_input_tokens IS NULL OR cached_input_tokens>=0)
        AND (cache_write_input_tokens IS NULL OR cache_write_input_tokens>=0)
        AND (reasoning_output_tokens IS NULL OR reasoning_output_tokens>=0)
        AND total_amount IS NULL AND total_amount_cents IS NULL));
CREATE UNIQUE INDEX usage_report_revision ON public.nw_usage_events(tenant_id,attempt_id,report_revision)
    WHERE usage_event_kind='usage_report';
CREATE INDEX usage_report_observation ON public.nw_usage_events(tenant_id,session_id,root_input_id,attempt_id,report_revision DESC)
    WHERE usage_event_kind IN ('measured','usage_report');
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


-- 每个尝试一条有效计量；按报告修订逐字段保留已报告值，原始金额视图只读取 measured。
CREATE VIEW public.observation_effective_model_usage WITH (security_invoker=true) AS
WITH reports AS (
 SELECT e.*,COALESCE(e.attempt_id, 'event:'||e.id::text) AS observation_key,
        CASE WHEN e.usage_event_kind='usage_report' THEN e.report_revision ELSE -1 END AS revision
 FROM public.nw_usage_events e
 WHERE e.event_type='llm.chat' AND e.usage_event_kind IN ('measured','usage_report')
), grouped AS (
 SELECT tenant_id,session_id,root_input_id,run_id,observation_key,
        (array_agg(id ORDER BY revision DESC,id DESC))[1] AS metadata_id,
        MAX(id) AS boundary_id,MAX(ingested_at) AS read_at,
        (array_agg(prompt_tokens ORDER BY revision DESC,id DESC) FILTER(WHERE prompt_tokens IS NOT NULL AND usage_source='PROVIDER_REPORTED'))[1] AS prompt_tokens,
        (array_agg(completion_tokens ORDER BY revision DESC,id DESC) FILTER(WHERE completion_tokens IS NOT NULL AND usage_source='PROVIDER_REPORTED'))[1] AS completion_tokens,
        (array_agg(cached_input_tokens ORDER BY revision DESC,id DESC) FILTER(WHERE cached_input_tokens IS NOT NULL AND usage_source='PROVIDER_REPORTED'))[1] AS cached_input_tokens,
        (array_agg(cache_write_input_tokens ORDER BY revision DESC,id DESC) FILTER(WHERE cache_write_input_tokens IS NOT NULL AND usage_source='PROVIDER_REPORTED'))[1] AS cache_write_input_tokens,
        (array_agg(reasoning_output_tokens ORDER BY revision DESC,id DESC) FILTER(WHERE reasoning_output_tokens IS NOT NULL AND usage_source='PROVIDER_REPORTED'))[1] AS reasoning_output_tokens
 FROM reports GROUP BY tenant_id,session_id,root_input_id,run_id,observation_key
)
SELECT g.boundary_id AS id,g.tenant_id,g.session_id,g.root_input_id,g.run_id,e.invocation_id,e.attempt_id,
       e.provider,e.model,e.event_type,e.call_point,e.request_id,e.end_user_id,e.agent_id,e.execution_id,e.meta,
       e.event_id,e.created_at,e.quantity,
       m.total_amount_cents,m.currency,m.input_amount_cents,m.output_amount_cents,m.amount_status,
       m.pricing_snapshot_version,
       CASE WHEN g.prompt_tokens IS NOT NULL AND g.completion_tokens IS NOT NULL
                 AND g.prompt_tokens::numeric+g.completion_tokens::numeric<=9223372036854775807
            THEN (g.prompt_tokens::numeric+g.completion_tokens::numeric)::bigint END AS tokens,
       e.occurred_at,g.read_at AS ingested_at,
       g.prompt_tokens,g.completion_tokens,
       CASE WHEN g.prompt_tokens IS NULL OR g.cached_input_tokens<=g.prompt_tokens
            THEN g.cached_input_tokens END AS cached_input_tokens,
       CASE WHEN g.prompt_tokens IS NULL OR g.cache_write_input_tokens<=g.prompt_tokens
            THEN g.cache_write_input_tokens END AS cache_write_input_tokens,
       CASE WHEN g.completion_tokens IS NULL OR g.reasoning_output_tokens<=g.completion_tokens
            THEN g.reasoning_output_tokens END AS reasoning_output_tokens,
       CASE WHEN g.prompt_tokens IS NOT NULL OR g.completion_tokens IS NOT NULL OR g.cached_input_tokens IS NOT NULL
           OR g.cache_write_input_tokens IS NOT NULL OR g.reasoning_output_tokens IS NOT NULL
            THEN 'PROVIDER_REPORTED' ELSE 'UNKNOWN' END AS usage_source
FROM grouped g JOIN public.nw_usage_events e ON e.tenant_id=g.tenant_id AND e.id=g.metadata_id
LEFT JOIN LATERAL (SELECT u.* FROM public.nw_usage_events u WHERE u.tenant_id=e.tenant_id
    AND u.usage_event_kind='measured' AND (u.attempt_id=e.attempt_id OR (e.attempt_id IS NULL AND u.id=e.id))
    ORDER BY u.id DESC LIMIT 1) m ON true;
COMMENT ON VIEW public.observation_effective_model_usage IS '按供应商尝试归并有效 Token；不累计流式快照，不改变金额事实';
