CREATE TABLE public.nw_runtime_session_workflow_resumes (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    run_id text NOT NULL,
    input_id varchar(160) NOT NULL,
    journal_ref text NOT NULL CHECK (btrim(journal_ref)<>''),
    original_journal_ref text NOT NULL CHECK (btrim(original_journal_ref)<>''),
    key_data text NOT NULL CHECK (btrim(key_data)<>''),
    request_data text NOT NULL CHECK (btrim(request_data)<>''),
    frozen_at_epoch_second bigint NOT NULL,
    frozen_at_nano integer NOT NULL CHECK (frozen_at_nano BETWEEN 0 AND 999999999),
    PRIMARY KEY(tenant_id,run_id,journal_ref),
    UNIQUE(tenant_id,run_id,input_id),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    FOREIGN KEY(tenant_id,agent_id,session_id,end_user_id,input_id)
        REFERENCES public.agent_runtime_input_ledger(tenant_id,agent_id,session_id,end_user_id,input_id),
    FOREIGN KEY(tenant_id,run_id,original_journal_ref)
        REFERENCES public.agent_runtime_session_action_preparations(tenant_id,run_id,journal_ref)
);
ALTER TABLE public.nw_runtime_session_workflow_resumes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_workflow_resumes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_session_workflow_resumes
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.assert_agent_runtime_session_workflow_resume_sources()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_input_ledger i
         WHERE i.tenant_id=NEW.tenant_id AND i.agent_id=NEW.agent_id
           AND i.session_id=NEW.session_id AND i.end_user_id=NEW.end_user_id
           AND i.input_id=NEW.input_id AND i.run_id=NEW.run_id
           AND i.decision_kind='ACCEPTED_EXISTING_RUN' AND i.receipt_status='ACCEPTED') THEN
        RAISE EXCEPTION 'session workflow resume requires accepted ExistingRun input';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_session_action_preparations p
         WHERE p.tenant_id=NEW.tenant_id AND p.run_id=NEW.run_id
           AND p.journal_ref=NEW.original_journal_ref AND p.preparation_kind='WORKFLOW') THEN
        RAISE EXCEPTION 'session workflow resume requires original Workflow preparation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER agent_runtime_session_workflow_resume_sources
    AFTER INSERT ON public.nw_runtime_session_workflow_resumes
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_session_workflow_resume_sources();
CREATE FUNCTION public.agent_runtime_session_workflow_resume_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_workflow_resume_immutable'; END;
$$;
CREATE TRIGGER session_workflow_resume_immutable BEFORE UPDATE OR DELETE
    ON public.nw_runtime_session_workflow_resumes FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_session_workflow_resume_immutable();
COMMENT ON TABLE public.nw_runtime_session_workflow_resumes IS 'ExistingRun 回复、原 Workflow 准备与恢复调用的不可变强类型 journal';
COMMENT ON COLUMN public.nw_runtime_session_workflow_resumes.input_id IS 'V95 已受理 ExistingRun 回复输入';
COMMENT ON COLUMN public.nw_runtime_session_workflow_resumes.journal_ref IS '本次 resume request 的稳定 Session 引用';
COMMENT ON COLUMN public.nw_runtime_session_workflow_resumes.original_journal_ref IS 'V122 原 dispatch Workflow 准备引用';
COMMENT ON COLUMN public.nw_runtime_session_workflow_resumes.request_data IS '完整 WorkflowResumePreparation、输入事件 ACK 与冻结时间';
COMMENT ON FUNCTION public.assert_agent_runtime_session_workflow_resume_sources() IS '约束 resume 请求同时来源于 V95 ExistingRun 输入和 V122 Workflow 准备';
COMMENT ON FUNCTION public.agent_runtime_session_workflow_resume_immutable() IS '禁止覆写或删除 Workflow resume 请求';
