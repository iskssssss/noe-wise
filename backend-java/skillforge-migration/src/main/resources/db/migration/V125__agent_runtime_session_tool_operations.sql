CREATE TABLE public.nw_runtime_session_tool_operations (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    action_id text NOT NULL CHECK (btrim(action_id)<>''),
    invocation_id text NOT NULL CHECK (btrim(invocation_id)<>''),
    attempt_id text,
    retry_revision bigint,
    operation_hash text NOT NULL CHECK (operation_hash~'^[0-9a-f]{64}$'),
    key_data text NOT NULL CHECK (btrim(key_data)<>''),
    command_kind text NOT NULL CHECK (command_kind IN(
        'CLAIM','START','RESULT','RETRY_PENDING','RETRY_CONFIRMED','COMPLETE','FAIL','UNKNOWN')),
    command_data text NOT NULL CHECK (btrim(command_data)<>''),
    PRIMARY KEY(tenant_id,operation_hash),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    CHECK ((command_kind='RESULT')=(attempt_id IS NOT NULL)),
    CHECK ((command_kind IN('RETRY_PENDING','RETRY_CONFIRMED'))=(retry_revision IS NOT NULL))
);
CREATE UNIQUE INDEX uq_agent_runtime_session_tool_result_attempt
    ON public.nw_runtime_session_tool_operations(tenant_id,run_id,action_id,invocation_id,attempt_id)
    WHERE command_kind='RESULT';
CREATE UNIQUE INDEX uq_agent_runtime_session_tool_retry_generation_kind
    ON public.nw_runtime_session_tool_operations(
        tenant_id,run_id,action_id,invocation_id,retry_revision,command_kind)
    WHERE command_kind IN('RETRY_PENDING','RETRY_CONFIRMED');
CREATE INDEX ix_agent_runtime_session_tool_retry_latest
    ON public.nw_runtime_session_tool_operations(
        tenant_id,run_id,action_id,invocation_id,retry_revision DESC)
    WHERE command_kind IN('RETRY_PENDING','RETRY_CONFIRMED');

CREATE TABLE public.nw_runtime_session_tool_consumptions (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    consume_operation_id text NOT NULL CHECK (btrim(consume_operation_id)<>''),
    scope_data text NOT NULL CHECK (btrim(scope_data)<>''),
    proof_data text NOT NULL CHECK (btrim(proof_data)<>''),
    PRIMARY KEY(tenant_id,consume_operation_id),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id)
);

ALTER TABLE public.nw_runtime_session_tool_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_tool_operations FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_tool_consumptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_tool_consumptions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_session_tool_operations
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE POLICY tenant_isolation ON public.nw_runtime_session_tool_consumptions
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));

CREATE FUNCTION public.agent_runtime_session_tool_operation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_tool_operation_immutable'; END;
$$;
CREATE TRIGGER session_tool_operation_immutable BEFORE UPDATE OR DELETE
    ON public.nw_runtime_session_tool_operations FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_session_tool_operation_immutable();
CREATE TRIGGER session_tool_consumption_immutable BEFORE UPDATE OR DELETE
    ON public.nw_runtime_session_tool_consumptions FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_session_tool_operation_immutable();

COMMENT ON TABLE public.nw_runtime_session_tool_operations IS 'Tool claim、start、分attempt结果、分代重试与terminal原命令journal';
COMMENT ON TABLE public.nw_runtime_session_tool_consumptions IS '每次Tool permit消费尝试的无bearer证明登记';
COMMENT ON COLUMN public.nw_runtime_session_tool_operations.tenant_id IS '原Run租户及RLS分区';
COMMENT ON COLUMN public.nw_runtime_session_tool_operations.run_id IS '原已提交Session Run';
COMMENT ON COLUMN public.nw_runtime_session_tool_operations.action_id IS 'Tool逻辑Action；claim journal允许早于V99 Action行';
COMMENT ON COLUMN public.nw_runtime_session_tool_operations.invocation_id IS 'Tool逻辑调用身份';
COMMENT ON COLUMN public.nw_runtime_session_tool_operations.attempt_id IS 'RESULT所属V99 attempt；非RESULT为空';
COMMENT ON COLUMN public.nw_runtime_session_tool_operations.retry_revision IS '重试代开始前的Action revision；非重试命令为空';
COMMENT ON COLUMN public.nw_runtime_session_tool_operations.operation_hash IS '完整强类型operation key的SHA-256';
COMMENT ON COLUMN public.nw_runtime_session_tool_operations.key_data IS '作用域、命令类别和分代key的规范编码';
COMMENT ON COLUMN public.nw_runtime_session_tool_operations.command_kind IS '原claim、start、result、retry或终态命令类别';
COMMENT ON COLUMN public.nw_runtime_session_tool_operations.command_data IS '强类型原命令规范编码；不含bearer原值';
COMMENT ON INDEX public.uq_agent_runtime_session_tool_result_attempt IS '每个Tool attempt只冻结一个Provider结果';
COMMENT ON INDEX public.uq_agent_runtime_session_tool_retry_generation_kind IS '每个Tool重试代分别只冻结一条pending与confirmed';
COMMENT ON INDEX public.ix_agent_runtime_session_tool_retry_latest IS '按Action revision定位最新Tool重试代';
COMMENT ON COLUMN public.nw_runtime_session_tool_consumptions.tenant_id IS '原Run租户及RLS分区';
COMMENT ON COLUMN public.nw_runtime_session_tool_consumptions.run_id IS 'permit所属的原已提交Session Run';
COMMENT ON COLUMN public.nw_runtime_session_tool_consumptions.consume_operation_id IS '每次消费尝试的新操作身份';
COMMENT ON COLUMN public.nw_runtime_session_tool_consumptions.scope_data IS 'Run、Action和invocation作用域的规范编码';
COMMENT ON COLUMN public.nw_runtime_session_tool_consumptions.proof_data IS 'intent、STARTED事实与不可逆bearer摘要，不含bearer原值';
COMMENT ON FUNCTION public.agent_runtime_session_tool_operation_immutable() IS '禁止覆写或删除Tool原命令与消费登记';
