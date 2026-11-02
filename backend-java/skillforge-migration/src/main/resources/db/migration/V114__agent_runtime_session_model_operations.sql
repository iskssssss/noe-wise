CREATE TABLE public.nw_runtime_session_model_operations (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    action_id text NOT NULL CHECK (btrim(action_id)<>''),
    invocation_id text NOT NULL CHECK (btrim(invocation_id)<>''),
    operation_hash text NOT NULL CHECK (operation_hash ~ '^[a-f0-9]{64}$'),
    key_data text NOT NULL CHECK (btrim(key_data)<>''),
    command_kind text NOT NULL CHECK (command_kind IN('CLAIM','START','RESULT','COMPLETE','FAIL','UNKNOWN')),
    command_data text NOT NULL CHECK (btrim(command_data)<>''),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_runtime_session_model_operations_pkey PRIMARY KEY(tenant_id,operation_hash),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id)
);
CREATE TABLE public.nw_runtime_session_model_consumes (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    consume_operation_id text NOT NULL CHECK (consume_operation_id LIKE 'model-consume:%'),
    scope_data text NOT NULL CHECK (btrim(scope_data)<>''),
    proof_data text NOT NULL CHECK (btrim(proof_data)<>''),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_runtime_session_model_consumes_pkey PRIMARY KEY(tenant_id,consume_operation_id),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id)
);
ALTER TABLE public.nw_runtime_session_model_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_model_operations FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_model_consumes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_model_consumes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_session_model_operations USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE POLICY tenant_isolation ON public.nw_runtime_session_model_consumes USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_session_model_operation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_model_operation_immutable'; END;
$$;
CREATE TRIGGER session_model_operation_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_session_model_operations FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_model_operation_immutable();
CREATE TRIGGER session_model_consume_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_session_model_consumes FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_model_operation_immutable();
COMMENT ON TABLE public.nw_runtime_session_model_operations IS '模型六类原命令不可变journal，不恢复网络许可';
COMMENT ON COLUMN public.nw_runtime_session_model_operations.tenant_id IS '原Run租户';
COMMENT ON COLUMN public.nw_runtime_session_model_operations.run_id IS '原Run标识';
COMMENT ON COLUMN public.nw_runtime_session_model_operations.action_id IS '冻结模型Action标识，首次CLAIM前允许尚无Action行';
COMMENT ON COLUMN public.nw_runtime_session_model_operations.invocation_id IS '原逻辑调用标识';
COMMENT ON COLUMN public.nw_runtime_session_model_operations.operation_hash IS '完整scope/kind/key规范数据的域分离SHA256，读取仍全值比较';
COMMENT ON COLUMN public.nw_runtime_session_model_operations.key_data IS '完整RunIdentity与原操作键，generation/lease/winner由Session生成';
COMMENT ON COLUMN public.nw_runtime_session_model_operations.command_kind IS '六类封闭命令分支';
COMMENT ON COLUMN public.nw_runtime_session_model_operations.command_data IS '完整原命令安全投影；RESULT只保留intent、STARTED、摘要与结果，不保存bearer/permit';
COMMENT ON COLUMN public.nw_runtime_session_model_operations.recorded_at IS '首次冻结数据库时间';
COMMENT ON TABLE public.nw_runtime_session_model_consumes IS '每次guard调用的新consume操作登记，不重放旧Consumed写权';
COMMENT ON COLUMN public.nw_runtime_session_model_consumes.tenant_id IS '原Run租户';
COMMENT ON COLUMN public.nw_runtime_session_model_consumes.run_id IS '原Run标识';
COMMENT ON COLUMN public.nw_runtime_session_model_consumes.consume_operation_id IS '每次调用新生成的消费操作ID';
COMMENT ON COLUMN public.nw_runtime_session_model_consumes.scope_data IS '完整Run/Action/invocation安全作用域';
COMMENT ON COLUMN public.nw_runtime_session_model_consumes.proof_data IS '原issued intent与STARTED全值及不可逆摘要，不含bearer';
COMMENT ON COLUMN public.nw_runtime_session_model_consumes.recorded_at IS '本次登记数据库时间';
COMMENT ON INDEX public.skillforge_runtime_session_model_operations_pkey IS '每tenant原操作键唯一';
COMMENT ON INDEX public.skillforge_runtime_session_model_consumes_pkey IS '每tenant消费操作ID唯一';
COMMENT ON FUNCTION public.agent_runtime_session_model_operation_immutable() IS '禁止覆写或删除原模型命令及消费登记';
