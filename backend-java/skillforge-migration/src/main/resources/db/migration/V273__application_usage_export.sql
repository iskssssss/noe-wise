-- 应用用量导出复用原子计量账本；待发布事实与原事实同事务，游标仅在提交后分配。
ALTER TABLE public.nw_usage_events
 ADD COLUMN application_id varchar(64),
 ADD COLUMN integration_id varchar(128),
 ADD COLUMN business_scope_id varchar(64),
 ADD COLUMN agent_definition_version varchar(128),
 ADD CONSTRAINT usage_application_fk FOREIGN KEY(application_id) REFERENCES public.nw_integration_applications(application_id);
COMMENT ON COLUMN public.nw_usage_events.application_id IS '可信 Session/Run 冻结应用来源；非应用请求为空';
COMMENT ON COLUMN public.nw_usage_events.integration_id IS '机构执行接入身份；不接受消息上下文覆盖';
COMMENT ON COLUMN public.nw_usage_events.business_scope_id IS '创建 Session 时授权并冻结的机构内业务范围';
COMMENT ON COLUMN public.nw_usage_events.agent_definition_version IS '真实执行 Run 冻结定义版本；不随当前发布变化';
COMMENT ON CONSTRAINT usage_application_fk ON public.nw_usage_events IS '计量来源必须引用已存在应用';
CREATE INDEX usage_application_dimensions ON public.nw_usage_events(application_id,tenant_id,occurred_at,agent_id) WHERE application_id IS NOT NULL;
COMMENT ON INDEX public.usage_application_dimensions IS '应用机构按时间与执行 Agent 查询计量事实';

CREATE TABLE public.nw_application_usage_streams (
 application_id varchar(64) PRIMARY KEY REFERENCES public.nw_integration_applications(application_id),
 published_cursor bigint NOT NULL DEFAULT 0 CHECK(published_cursor >= 0)
);
COMMENT ON TABLE public.nw_application_usage_streams IS '每应用提交后用量发布序列；行锁序列化分配且和发布同事务提交';
COMMENT ON COLUMN public.nw_application_usage_streams.application_id IS '应用身份，也是独立发布游标命名空间';
COMMENT ON COLUMN public.nw_application_usage_streams.published_cursor IS '最后已提交发布游标，不能用源事件 ID 或发生时间代替';
COMMENT ON CONSTRAINT nw_application_usage_streams_pkey ON public.nw_application_usage_streams IS '同应用仅一条串行发布状态';
COMMENT ON CONSTRAINT nw_application_usage_streams_application_id_fkey ON public.nw_application_usage_streams IS '发布状态随应用治理生命周期保留';
COMMENT ON CONSTRAINT nw_application_usage_streams_published_cursor_check ON public.nw_application_usage_streams IS '应用发布游标只能为非负值';

