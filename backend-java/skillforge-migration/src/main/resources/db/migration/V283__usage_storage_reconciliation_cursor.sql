-- 父负责人统一写入 V283__usage_storage_reconciliation_cursor.sql；本文件不是已执行迁移。
-- B1：完整终态只保存到measured，消费水位不冒充供应商报告revision。
ALTER TABLE public.nw_usage_events ADD COLUMN usage_snapshot_revision bigint;
ALTER TABLE public.nw_usage_events ADD CONSTRAINT usage_snapshot_revision_contract CHECK(
 usage_snapshot_revision IS NULL OR (usage_snapshot_revision>=0 AND usage_event_kind='measured'
 AND event_type='llm.chat' AND attempt_id IS NOT NULL));
COMMENT ON COLUMN public.nw_usage_events.usage_snapshot_revision IS '完整模型measured终态消费的真实报告水位；同水位较晚终态可补全字段，不占用供应商revision命名空间，后续真实revision仍可更正';
COMMENT ON CONSTRAINT usage_snapshot_revision_contract ON public.nw_usage_events IS '真实报告水位只属于有attempt的模型measured终态，不伪造usage_report';
-- B2：源事实的精确引用保留同租户、同应用授权关系；保留当前发布序列，不做存量删除。
CREATE INDEX usage_attempt_effective_reports ON public.nw_usage_events(tenant_id,attempt_id,report_revision DESC,id DESC)
 WHERE usage_event_kind IN('measured','usage_report');
COMMENT ON INDEX public.usage_attempt_effective_reports IS '报告变化判定只读取同租户同attempt有效字段，避免高频报告扫描机构全账本';
ALTER TABLE public.nw_usage_events ADD CONSTRAINT usage_event_application_reference
 UNIQUE(tenant_id,id,application_id);
ALTER TABLE public.nw_application_usage_exports
 ADD COLUMN export_schema_version integer NOT NULL DEFAULT 1,
 ADD CONSTRAINT application_usage_export_schema CHECK(export_schema_version=1),
 ADD CONSTRAINT application_usage_source_application_fk FOREIGN KEY(tenant_id,usage_id,application_id)
  REFERENCES public.nw_usage_events(tenant_id,id,application_id);
DROP INDEX public.application_usage_pending;
DROP INDEX public.application_usage_dimensions;
DROP INDEX public.application_usage_adjustments;
DROP INDEX public.application_usage_attempt_reports;
ALTER TABLE public.nw_application_usage_exports
 DROP CONSTRAINT nw_application_usage_exports_usage_event_kind_check,
 DROP COLUMN source_event_id, DROP COLUMN integration_id, DROP COLUMN business_scope_id,
 DROP COLUMN agent_definition_version, DROP COLUMN event_type, DROP COLUMN usage_event_kind,
 DROP COLUMN report_revision, DROP COLUMN adjustment_of_event_id, DROP COLUMN invocation_id,
 DROP COLUMN attempt_id, DROP COLUMN request_id, DROP COLUMN root_input_id, DROP COLUMN session_id,
 DROP COLUMN run_id, DROP COLUMN end_user_id, DROP COLUMN channel_id, DROP COLUMN agent_id,
 DROP COLUMN provider, DROP COLUMN model, DROP COLUMN quantity, DROP COLUMN tokens,
 DROP COLUMN prompt_tokens, DROP COLUMN completion_tokens, DROP COLUMN usage_source,
 DROP COLUMN amount_status, DROP COLUMN amount_cents, DROP COLUMN currency,
 DROP COLUMN exchange_rate_to_cny, DROP COLUMN occurred_at, DROP COLUMN ingested_at;
CREATE INDEX application_usage_pending ON public.nw_application_usage_exports(application_id,usage_id)
 WHERE publication_cursor IS NULL;
