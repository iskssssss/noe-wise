ALTER TABLE public.agent_runtime_session_events
    ADD CONSTRAINT agent_runtime_session_events_run_event UNIQUE(tenant_id,run_id,event_id);

CREATE TABLE public.agent_runtime_session_tool_projections (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    journal_ref text NOT NULL CHECK (btrim(journal_ref)<>''),
    preparation_ref text,
    program_event_id text,
    fact_ref text NOT NULL CHECK (btrim(fact_ref)<>''),
    key_data text NOT NULL CHECK (btrim(key_data)<>''),
    projection_data text NOT NULL CHECK (btrim(projection_data)<>''),
    occurred_at_epoch_second bigint NOT NULL,
    occurred_at_nano integer NOT NULL CHECK (occurred_at_nano BETWEEN 0 AND 999999999),
    PRIMARY KEY(tenant_id,journal_ref,fact_ref),
    CHECK ((preparation_ref IS NOT NULL AND preparation_ref=journal_ref AND program_event_id IS NULL)
        OR (program_event_id IS NOT NULL AND program_event_id=journal_ref AND preparation_ref IS NULL)),
    FOREIGN KEY(tenant_id,run_id,preparation_ref)
        REFERENCES public.agent_runtime_session_action_preparations(tenant_id,run_id,journal_ref),
    FOREIGN KEY(tenant_id,run_id,program_event_id)
        REFERENCES public.agent_runtime_session_events(tenant_id,run_id,event_id),
    FOREIGN KEY(tenant_id,fact_ref) REFERENCES public.agent_runtime_action_attempt_facts(tenant_id,fact_ref),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id)
);
ALTER TABLE public.agent_runtime_session_tool_projections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_tool_projections FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_tool_projections
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_session_tool_projection_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_tool_projection_immutable'; END;
$$;
CREATE TRIGGER session_tool_projection_immutable BEFORE UPDATE OR DELETE
    ON public.agent_runtime_session_tool_projections FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_session_tool_projection_immutable();
COMMENT ON TABLE public.agent_runtime_session_tool_projections IS '与原Tool采用事实绑定的安全反馈投影';
COMMENT ON COLUMN public.agent_runtime_session_tool_projections.tenant_id IS '原Session租户及RLS分区';
COMMENT ON COLUMN public.agent_runtime_session_tool_projections.run_id IS '必须与原动作准备或程序请求事件相同的Run';
COMMENT ON COLUMN public.agent_runtime_session_tool_projections.journal_ref IS '原动作准备或程序请求事件的稳定引用';
COMMENT ON COLUMN public.agent_runtime_session_tool_projections.preparation_ref IS '普通工具来源，外键绑定V122准备，与程序事件来源互斥';
COMMENT ON COLUMN public.agent_runtime_session_tool_projections.program_event_id IS '程序子工具来源，外键绑定同Run的不可变Session事件';
COMMENT ON COLUMN public.agent_runtime_session_tool_projections.fact_ref IS 'V99实际采用的Tool attempt事实';
COMMENT ON COLUMN public.agent_runtime_session_tool_projections.key_data IS '原请求key与采用fact的完整规范编码';
COMMENT ON COLUMN public.agent_runtime_session_tool_projections.projection_data IS '经Core registry验证的FrozenToolResult规范编码';
COMMENT ON COLUMN public.agent_runtime_session_tool_projections.occurred_at_epoch_second IS 'Session事件时间秒部分';
COMMENT ON COLUMN public.agent_runtime_session_tool_projections.occurred_at_nano IS 'Session事件时间纳秒部分';
COMMENT ON FUNCTION public.agent_runtime_session_tool_projection_immutable() IS '禁止覆写或删除Tool安全反馈投影';