CREATE TABLE public.nw_application_usage_exports (
 application_id varchar(64) NOT NULL REFERENCES public.nw_integration_applications(application_id),
 tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
 usage_id bigint NOT NULL,
 event_id varchar(640) NOT NULL,
 source_event_id varchar(512) NOT NULL,
 integration_id varchar(128) NOT NULL,
 business_scope_id varchar(64),
 agent_definition_version varchar(128),
 publication_cursor bigint,
 published_at timestamptz,
 event_type varchar(64) NOT NULL,
 usage_event_kind varchar(32) NOT NULL,
 report_revision bigint,
 adjustment_of_event_id varchar(640),
 invocation_id varchar(240), attempt_id varchar(240),
 request_id varchar(240), root_input_id varchar(240),
 session_id varchar(240), run_id varchar(240),
 end_user_id bigint, channel_id varchar(128), agent_id bigint,
 provider varchar(128), model varchar(240),
 quantity integer, tokens bigint, prompt_tokens bigint, completion_tokens bigint,
 usage_source varchar(32), amount_status varchar(32),
 amount_cents numeric, currency varchar(16), exchange_rate_to_cny numeric,
 occurred_at timestamptz NOT NULL, ingested_at timestamptz NOT NULL,
 PRIMARY KEY(application_id,event_id),
    CONSTRAINT skillforge_application_usage_exports_application_id__113e15a9c1 UNIQUE(application_id,publication_cursor),
 UNIQUE(tenant_id,usage_id),
 FOREIGN KEY(tenant_id,usage_id) REFERENCES public.nw_usage_events(tenant_id,id),
 CONSTRAINT application_usage_association_fk FOREIGN KEY(application_id,tenant_id)
   REFERENCES public.nw_integration_application_tenants(application_id,tenant_id),
 CHECK((publication_cursor IS NULL AND published_at IS NULL) OR (publication_cursor > 0 AND published_at IS NOT NULL)),
 CHECK(usage_event_kind IN ('measured','usage_report','reconciliation','adjustment'))
);
COMMENT ON TABLE public.nw_application_usage_exports IS '追加计量事实的应用导出投影；仅发布字段允许变化，客户价格与扣款不在此表';
COMMENT ON COLUMN public.nw_application_usage_exports.application_id IS '从可信执行来源冻结的应用身份';
COMMENT ON COLUMN public.nw_application_usage_exports.tenant_id IS '机构隔离与费用归属';
COMMENT ON COLUMN public.nw_application_usage_exports.usage_id IS '源账本主键，仅用于关联，不能作为增量水位';
COMMENT ON COLUMN public.nw_application_usage_exports.event_id IS '租户前缀加源事件业务键，跨机构稳定幂等身份';
COMMENT ON COLUMN public.nw_application_usage_exports.source_event_id IS '原用量账本幂等业务键';
COMMENT ON COLUMN public.nw_application_usage_exports.integration_id IS '冻结的机构执行接入来源';
COMMENT ON COLUMN public.nw_application_usage_exports.business_scope_id IS '已授权且冻结的业务范围';
COMMENT ON COLUMN public.nw_application_usage_exports.agent_definition_version IS 'Run 冻结定义版本';
COMMENT ON COLUMN public.nw_application_usage_exports.publication_cursor IS '提交后在应用锁内分配；NULL 代表可靠待发布';
COMMENT ON COLUMN public.nw_application_usage_exports.published_at IS '发布事务时间；不代表发生时间';
COMMENT ON COLUMN public.nw_application_usage_exports.event_type IS '请求、模型或外部能力实际调用类型';
COMMENT ON COLUMN public.nw_application_usage_exports.usage_event_kind IS '原始计量、累计报告、成本确认或成本增减';
COMMENT ON COLUMN public.nw_application_usage_exports.report_revision IS '供应商累计报告修订号，非报告为空';
COMMENT ON COLUMN public.nw_application_usage_exports.adjustment_of_event_id IS '确认或调整引用的原导出事件稳定身份';
COMMENT ON COLUMN public.nw_application_usage_exports.invocation_id IS '逻辑外部调用身份';
COMMENT ON COLUMN public.nw_application_usage_exports.attempt_id IS '真实外部调用尝试身份，累计报告按该身份合并';
COMMENT ON COLUMN public.nw_application_usage_exports.request_id IS '请求因果根';
COMMENT ON COLUMN public.nw_application_usage_exports.root_input_id IS 'Runtime 权威根输入身份';
COMMENT ON COLUMN public.nw_application_usage_exports.session_id IS '真实执行会话，包含委派独立会话';
COMMENT ON COLUMN public.nw_application_usage_exports.run_id IS '真实执行 Run';
COMMENT ON COLUMN public.nw_application_usage_exports.end_user_id IS '入口业务用户';
COMMENT ON COLUMN public.nw_application_usage_exports.channel_id IS '入口渠道';
COMMENT ON COLUMN public.nw_application_usage_exports.agent_id IS '实际执行机构 Agent 实例';
COMMENT ON COLUMN public.nw_application_usage_exports.provider IS '供应商标识';
COMMENT ON COLUMN public.nw_application_usage_exports.model IS '真实调用模型';
COMMENT ON COLUMN public.nw_application_usage_exports.quantity IS '原事件计量单位数；请求与调用数量分开汇总';
COMMENT ON COLUMN public.nw_application_usage_exports.tokens IS '原报告总 token；未知保持 NULL';
COMMENT ON COLUMN public.nw_application_usage_exports.prompt_tokens IS '输入 token；未知保持 NULL';
COMMENT ON COLUMN public.nw_application_usage_exports.completion_tokens IS '输出 token；未知保持 NULL';
COMMENT ON COLUMN public.nw_application_usage_exports.usage_source IS '供应商实测、估算或未知';
COMMENT ON COLUMN public.nw_application_usage_exports.amount_status IS '供应商成本事实状态；不可用未知值代替零';
COMMENT ON COLUMN public.nw_application_usage_exports.amount_cents IS '精确供应商金额，单位分，保留小数，客户端按字符串消费';
COMMENT ON COLUMN public.nw_application_usage_exports.currency IS '供应商金额币种';
COMMENT ON COLUMN public.nw_application_usage_exports.exchange_rate_to_cny IS '写时冻结汇率，用于汇总人民币供应商成本';
COMMENT ON COLUMN public.nw_application_usage_exports.occurred_at IS '源调用发生时间，支持业务时间查询但不作增量水位';
COMMENT ON COLUMN public.nw_application_usage_exports.ingested_at IS '源计量事实入库时间';
COMMENT ON CONSTRAINT nw_application_usage_exports_pkey ON public.nw_application_usage_exports IS '每应用事件稳定幂等键';
COMMENT ON CONSTRAINT nw_application_usage_exports_application_id_fkey ON public.nw_application_usage_exports IS '导出来源必须引用已存在应用';
COMMENT ON CONSTRAINT nw_application_usage_exports_tenant_id_fkey ON public.nw_application_usage_exports IS '导出机构必须存在';
COMMENT ON CONSTRAINT skillforge_application_usage_exports_application_id__113e15a9c1 ON public.nw_application_usage_exports IS '应用序列不得重复分配游标';
COMMENT ON CONSTRAINT nw_application_usage_exports_tenant_id_usage_id_key ON public.nw_application_usage_exports IS '原事实仅投影一次';
COMMENT ON CONSTRAINT nw_application_usage_exports_tenant_id_usage_id_fkey ON public.nw_application_usage_exports IS '原事实和导出事实必须同租户且同事务存在';
COMMENT ON CONSTRAINT application_usage_association_fk ON public.nw_application_usage_exports IS '费用机构必须属于可信应用关联';
COMMENT ON CONSTRAINT nw_application_usage_exports_check ON public.nw_application_usage_exports IS '发布游标与发布时刻须原子写入';
COMMENT ON CONSTRAINT nw_application_usage_exports_usage_event_kind_check ON public.nw_application_usage_exports IS '导出只接受已声明账本事实种类';
CREATE INDEX application_usage_pending ON public.nw_application_usage_exports(application_id,ingested_at,usage_id) WHERE publication_cursor IS NULL;
CREATE INDEX application_usage_dimensions ON public.nw_application_usage_exports(application_id,tenant_id,occurred_at,agent_id);
CREATE INDEX application_usage_adjustments ON public.nw_application_usage_exports(application_id,adjustment_of_event_id);
CREATE INDEX application_usage_attempt_reports ON public.nw_application_usage_exports(application_id,tenant_id,attempt_id,report_revision);
COMMENT ON INDEX public.application_usage_pending IS '所有已提交待发布事实可重启扫描；迟提交不会越过水位';
COMMENT ON INDEX public.application_usage_dimensions IS '应用、机构、业务时间和 Agent 明细过滤';
COMMENT ON INDEX public.application_usage_adjustments IS '追加确认与增减事实关联原计量';
COMMENT ON INDEX public.application_usage_attempt_reports IS '按真实尝试归并累计报告，防止多次累计计量';