COMMENT ON TABLE public.nw_application_usage_exports IS '源不可变计量事实的精确同租户同应用引用及可靠发布状态；业务计量字段由usage_events唯一保存';
COMMENT ON COLUMN public.nw_application_usage_exports.export_schema_version IS '冻结的导出映射协议版本；当前仅支持1，不复制源账本业务字段';
COMMENT ON CONSTRAINT usage_event_application_reference ON public.nw_usage_events IS '导出精确引用同租户且同应用源事实';
COMMENT ON CONSTRAINT application_usage_source_application_fk ON public.nw_application_usage_exports IS '源租户及应用必须同时匹配；未解除发布引用的源事实不可回收';
COMMENT ON CONSTRAINT application_usage_export_schema ON public.nw_application_usage_exports IS '拒绝尚无读取实现的导出映射版本';
COMMENT ON INDEX public.application_usage_pending IS '扫描所有已提交且未发布源引用；不把usage_id当已发布水位';

-- 应用来源读取必须有已存在导出引用；机构上下文存在时继续收窄当前机构。
-- 此策略仅增加SELECT，不增加源账本UPDATE/DELETE，不允许读取其他应用或未导出的源事实。
CREATE POLICY skillforge_usage_events_application_export_select ON public.nw_usage_events FOR SELECT
 USING(application_id=current_setting('app.application_id',true)
  AND (NULLIF(current_setting('app.tenant_id',true),'') IS NULL
       OR tenant_id::text=current_setting('app.tenant_id',true))
  AND EXISTS(SELECT 1 FROM public.nw_application_usage_exports e
   WHERE e.tenant_id=nw_usage_events.tenant_id AND e.usage_id=nw_usage_events.id
    AND e.application_id=nw_usage_events.application_id));
COMMENT ON POLICY skillforge_usage_events_application_export_select ON public.nw_usage_events IS '已授权应用仅读取其精确发布源引用；有机构上下文时仍限定当前机构';

CREATE FUNCTION public.guard_application_usage_export_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.application_id,NEW.tenant_id,NEW.usage_id,NEW.event_id,NEW.export_schema_version)
   IS DISTINCT FROM ROW(OLD.application_id,OLD.tenant_id,OLD.usage_id,OLD.event_id,OLD.export_schema_version) THEN
  RAISE EXCEPTION 'application_usage_export_reference_immutable';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER application_usage_export_reference_immutable BEFORE UPDATE ON public.nw_application_usage_exports
 FOR EACH ROW EXECUTE FUNCTION public.guard_application_usage_export_reference();
COMMENT ON FUNCTION public.guard_application_usage_export_reference() IS '导出仅更新发布状态，源事实身份、授权归属和协议版本冻结';

-- B7：一租户一供应商一条恢复进度，供应商发布窗口契约未确认时不创建进度或推进水位。
-- 当前正式注册http/dashscope；V78只允许http，与已存在DashScope适配器选择冲突。
ALTER TABLE public.nw_tenants DROP CONSTRAINT ck_skillforge_tenants_usage_reconciliation_provider;
ALTER TABLE public.nw_tenants ADD CONSTRAINT ck_skillforge_tenants_usage_reconciliation_provider
 CHECK(usage_reconciliation_provider IS NULL OR usage_reconciliation_provider IN('http','dashscope'));
