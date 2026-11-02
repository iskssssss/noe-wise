-- Provider入口提交记录与原Execution账本同事务形成；BLOCKED只证明一个原EXECUTE attempt不可再提交。
CREATE TABLE public.nw_runtime_workflow_invocations (
    tenant_id text NOT NULL CHECK (btrim(tenant_id)<>''),
    action_id text NOT NULL CHECK (btrim(action_id)<>''),
    invocation_id text NOT NULL CHECK (btrim(invocation_id)<>''),
    attempt_id text NOT NULL CHECK (btrim(attempt_id)<>''),
    execution_id text NOT NULL CHECK (btrim(execution_id)<>''),
    call_kind text NOT NULL CHECK (call_kind IN ('WORKFLOW_DISPATCH','WORKFLOW_RESUME','WORKFLOW_CANCEL')),
    call_fingerprint text NOT NULL CHECK (btrim(call_fingerprint)<>''),
    owner_data text NOT NULL CHECK (btrim(owner_data)<>''),
    snapshot_data text NOT NULL CHECK (btrim(snapshot_data)<>''),
    target_data text NOT NULL CHECK (btrim(target_data)<>''),
    call_data text NOT NULL CHECK (btrim(call_data)<>''),
    status text NOT NULL CHECK (status IN ('COMMITTED','BLOCKED')),
    command_kind text,
    command_data text,
    output_data text,
    barrier_ref text,
    reconciliation_ref text,
    CONSTRAINT skillforge_runtime_workflow_invocations_pkey PRIMARY KEY(tenant_id,action_id,invocation_id,attempt_id),
    CONSTRAINT workflow_invocation_shape CHECK (
        (status='COMMITTED' AND command_kind IS NOT NULL AND command_data IS NOT NULL
         AND btrim(command_data)<>'' AND output_data IS NOT NULL AND btrim(output_data)<>''
         AND barrier_ref IS NULL AND reconciliation_ref IS NULL
         AND ((call_kind='WORKFLOW_DISPATCH' AND command_kind='CREATE')
           OR (call_kind='WORKFLOW_RESUME' AND command_kind='RESUME')
           OR (call_kind='WORKFLOW_CANCEL' AND command_kind='CANCEL')))
        OR (status='BLOCKED' AND command_kind IS NULL AND command_data IS NULL AND output_data IS NULL
            AND barrier_ref IS NOT NULL AND btrim(barrier_ref)<>''
            AND reconciliation_ref IS NOT NULL AND btrim(reconciliation_ref)<>''))
);
CREATE UNIQUE INDEX workflow_invocation_dispatch_binding
    ON public.nw_runtime_workflow_invocations(tenant_id,execution_id)
    WHERE call_kind='WORKFLOW_DISPATCH' AND status='COMMITTED';
ALTER TABLE public.nw_runtime_workflow_invocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_workflow_invocations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_workflow_invocations
    USING (tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK (tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION public.agent_runtime_workflow_invocation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'workflow_invocation_immutable';
END;
$$;
CREATE TRIGGER workflow_invocation_immutable BEFORE UPDATE OR DELETE ON public.nw_runtime_workflow_invocations
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_workflow_invocation_immutable();
CREATE FUNCTION public.agent_runtime_workflow_invocation_committed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='COMMITTED' AND NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_execution_mutations m
        WHERE m.tenant_id=NEW.tenant_id AND m.execution_id=NEW.execution_id
          AND m.mutation_kind=NEW.command_kind AND m.command_data=NEW.command_data AND m.output_data=NEW.output_data
    ) THEN
        RAISE EXCEPTION 'workflow_invocation_execution_commit_missing';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER workflow_invocation_committed AFTER INSERT ON public.nw_runtime_workflow_invocations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_workflow_invocation_committed();
COMMENT ON TABLE public.nw_runtime_workflow_invocations IS '不可变Workflow入口提交与原EXECUTE attempt屏障；不运行Engine、不替代Action授权';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.tenant_id IS '原Action与Execution所属租户，强制RLS';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.action_id IS '原Action身份';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.invocation_id IS '原Action invocation身份';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.attempt_id IS '实际原EXECUTE attempt身份，绝不使用RECONCILE attempt代替';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.execution_id IS '冻结Execution引用，BLOCKED时允许尚不存在';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.call_kind IS '原Workflow调度、恢复或取消调用种类';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.call_fingerprint IS '原EXECUTE调用指纹';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.owner_data IS '规范编码的完整原RunIdentity';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.snapshot_data IS '规范编码的完整CapabilitySnapshotRef';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.target_data IS '规范编码的完整FrozenWorkflowTarget，包含挂载与实例revision';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.call_data IS '规范编码的原ActionCallSnapshot，复用严格codec，不存bearer';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.status IS 'COMMITTED为入口已提交；BLOCKED为原attempt永久禁止提交';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.command_kind IS '原Execution Ledger mutation种类';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.command_data IS '原Execution命令规范编码，与原账本逐字一致';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.output_data IS '入口首次提交时ExecutionRecord，与原账本逐字一致';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.barrier_ref IS '与原attempt绑定的持久屏障证明引用';
COMMENT ON COLUMN public.nw_runtime_workflow_invocations.reconciliation_ref IS '形成屏障时的核对结果引用';
COMMENT ON CONSTRAINT workflow_invocation_shape ON public.nw_runtime_workflow_invocations IS '提交及屏障互斥，并精确限定调用与命令矩阵';
COMMENT ON INDEX public.skillforge_runtime_workflow_invocations_pkey IS '同租户Action invocation原attempt唯一，屏障和提交不能并存';
COMMENT ON INDEX public.workflow_invocation_dispatch_binding IS '每个Execution只允许一个成功调度入口冻结完整身份';
COMMENT ON FUNCTION public.agent_runtime_workflow_invocation_immutable() IS '禁止更新或删除已提交的入口及屏障';
COMMENT ON FUNCTION public.agent_runtime_workflow_invocation_committed() IS '事务结束时强制入口提交匹配原Execution mutation，禁止只有Provider结果没有状态提交';