ALTER TABLE public.nw_application_usage_streams ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_application_usage_streams FORCE ROW LEVEL SECURITY;
CREATE POLICY application_scope ON public.nw_application_usage_streams
 USING(application_id=current_setting('app.application_id',true))
 WITH CHECK(application_id=current_setting('app.application_id',true));
ALTER TABLE public.nw_application_usage_exports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_application_usage_exports FORCE ROW LEVEL SECURITY;
CREATE POLICY application_usage_read ON public.nw_application_usage_exports FOR SELECT
 USING(application_id=current_setting('app.application_id',true) OR tenant_id::text=current_setting('app.tenant_id',true));
CREATE POLICY application_usage_enqueue ON public.nw_application_usage_exports FOR INSERT
 WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
CREATE POLICY application_usage_publish ON public.nw_application_usage_exports FOR UPDATE
 USING(application_id=current_setting('app.application_id',true))
 WITH CHECK(application_id=current_setting('app.application_id',true));
GRANT SELECT,INSERT,UPDATE ON public.nw_application_usage_streams,public.nw_application_usage_exports TO skillforge;

-- 重建属于异步资源操作，计量来源在任务受理时冻结并持久保存，重启仍绑定原来源。
ALTER TABLE public.nw_knowledge_rebuild_jobs
 ADD COLUMN metering_request_id varchar(128),
 ADD COLUMN metering_root_input_id varchar(128),
 ADD COLUMN metering_session_id varchar(128),
 ADD COLUMN metering_run_id varchar(128),
 ADD COLUMN metering_scope_type varchar(64),
 ADD COLUMN metering_scope_id varchar(128),
 ADD COLUMN metering_end_user_id bigint,
 ADD COLUMN metering_agent_id bigint,
 ADD COLUMN metering_channel_id varchar(128),
 ADD CONSTRAINT knowledge_rebuild_metering_ids_check CHECK(
   (metering_end_user_id IS NULL OR metering_end_user_id>0) AND (metering_agent_id IS NULL OR metering_agent_id>0));