COMMENT ON CONSTRAINT ck_skillforge_tenants_usage_reconciliation_provider ON public.nw_tenants IS '只允许当前正式注册的账单HTTP与DashScope网关适配器；不表示已具备官方发布水位保证';
CREATE TABLE public.nw_usage_reconciliation_progress (
 tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
 provider_id varchar(128) NOT NULL,
 initial_from timestamptz NOT NULL,
 lookback_seconds bigint NOT NULL,
 watermark timestamptz,
 window_from timestamptz,
 window_to timestamptz,
 request_cursor varchar(4096),
 lease_token varchar(36),
 lease_until timestamptz,
 page_count integer NOT NULL DEFAULT 0,
 blocked_reason varchar(32),
 CONSTRAINT skillforge_usage_reconciliation_progress_pkey PRIMARY KEY(tenant_id,provider_id),
 CONSTRAINT usage_reconciliation_lookback CHECK(lookback_seconds>=0),
 CONSTRAINT usage_reconciliation_page_count CHECK(page_count BETWEEN 0 AND 1000),
 CONSTRAINT usage_reconciliation_window CHECK(
  (window_from IS NULL AND window_to IS NULL AND request_cursor IS NULL AND page_count=0)
  OR (window_from IS NOT NULL AND window_to IS NOT NULL AND window_from<window_to)),
 CONSTRAINT usage_reconciliation_lease CHECK((lease_token IS NULL AND lease_until IS NULL)
  OR (lease_token IS NOT NULL AND lease_until IS NOT NULL AND window_to IS NOT NULL)),
 CONSTRAINT usage_reconciliation_blocked CHECK(blocked_reason IS NULL OR blocked_reason IN('CURSOR_CYCLE','PAGE_LIMIT'))
);
CREATE TABLE public.nw_usage_reconciliation_pages (
 tenant_id bigint NOT NULL,
 provider_id varchar(128) NOT NULL,
 window_to timestamptz NOT NULL,
 request_cursor varchar(4096) NOT NULL,
 cursor_sha256 varchar(64) NOT NULL,
 CONSTRAINT skillforge_usage_reconciliation_pages_pkey PRIMARY KEY(tenant_id,provider_id,window_to,cursor_sha256),
 CONSTRAINT usage_reconciliation_cursor_digest CHECK(cursor_sha256=encode(sha256(convert_to(request_cursor,'UTF8')),'hex')),
 CONSTRAINT skillforge_usage_reconciliation_pages_progress_fk FOREIGN KEY(tenant_id,provider_id)
  REFERENCES public.nw_usage_reconciliation_progress(tenant_id,provider_id)
);
COMMENT ON TABLE public.nw_usage_reconciliation_progress IS '供应商发布窗口的可靠游标和完成水位；只推进已全部提交的事实页，不作为账单权威';
COMMENT ON COLUMN public.nw_usage_reconciliation_progress.tenant_id IS '可信任务租户身份，所有查询与写入强制RLS';
COMMENT ON COLUMN public.nw_usage_reconciliation_progress.provider_id IS '正式供应商选择键，切换供应商保持独立进度';
COMMENT ON COLUMN public.nw_usage_reconciliation_progress.initial_from IS '已证实发布窗口契约的首次完整起点，不允许调度器猜测';
COMMENT ON COLUMN public.nw_usage_reconciliation_progress.lookback_seconds IS '供应商发布窗口契约要求的冻结回看秒数，不属于业务TTL';
COMMENT ON COLUMN public.nw_usage_reconciliation_progress.watermark IS '最后完整窗口终点；失败和不可靠空页不得推进';
COMMENT ON COLUMN public.nw_usage_reconciliation_progress.window_from IS '在途固定窗口起点；重试保持精确原窗口';
COMMENT ON COLUMN public.nw_usage_reconciliation_progress.window_to IS '在途固定窗口终点；末页确认后才成为水位';
COMMENT ON COLUMN public.nw_usage_reconciliation_progress.request_cursor IS '下一页请求游标，NULL表示当前固定窗口第一页';
COMMENT ON COLUMN public.nw_usage_reconciliation_progress.lease_token IS '当前短事务领取token；过期任务不能推进新领取者的状态';
COMMENT ON COLUMN public.nw_usage_reconciliation_progress.lease_until IS '允许重取当前页的时刻，不表示供应商效果已知';
COMMENT ON COLUMN public.nw_usage_reconciliation_progress.page_count IS '固定窗口已确认页数；终态归零，最大1000页';
COMMENT ON COLUMN public.nw_usage_reconciliation_progress.blocked_reason IS '供应商循环或超页预算需要核验，保留原游标不跳过';
COMMENT ON TABLE public.nw_usage_reconciliation_pages IS '仅在途窗口的已确认页游标回执，用于跨调度重启循环检测；完整末页同事务回收';
COMMENT ON COLUMN public.nw_usage_reconciliation_pages.tenant_id IS '与进度完全一致的租户引用';
COMMENT ON COLUMN public.nw_usage_reconciliation_pages.provider_id IS '与进度完全一致的供应商引用';
COMMENT ON COLUMN public.nw_usage_reconciliation_pages.window_to IS '回执所属固定窗口终点，历史已完成窗口不累计';
COMMENT ON COLUMN public.nw_usage_reconciliation_pages.request_cursor IS '已确认页的精确请求游标，空字符串仅编码第一页的无游标状态';
COMMENT ON COLUMN public.nw_usage_reconciliation_pages.cursor_sha256 IS 'UTF-8请求游标的SHA-256，仅在同租户同供应商同窗口检测循环；摘要碰撞保守阻塞不跳过数据';
COMMENT ON CONSTRAINT skillforge_usage_reconciliation_progress_pkey ON public.nw_usage_reconciliation_progress IS '每租户每供应商仅一条恢复进度';
COMMENT ON CONSTRAINT nw_usage_reconciliation_progress_tenant_id_fkey ON public.nw_usage_reconciliation_progress IS '恢复进度引用已存在租户';
COMMENT ON CONSTRAINT usage_reconciliation_lookback ON public.nw_usage_reconciliation_progress IS '已批准回看范围不能为负';
COMMENT ON CONSTRAINT usage_reconciliation_page_count ON public.nw_usage_reconciliation_progress IS '固定窗口页数有界且完成后归零';
COMMENT ON CONSTRAINT usage_reconciliation_window ON public.nw_usage_reconciliation_progress IS '无在途窗口不保留请求游标，活动窗口起点必须早于终点';
COMMENT ON CONSTRAINT usage_reconciliation_lease ON public.nw_usage_reconciliation_progress IS '领取token与到期时间成对存在并引用活动窗口';
COMMENT ON CONSTRAINT usage_reconciliation_blocked ON public.nw_usage_reconciliation_progress IS '异常窗口只能声明已实现的循环或超页预算原因';
COMMENT ON CONSTRAINT skillforge_usage_reconciliation_pages_pkey ON public.nw_usage_reconciliation_pages IS '窗口内同请求游标仅确认一次，摘要不跨租户或供应商';
COMMENT ON CONSTRAINT usage_reconciliation_cursor_digest ON public.nw_usage_reconciliation_pages IS '索引摘要必须与精确UTF-8游标正文一致';
COMMENT ON CONSTRAINT skillforge_usage_reconciliation_pages_progress_fk ON public.nw_usage_reconciliation_pages IS '窗口页回执只能引用已存在且同租户的供应商进度';
ALTER TABLE public.nw_usage_reconciliation_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_usage_reconciliation_progress FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_usage_reconciliation_pages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_usage_reconciliation_pages FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_usage_reconciliation_progress_tenant ON public.nw_usage_reconciliation_progress
 USING(tenant_id::text=current_setting('app.tenant_id',true))
 WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
CREATE POLICY skillforge_usage_reconciliation_pages_tenant ON public.nw_usage_reconciliation_pages
 USING(tenant_id::text=current_setting('app.tenant_id',true))
 WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
GRANT SELECT,INSERT,UPDATE ON public.nw_usage_reconciliation_progress TO skillforge;
GRANT SELECT,INSERT,DELETE ON public.nw_usage_reconciliation_pages TO skillforge;
CREATE OR REPLACE VIEW public.observation_effective_model_usage WITH (security_invoker=true) AS
WITH reports AS (
 SELECT e.*,COALESCE(e.attempt_id, 'event:'||e.id::text) AS observation_key,
        CASE WHEN e.usage_event_kind='usage_report' THEN e.report_revision ELSE COALESCE(e.usage_snapshot_revision,-1) END AS revision
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

