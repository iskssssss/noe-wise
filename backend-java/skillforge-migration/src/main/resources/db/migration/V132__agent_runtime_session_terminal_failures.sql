CREATE TABLE public.nw_runtime_session_terminal_failures (
    tenant_id text NOT NULL,
    agent_id text NOT NULL,
    session_id text NOT NULL,
    end_user_id bigint NOT NULL,
    run_id text NOT NULL,
    result_ref text NOT NULL,
    reason_code text NOT NULL,
    source_run_revision bigint NOT NULL,
    outcome_data text NOT NULL,
    source_run_data text NOT NULL,
    frozen_at_epoch_second bigint NOT NULL,
    frozen_at_nano integer NOT NULL,
    PRIMARY KEY(tenant_id,run_id,result_ref),
    FOREIGN KEY(tenant_id,run_id)
        REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    CONSTRAINT ck_agent_runtime_session_terminal_failure_identity CHECK (
        btrim(tenant_id)<>'' AND btrim(agent_id)<>'' AND btrim(session_id)<>''
        AND end_user_id>0 AND btrim(run_id)<>''),
    CONSTRAINT ck_agent_runtime_session_terminal_failure_value CHECK (
        btrim(result_ref)<>'' AND btrim(reason_code)<>'' AND source_run_revision>0
        AND btrim(outcome_data)<>'' AND btrim(source_run_data)<>''),
    CONSTRAINT ck_agent_runtime_session_terminal_failure_time CHECK (
        frozen_at_nano BETWEEN 0 AND 999999999)
);

ALTER TABLE public.nw_runtime_session_terminal_failures ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_terminal_failures FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_session_terminal_failures
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));

CREATE FUNCTION public.guard_agent_runtime_session_terminal_failure()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'session_terminal_failure_immutable';
END;
$$;
CREATE TRIGGER agent_runtime_session_terminal_failure_immutable
    BEFORE UPDATE OR DELETE ON public.nw_runtime_session_terminal_failures
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_terminal_failure();

CREATE FUNCTION public.assert_agent_runtime_session_terminal_failure()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_runs r
         WHERE r.tenant_id=NEW.tenant_id AND r.agent_id=NEW.agent_id
           AND r.session_id=NEW.session_id AND r.end_user_id=NEW.end_user_id
           AND r.run_id=NEW.run_id AND r.run_revision=NEW.source_run_revision
           AND r.cancel_request_id IS NULL
           AND r.cancel_requested_epoch_second IS NULL AND r.cancel_requested_nano IS NULL
           AND r.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED')
           AND r.record_data=NEW.source_run_data) THEN
        RAISE EXCEPTION 'session terminal failure source mismatch';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER agent_runtime_session_terminal_failure_sources
    AFTER INSERT ON public.nw_runtime_session_terminal_failures
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_session_terminal_failure();

COMMENT ON TABLE public.nw_runtime_session_terminal_failures IS 'Run 技术失败终结前冻结的原 Run、稳定原因与结果事实';
COMMENT ON COLUMN public.nw_runtime_session_terminal_failures.result_ref IS '技术失败诊断结果引用，也是同 Run 内的失败事实键';
COMMENT ON COLUMN public.nw_runtime_session_terminal_failures.reason_code IS '冻结的稳定技术失败原因码';
COMMENT ON COLUMN public.nw_runtime_session_terminal_failures.source_run_revision IS '失败冻结时原 Run 的精确 revision';
COMMENT ON COLUMN public.nw_runtime_session_terminal_failures.outcome_data IS 'RunOutcome.Failed 的 adapter-private 规范编码';
COMMENT ON COLUMN public.nw_runtime_session_terminal_failures.source_run_data IS '失败终结前精确 RunRecord 的 adapter-private 规范编码';
COMMENT ON FUNCTION public.guard_agent_runtime_session_terminal_failure() IS '技术失败终结事实禁止改写或删除';
COMMENT ON FUNCTION public.assert_agent_runtime_session_terminal_failure() IS '首次冻结必须引用未取消且非终态的精确原 Run';
