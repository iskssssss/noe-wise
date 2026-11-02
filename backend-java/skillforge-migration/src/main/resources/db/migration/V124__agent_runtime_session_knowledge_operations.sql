CREATE TABLE public.nw_runtime_session_knowledge_operations (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    action_id text NOT NULL CHECK (btrim(action_id)<>''),
    invocation_id text NOT NULL CHECK (btrim(invocation_id)<>''),
    operation_hash text NOT NULL CHECK (operation_hash~'^[0-9a-f]{64}$'),
    key_data text NOT NULL CHECK (btrim(key_data)<>''),
    command_kind text NOT NULL CHECK (command_kind IN('CLAIM','START','RESULT','COMPLETE','FAIL','UNKNOWN')),
    command_data text NOT NULL CHECK (btrim(command_data)<>''),
    PRIMARY KEY(tenant_id,operation_hash),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id)
);
CREATE UNIQUE INDEX uq_agent_runtime_session_knowledge_result_scope
    ON public.nw_runtime_session_knowledge_operations(tenant_id,run_id,action_id,invocation_id)
    WHERE command_kind='RESULT';
CREATE TABLE public.nw_runtime_session_knowledge_consumptions (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    consume_operation_id text NOT NULL CHECK (btrim(consume_operation_id)<>''),
    scope_data text NOT NULL CHECK (btrim(scope_data)<>''),
    proof_data text NOT NULL CHECK (btrim(proof_data)<>''),
    PRIMARY KEY(tenant_id,consume_operation_id),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id)
);
ALTER TABLE public.nw_runtime_session_knowledge_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_knowledge_operations FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_knowledge_consumptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_knowledge_consumptions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_session_knowledge_operations
    USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE POLICY tenant_isolation ON public.nw_runtime_session_knowledge_consumptions
    USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_session_knowledge_operation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_knowledge_operation_immutable'; END;
$$;
CREATE TRIGGER session_knowledge_operation_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_session_knowledge_operations
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_knowledge_operation_immutable();
CREATE TRIGGER session_knowledge_consumption_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_session_knowledge_consumptions
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_knowledge_operation_immutable();
COMMENT ON TABLE public.nw_runtime_session_knowledge_operations IS 'Knowledge claim、start、result与terminal原命令journal';
COMMENT ON TABLE public.nw_runtime_session_knowledge_consumptions IS '每次真实permit消费尝试的无bearer证明登记';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_operations.tenant_id IS '原Run租户及RLS分区';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_operations.run_id IS '原已提交Session Run';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_operations.action_id IS 'Knowledge逻辑Action；claim journal允许早于V99 Action行';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_operations.invocation_id IS 'Knowledge逻辑调用身份';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_operations.operation_hash IS '完整强类型operation key的SHA-256';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_operations.key_data IS 'Run、Action、invocation、命令类别和operation key的规范编码';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_operations.command_kind IS '原claim、start、result或终态命令类别';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_operations.command_data IS '强类型原命令规范编码；不含bearer原值';
COMMENT ON INDEX public.uq_agent_runtime_session_knowledge_result_scope IS '每个Knowledge调用作用域只冻结一个Provider结果，供崩溃恢复精确定位';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_consumptions.tenant_id IS '原Run租户及RLS分区';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_consumptions.run_id IS 'permit所属的原已提交Session Run';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_consumptions.consume_operation_id IS '每次消费尝试新建且不可恢复调用权的操作身份';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_consumptions.scope_data IS 'Run、Action和invocation作用域的规范编码';
COMMENT ON COLUMN public.nw_runtime_session_knowledge_consumptions.proof_data IS 'intent、STARTED事实与不可逆bearer摘要，不含bearer原值';
COMMENT ON FUNCTION public.agent_runtime_session_knowledge_operation_immutable() IS '禁止覆写或删除Knowledge原命令与消费登记';