COMMENT ON COLUMN public.nw_knowledge_rebuild_jobs.metering_request_id IS '服务端作用域冻结的原请求归组身份；NULL 表示没有请求来源';
COMMENT ON COLUMN public.nw_knowledge_rebuild_jobs.metering_root_input_id IS '服务端作用域冻结的输入根身份；NULL 表示无会话输入根';
COMMENT ON COLUMN public.nw_knowledge_rebuild_jobs.metering_session_id IS '受理时冻结的 Session 来源；NULL 表示非会话操作，恢复禁止补造';
COMMENT ON COLUMN public.nw_knowledge_rebuild_jobs.metering_run_id IS '受理时冻结的 Run 来源；NULL 表示非运行任务，用于解析可信应用';
COMMENT ON COLUMN public.nw_knowledge_rebuild_jobs.metering_scope_type IS '受理时冻结的来源范围类型；ControlOperation 使用 control_operation，NULL 表示无范围';
COMMENT ON COLUMN public.nw_knowledge_rebuild_jobs.metering_scope_id IS '受理时冻结的范围身份；ControlOperation 为操作账本身份，NULL 表示无范围';
COMMENT ON COLUMN public.nw_knowledge_rebuild_jobs.metering_end_user_id IS '受理时冻结的 EndUser 事实身份；NULL 表示没有入口用户，不使用后台当前用户补造';
COMMENT ON COLUMN public.nw_knowledge_rebuild_jobs.metering_agent_id IS '受理时冻结的执行 Agent 身份；NULL 表示非 Agent 来源，不读取当前发布反推';
COMMENT ON COLUMN public.nw_knowledge_rebuild_jobs.metering_channel_id IS '受理时冻结的渠道来源；NULL 表示没有渠道，不能用后台调用线程补造';
COMMENT ON CONSTRAINT knowledge_rebuild_metering_ids_check ON public.nw_knowledge_rebuild_jobs IS '冻结用户与Agent身份存在时必须为正数';
CREATE FUNCTION public.guard_knowledge_rebuild_metering_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.metering_request_id,NEW.metering_root_input_id,NEW.metering_session_id,NEW.metering_run_id,
        NEW.metering_scope_type,NEW.metering_scope_id,NEW.metering_end_user_id,NEW.metering_agent_id,NEW.metering_channel_id)
    IS DISTINCT FROM
    ROW(OLD.metering_request_id,OLD.metering_root_input_id,OLD.metering_session_id,OLD.metering_run_id,
        OLD.metering_scope_type,OLD.metering_scope_id,OLD.metering_end_user_id,OLD.metering_agent_id,OLD.metering_channel_id) THEN
  RAISE EXCEPTION 'knowledge_rebuild_metering_origin_immutable';
 END IF;
 RETURN NEW;
END;
$$;
COMMENT ON FUNCTION public.guard_knowledge_rebuild_metering_origin() IS '重建计量来源冻结后禁止更改，避免恢复或重试改写机构应用费用归属';
CREATE TRIGGER knowledge_rebuild_metering_origin_immutable BEFORE UPDATE ON public.nw_knowledge_rebuild_jobs
 FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_rebuild_metering_origin();
