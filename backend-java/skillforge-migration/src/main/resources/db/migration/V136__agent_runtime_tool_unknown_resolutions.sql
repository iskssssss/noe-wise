-- Tool OUTCOME_UNKNOWN 的补充证据与采用事实。原 V125 RESULT 保持唯一且不可变。
ALTER TABLE public.nw_runtime_session_tool_operations
    DROP CONSTRAINT nw_runtime_session_tool_operations_command_kind_check;
ALTER TABLE public.nw_runtime_session_tool_operations
    ADD CONSTRAINT skillforge_runtime_session_tool_operations_command_kind_check
    CHECK (command_kind IN(
        'CLAIM','KNOWN_CLAIM','START','RESULT','RETRY_PENDING','RETRY_CONFIRMED','COMPLETE','FAIL','UNKNOWN'));
ALTER TABLE public.nw_runtime_session_tool_operations
    DROP CONSTRAINT nw_runtime_session_tool_operations_check1;
ALTER TABLE public.nw_runtime_session_tool_operations
    ADD CONSTRAINT skillforge_runtime_session_tool_operations_revision_shape_check
    CHECK ((command_kind IN('KNOWN_CLAIM','RETRY_PENDING','RETRY_CONFIRMED'))
        =(retry_revision IS NOT NULL));
COMMENT ON COLUMN public.nw_runtime_session_tool_operations.retry_revision IS
    '重试代开始前或KNOWN_CLAIM前的Action revision；其他命令为空';

CREATE TABLE public.nw_runtime_tool_unknown_evidence (
    tenant_id text NOT NULL,
    evidence_id text NOT NULL CHECK (btrim(evidence_id)<>''),
    run_id text NOT NULL,
    action_id text NOT NULL CHECK (btrim(action_id)<>''),
    invocation_id text NOT NULL CHECK (btrim(invocation_id)<>''),
    attempt_id text NOT NULL CHECK (btrim(attempt_id)<>''),
    unknown_fact_ref text NOT NULL CHECK (btrim(unknown_fact_ref)<>''),
    original_result_operation_id text NOT NULL CHECK (btrim(original_result_operation_id)<>''),
    callback_id text NOT NULL CHECK (btrim(callback_id)<>''),
    delivery_id text NOT NULL CHECK (btrim(delivery_id)<>''),
    registration_revision bigint NOT NULL CHECK (registration_revision>0),
    source_fingerprint text NOT NULL CHECK (source_fingerprint~'^[0-9a-f]{64}$'),
    result_operation_id text NOT NULL CHECK (btrim(result_operation_id)<>''),
    evidence_data text NOT NULL CHECK (btrim(evidence_data)<>''),
    recorded_second bigint NOT NULL,
    recorded_nano integer NOT NULL CHECK (recorded_nano BETWEEN 0 AND 999999999),
    PRIMARY KEY(tenant_id,evidence_id),
    UNIQUE(tenant_id,delivery_id),
    UNIQUE(tenant_id,result_operation_id),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    FOREIGN KEY(tenant_id,action_id,invocation_id)
        REFERENCES public.agent_runtime_actions(tenant_id,action_id,invocation_id),
    FOREIGN KEY(tenant_id,action_id,invocation_id,unknown_fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts(tenant_id,action_id,invocation_id,fact_ref),
    FOREIGN KEY(tenant_id,callback_id)
        REFERENCES public.agent_runtime_callback_registrations(tenant_id,callback_id),
    FOREIGN KEY(tenant_id,delivery_id)
        REFERENCES public.agent_runtime_callback_deliveries(tenant_id,delivery_id)
);

CREATE TABLE public.nw_runtime_tool_unknown_adoptions (
    tenant_id text NOT NULL,
    adoption_id text NOT NULL CHECK (btrim(adoption_id)<>''),
    evidence_id text NOT NULL CHECK (btrim(evidence_id)<>''),
    run_id text NOT NULL,
    action_id text NOT NULL CHECK (btrim(action_id)<>''),
    invocation_id text NOT NULL CHECK (btrim(invocation_id)<>''),
    unknown_fact_ref text NOT NULL CHECK (btrim(unknown_fact_ref)<>''),
    candidate_fact_ref text CHECK (candidate_fact_ref IS NULL OR btrim(candidate_fact_ref)<>''),
    winner_fact_ref text NOT NULL CHECK (btrim(winner_fact_ref)<>''),
    disposition text NOT NULL CHECK (disposition IN('WINNER','EQUIVALENT','CONFLICT')),
    adoption_data text NOT NULL CHECK (btrim(adoption_data)<>''),
    adopted_second bigint NOT NULL,
    adopted_nano integer NOT NULL CHECK (adopted_nano BETWEEN 0 AND 999999999),
    PRIMARY KEY(tenant_id,adoption_id),
    UNIQUE(tenant_id,evidence_id),
    FOREIGN KEY(tenant_id,evidence_id)
        REFERENCES public.nw_runtime_tool_unknown_evidence(tenant_id,evidence_id),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    FOREIGN KEY(tenant_id,action_id,invocation_id,unknown_fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts(tenant_id,action_id,invocation_id,fact_ref),
    FOREIGN KEY(tenant_id,action_id,invocation_id,candidate_fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts(tenant_id,action_id,invocation_id,fact_ref),
    FOREIGN KEY(tenant_id,action_id,invocation_id,winner_fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts(tenant_id,action_id,invocation_id,fact_ref)
);

ALTER TABLE public.nw_runtime_tool_unknown_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_tool_unknown_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_tool_unknown_evidence
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
ALTER TABLE public.nw_runtime_tool_unknown_adoptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_tool_unknown_adoptions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_tool_unknown_adoptions
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));

CREATE FUNCTION public.agent_runtime_tool_unknown_resolution_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'tool_unknown_resolution_immutable'; END;
$$;
CREATE TRIGGER tool_unknown_evidence_immutable BEFORE UPDATE OR DELETE
    ON public.nw_runtime_tool_unknown_evidence FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_tool_unknown_resolution_immutable();
CREATE TRIGGER tool_unknown_adoption_immutable BEFORE UPDATE OR DELETE
    ON public.nw_runtime_tool_unknown_adoptions FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_tool_unknown_resolution_immutable();

COMMENT ON TABLE public.nw_runtime_tool_unknown_evidence IS '已消费Tool unknown的签名callback补充证据；不覆盖原V125 RESULT';
COMMENT ON TABLE public.nw_runtime_tool_unknown_adoptions IS '补充证据写入V99后的实际winner判定；只有WINNER/EQUIVALENT可恢复Session';
COMMENT ON COLUMN public.nw_runtime_tool_unknown_evidence.evidence_data IS '完整强类型证据闭包；不含bearer或签名密钥';
COMMENT ON COLUMN public.nw_runtime_tool_unknown_adoptions.candidate_fact_ref IS '首个补充证据实际写入的V99 fact；winner已存在时为空';
COMMENT ON COLUMN public.nw_runtime_tool_unknown_adoptions.adoption_data IS '候选fact、实际winner及判定的强类型不可变快照';
COMMENT ON FUNCTION public.agent_runtime_tool_unknown_resolution_immutable() IS '禁止覆写或删除Tool unknown补充证据与采用事实';
