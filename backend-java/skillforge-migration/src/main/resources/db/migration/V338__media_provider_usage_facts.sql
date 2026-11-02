-- 媒体供应商计量只追加真实观察；无定价单位时保持缺价，不按调用次数补造媒体费用。
ALTER TABLE public.nw_usage_events
 ADD COLUMN pricing_model_id bigint,
 ADD COLUMN pricing_model_revision bigint,
 ADD COLUMN image_count bigint,
 ADD COLUMN video_count bigint,
 ADD COLUMN characters bigint,
 ADD COLUMN duration_seconds numeric,
 ADD CONSTRAINT usage_media_model_revision CHECK (
  (pricing_model_id IS NULL AND pricing_model_revision IS NULL)
  OR (pricing_model_id IS NOT NULL AND pricing_model_id>0 AND pricing_model_revision IS NOT NULL AND pricing_model_revision>0)),
 ADD CONSTRAINT usage_media_measurement_nonnegative CHECK (
  (image_count IS NULL OR image_count>=0) AND (video_count IS NULL OR video_count>=0)
  AND (characters IS NULL OR characters>=0)
  AND (duration_seconds IS NULL OR duration_seconds>=0
   AND duration_seconds NOT IN ('NaN'::numeric,'Infinity'::numeric,'-Infinity'::numeric))),
 ADD CONSTRAINT usage_media_measurement_type CHECK (
  (pricing_model_id IS NULL AND image_count IS NULL AND video_count IS NULL AND characters IS NULL AND duration_seconds IS NULL)
  OR event_type IN ('media.image','media.video','media.transcription','media.speech'));
ALTER TABLE public.nw_usage_events DROP CONSTRAINT usage_event_type;
ALTER TABLE public.nw_usage_events ADD CONSTRAINT usage_event_type CHECK(event_type IN
 ('request','llm.chat','llm.embedding','llm.rerank','knowledge.source','tool.call','workflow.external',
  'media.image','media.video','media.transcription','media.speech'));
ALTER TABLE public.nw_usage_events DROP CONSTRAINT usage_snapshot_revision_contract;
ALTER TABLE public.nw_usage_events ADD CONSTRAINT usage_snapshot_revision_contract CHECK (
 usage_snapshot_revision IS NULL OR (usage_event_kind='measured' AND attempt_id IS NOT NULL
  AND ((event_type='llm.chat' AND usage_snapshot_revision>=0)
   OR (event_type IN ('media.image','media.video','media.transcription','media.speech')
    AND usage_snapshot_revision>0))));
COMMENT ON COLUMN public.nw_usage_events.usage_snapshot_revision IS 'measured终态的计量水位：聊天使用真实报告水位，媒体使用原Job内部观察序号；不占用供应商报告revision命名空间';
COMMENT ON CONSTRAINT usage_snapshot_revision_contract ON public.nw_usage_events IS '计量水位仅属于有attempt的聊天或媒体measured终态；媒体内部观察序号必须为正，不伪造usage_report';
ALTER TABLE public.nw_usage_events DROP CONSTRAINT ck_request_nonbillable;
ALTER TABLE public.nw_usage_events ADD CONSTRAINT ck_request_nonbillable CHECK (
 (event_type='request' AND amount_status='not_applicable')
 OR (event_type<>'request' AND amount_status<>'not_applicable')
 OR (event_type IN ('media.image','media.video','media.transcription','media.speech')
  AND quantity=0 AND amount_status='not_applicable'));
ALTER TABLE public.nw_usage_events ADD CONSTRAINT usage_media_not_submitted CHECK (
 event_type NOT IN ('media.image','media.video','media.transcription','media.speech') OR amount_status<>'not_applicable'
 OR (usage_event_kind='measured' AND quantity=0 AND usage_source='UNKNOWN' AND tokens IS NULL
  AND prompt_tokens IS NULL AND completion_tokens IS NULL AND image_count IS NULL AND video_count IS NULL
  AND characters IS NULL AND duration_seconds IS NULL AND total_amount IS NULL AND total_amount_cents IS NULL));
CREATE INDEX usage_media_invocation_started ON public.nw_usage_events(tenant_id,invocation_id)
 WHERE event_type IN ('media.image','media.video','media.transcription','media.speech')
   AND usage_event_kind='usage_report' AND report_revision=0;
