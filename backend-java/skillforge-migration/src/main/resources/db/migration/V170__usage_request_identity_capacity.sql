DROP VIEW public.billing_effective_usage;
-- 系统摘要使用稳定的复合调用标识；计费请求归组键与 external_invocations 的身份容量一致。
ALTER TABLE public.nw_usage_events ALTER COLUMN request_id TYPE varchar(128);
COMMENT ON COLUMN public.nw_usage_events.request_id IS '完整请求归组标识，支持系统用途复合身份；不得截断以免错误合并计费链路';

CREATE VIEW billing_effective_usage WITH (security_invoker=true) AS
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
FROM nw_usage_events e
LEFT JOIN LATERAL (SELECT r.* FROM nw_usage_events r WHERE r.tenant_id=e.tenant_id
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
    FROM nw_usage_events x WHERE x.tenant_id=e.tenant_id AND x.adjustment_of_event_id=e.id
    AND x.usage_event_kind='adjustment') a ON true
WHERE e.usage_event_kind='measured';
COMMENT ON VIEW billing_effective_usage IS '原子用量的有效成本投影；最新对账确认替代原金额，调整仅按增减额追加，金额单位为分';
