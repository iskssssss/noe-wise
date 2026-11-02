CREATE TABLE public.nw_runtime_session_workflow_projections (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    action_id text NOT NULL CHECK (btrim(action_id)<>''),
    invocation_id text NOT NULL CHECK (btrim(invocation_id)<>''),
    journal_ref text NOT NULL CHECK (btrim(journal_ref)<>''),
    fact_ref text NOT NULL CHECK (btrim(fact_ref)<>''),
    key_data text NOT NULL CHECK (btrim(key_data)<>''),
    projection_data text NOT NULL CHECK (btrim(projection_data)<>''),
    occurred_at_epoch_second bigint NOT NULL,
    occurred_at_nano integer NOT NULL CHECK (occurred_at_nano BETWEEN 0 AND 999999999),
    PRIMARY KEY(tenant_id,journal_ref,fact_ref),
    FOREIGN KEY(tenant_id,run_id,journal_ref)
        REFERENCES public.agent_runtime_session_action_preparations(tenant_id,run_id,journal_ref),
    FOREIGN KEY(tenant_id,action_id,invocation_id,fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts(tenant_id,action_id,invocation_id,fact_ref),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id)
);
ALTER TABLE public.nw_runtime_session_workflow_projections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_workflow_projections FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_session_workflow_projections
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.assert_agent_runtime_session_workflow_projection_source()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_actions a
         WHERE a.tenant_id=NEW.tenant_id AND a.action_id=NEW.action_id
           AND a.invocation_id=NEW.invocation_id AND a.run_id=NEW.run_id) THEN
        RAISE EXCEPTION 'session workflow projection requires exact action source';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_session_action_preparations p
         WHERE p.tenant_id=NEW.tenant_id AND p.run_id=NEW.run_id
           AND p.journal_ref=NEW.journal_ref AND p.preparation_kind='WORKFLOW') THEN
        RAISE EXCEPTION 'session workflow projection requires workflow preparation source';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER agent_runtime_session_workflow_projection_source
    AFTER INSERT ON public.nw_runtime_session_workflow_projections
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_session_workflow_projection_source();
CREATE FUNCTION public.agent_runtime_session_workflow_projection_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_workflow_projection_immutable'; END;
$$;
CREATE TRIGGER session_workflow_projection_immutable BEFORE UPDATE OR DELETE
    ON public.nw_runtime_session_workflow_projections FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_session_workflow_projection_immutable();
COMMENT ON TABLE public.nw_runtime_session_workflow_projections IS '与原Workflow采用事实绑定的安全反馈投影';
COMMENT ON COLUMN public.nw_runtime_session_workflow_projections.tenant_id IS '原Session租户及RLS分区';
COMMENT ON COLUMN public.nw_runtime_session_workflow_projections.run_id IS '必须与V122准备相同的Run';
COMMENT ON COLUMN public.nw_runtime_session_workflow_projections.action_id IS '必须与采用V99事实相同的Workflow Action';
COMMENT ON COLUMN public.nw_runtime_session_workflow_projections.invocation_id IS '必须与采用V99事实相同的Workflow invocation';
COMMENT ON COLUMN public.nw_runtime_session_workflow_projections.journal_ref IS '与V121原请求相同的稳定引用';
COMMENT ON COLUMN public.nw_runtime_session_workflow_projections.fact_ref IS 'V99实际采用的Workflow attempt事实';
COMMENT ON COLUMN public.nw_runtime_session_workflow_projections.key_data IS '原请求key与采用fact的完整规范编码';
COMMENT ON COLUMN public.nw_runtime_session_workflow_projections.projection_data IS '经Core registry验证的FrozenWorkflowResult规范编码';
COMMENT ON COLUMN public.nw_runtime_session_workflow_projections.occurred_at_epoch_second IS 'Session事件时间秒部分';
COMMENT ON COLUMN public.nw_runtime_session_workflow_projections.occurred_at_nano IS 'Session事件时间纳秒部分';
COMMENT ON FUNCTION public.agent_runtime_session_workflow_projection_immutable() IS '禁止覆写或删除Workflow安全反馈投影';
COMMENT ON FUNCTION public.assert_agent_runtime_session_workflow_projection_source() IS '要求安全反馈投影与原Workflow准备及V99 Action身份严格一致';