COMMENT ON COLUMN public.nw_usage_events.pricing_model_id IS '媒体原调用冻结的平台模型ID；不按当前模型追溯历史，无原事实不补造';
COMMENT ON COLUMN public.nw_usage_events.pricing_model_revision IS '媒体原调用冻结的已发布模型修订；与pricing_model_id成对，其他事件NULL';
COMMENT ON COLUMN public.nw_usage_events.image_count IS '供应商明确报告的图片数量；NULL未知，不使用请求数量或文件数替代';
COMMENT ON COLUMN public.nw_usage_events.video_count IS '供应商明确报告的视频数量；NULL未知';
COMMENT ON COLUMN public.nw_usage_events.characters IS '供应商明确报告的字符计量；NULL未知，不按原文字数估算';
COMMENT ON COLUMN public.nw_usage_events.duration_seconds IS '供应商明确报告的秒数，保留原十进制精度；NULL未知，不使用本地解码时长替代';
COMMENT ON COLUMN public.nw_usage_events.report_revision IS 'LLM供应商报告修订，媒体为原Job CAS驱动的内部观察序号；0表示发送前冻结的未知开始事实';

-- 显式保留原有列顺序，新增列只追加在末尾；CREATE OR REPLACE保留既有依赖和ACL。
CREATE OR REPLACE VIEW public.billing_effective_usage WITH (security_invoker=true) AS
SELECT e.id,e.tenant_id,e.event_type,e.provider,e.model,e.occurred_at,e.ingested_at,e.quantity,e.tokens,e.prompt_tokens,e.completion_tokens,e.input_amount_cents,e.output_amount_cents,e.total_amount_cents,e.currency,e.event_id,e.request_id,e.parent_event_id,e.session_id,e.end_user_id,e.channel_id,e.agent_id,e.root_input_id,e.run_id,e.execution_id,e.scope_id,e.scope_type,e.call_point,e.meta,e.created_at,e.usage_source,e.cached_input_tokens,e.cache_write_input_tokens,e.reasoning_output_tokens,e.estimator_version,e.invocation_id,e.attempt_id,e.input_amount,e.output_amount,e.total_amount,e.exchange_rate_to_cny,e.pricing_snapshot_version,e.usage_event_kind,e.amount_status,e.adjustment_of_event_id,e.workflow_instance_id,e.report_revision, c.effective_amount, c.effective_currency, c.effective_rate,
       CASE WHEN e.event_type='request' OR (e.event_type IN ('media.image','media.video','media.transcription','media.speech')
              AND e.quantity=0 AND e.amount_status='not_applicable') THEN 'not_applicable'
            WHEN c.effective_status IS DISTINCT FROM 'priced' THEN COALESCE(c.effective_status,'unpriced')
            WHEN c.effective_amount IS NULL THEN 'unresolved'
            WHEN c.effective_currency IS DISTINCT FROM 'CNY' AND c.effective_rate IS NULL THEN 'unresolved'
            WHEN COALESCE(a.gaps,0)>0 THEN 'unresolved' ELSE 'priced' END AS cost_status,
       CASE WHEN c.effective_status='priced' AND c.effective_amount IS NOT NULL
              AND (c.effective_currency='CNY' OR c.effective_rate IS NOT NULL)
            THEN c.effective_amount * CASE WHEN c.effective_currency='CNY' THEN 1 ELSE c.effective_rate END + COALESCE(a.delta,0)
            ELSE a.delta END AS confirmed_cost_cents,
       e.pricing_model_id,e.pricing_model_revision,e.image_count,e.video_count,e.characters,e.duration_seconds
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

CREATE VIEW public.nw_observation_effective_media_usage WITH (security_invoker=true) AS
SELECT DISTINCT ON (e.tenant_id,e.attempt_id) e.*
FROM public.nw_usage_events e
WHERE e.event_type IN ('media.image','media.video','media.transcription','media.speech')
 AND e.usage_event_kind IN ('measured','usage_report') AND e.attempt_id IS NOT NULL
ORDER BY e.tenant_id,e.attempt_id,COALESCE(e.report_revision,e.usage_snapshot_revision,0) DESC,
 (e.usage_event_kind='measured') DESC,e.id DESC;
COMMENT ON VIEW public.nw_observation_effective_media_usage IS '同一媒体供应商Attempt一条有效累计观察；NULL不覆盖已知计量，同序号最终计量优先；金额仍由原measured缺价事实决定';
-- 新投影只继承既有观察投影的SELECT读权，不扩大账本写权或硬编码开发机角色。
DO $$
DECLARE permission record;
BEGIN
 FOR permission IN SELECT DISTINCT privilege.grantee FROM pg_class relation
  CROSS JOIN LATERAL aclexplode(COALESCE(relation.relacl,acldefault('r',relation.relowner))) privilege
  WHERE relation.oid='public.observation_effective_model_usage'::regclass AND privilege.privilege_type='SELECT'
 LOOP
  EXECUTE format('GRANT SELECT ON public.nw_observation_effective_media_usage TO %s',
   CASE WHEN permission.grantee=0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(permission.grantee)) END);
 END LOOP;
END $$;
