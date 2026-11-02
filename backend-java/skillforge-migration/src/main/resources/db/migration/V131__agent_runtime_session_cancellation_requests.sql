CREATE TABLE public.nw_runtime_session_cancellation_requests (
    tenant_id text NOT NULL,
    agent_id text NOT NULL,
    session_id text NOT NULL,
    end_user_id bigint NOT NULL,
    run_id text NOT NULL,
    cancel_request_id varchar(160) NOT NULL,
    expected_revision bigint NOT NULL,
    requester_verification_ref text NOT NULL,
    cancel_event_id text NOT NULL,
    request_data text NOT NULL,
    source_run_data text NOT NULL,
    requester_data text NOT NULL,
    frozen_at_epoch_second bigint NOT NULL,
    frozen_at_nano integer NOT NULL,
    PRIMARY KEY(tenant_id,run_id,cancel_request_id),
    FOREIGN KEY(tenant_id,run_id)
        REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    FOREIGN KEY(requester_verification_ref)
        REFERENCES public.nw_runtime_identity_verifications(verification_ref),
    CONSTRAINT ck_agent_runtime_session_cancellation_identity CHECK (
        btrim(tenant_id)<>'' AND btrim(agent_id)<>'' AND btrim(session_id)<>''
        AND end_user_id>0 AND btrim(run_id)<>'' AND btrim(cancel_request_id)<>''),
    CONSTRAINT ck_agent_runtime_session_cancellation_request CHECK (
        expected_revision>0 AND btrim(requester_verification_ref)<>''
        AND btrim(cancel_event_id)<>'' AND btrim(request_data)<>''
        AND btrim(source_run_data)<>'' AND btrim(requester_data)<>''),
    CONSTRAINT ck_agent_runtime_session_cancellation_time CHECK (
        frozen_at_nano BETWEEN 0 AND 999999999)
);

ALTER TABLE public.nw_runtime_session_cancellation_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_cancellation_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_session_cancellation_requests
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));

CREATE FUNCTION public.guard_agent_runtime_session_cancellation_request()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'session_cancellation_request_immutable';
END;
$$;
CREATE TRIGGER agent_runtime_session_cancellation_request_immutable
    BEFORE UPDATE OR DELETE ON public.nw_runtime_session_cancellation_requests
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_cancellation_request();

CREATE FUNCTION public.assert_agent_runtime_session_cancellation_request()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_runs r
          JOIN public.nw_runtime_identity_verifications v
            ON v.tenant_id=r.tenant_id AND v.verification_ref=NEW.requester_verification_ref
         WHERE r.tenant_id=NEW.tenant_id AND r.agent_id=NEW.agent_id
           AND r.session_id=NEW.session_id AND r.end_user_id=NEW.end_user_id
           AND r.run_id=NEW.run_id AND r.run_revision=NEW.expected_revision
           AND r.cancel_request_id IS NULL
           AND r.cancel_requested_epoch_second IS NULL AND r.cancel_requested_nano IS NULL
           AND r.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED')
           AND r.record_data=NEW.source_run_data) THEN
        RAISE EXCEPTION 'session cancellation source mismatch';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER agent_runtime_session_cancellation_request_sources
    AFTER INSERT ON public.nw_runtime_session_cancellation_requests
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_session_cancellation_request();

COMMENT ON TABLE public.nw_runtime_session_cancellation_requests IS '控制面发送 Run cancel CAS 前冻结的完整原请求、Run 与当前 requester';
COMMENT ON COLUMN public.nw_runtime_session_cancellation_requests.cancel_request_id IS '幂等取消请求标识，与 Run 组成首次发送 journal key';
COMMENT ON COLUMN public.nw_runtime_session_cancellation_requests.expected_revision IS '冻结时原 Run 的精确 revision，也是后续 cancel CAS 输入';
COMMENT ON COLUMN public.nw_runtime_session_cancellation_requests.requester_verification_ref IS '发送前重新授权得到的当前 requester verification 引用';
COMMENT ON COLUMN public.nw_runtime_session_cancellation_requests.cancel_event_id IS '完整取消命令绑定的原 ledger event 标识';
COMMENT ON COLUMN public.nw_runtime_session_cancellation_requests.request_data IS '完整 RequestRunCancel 的 adapter-private 规范编码';
COMMENT ON COLUMN public.nw_runtime_session_cancellation_requests.source_run_data IS '发送前读取的精确原 RunRecord 规范编码';
COMMENT ON COLUMN public.nw_runtime_session_cancellation_requests.requester_data IS '发送前重新授权得到的当前 requester 规范编码';
COMMENT ON FUNCTION public.guard_agent_runtime_session_cancellation_request() IS '取消发送前 journal 禁止改写或删除';
COMMENT ON FUNCTION public.assert_agent_runtime_session_cancellation_request() IS '首次冻结必须引用尚未取消且非终态的精确原 Run revision';
