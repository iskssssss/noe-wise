-- 受限 Workflow 模型节点的原 Execution/node/parameters/config 来源。
CREATE TABLE public.nw_runtime_session_workflow_inputs (
    tenant_id text NOT NULL CHECK (btrim(tenant_id)<>''),
    run_id text NOT NULL CHECK (btrim(run_id)<>''),
    execution_id text NOT NULL CHECK (btrim(execution_id)<>''),
    loop_id text NOT NULL CHECK (btrim(loop_id)<>''),
    source_ref text NOT NULL CHECK (btrim(source_ref)<>''),
    input_data text NOT NULL CHECK (btrim(input_data)<>''),
    CONSTRAINT agent_runtime_session_workflow_input_pk PRIMARY KEY(tenant_id,run_id,execution_id,loop_id),
    CONSTRAINT agent_runtime_session_workflow_input_run_fk FOREIGN KEY(tenant_id,run_id)
        REFERENCES public.agent_runtime_runs(tenant_id,run_id) ON DELETE RESTRICT,
    CONSTRAINT agent_runtime_session_workflow_input_execution_fk FOREIGN KEY(tenant_id,execution_id)
        REFERENCES public.agent_runtime_executions(tenant_id,execution_id) ON DELETE RESTRICT
);

CREATE FUNCTION public.guard_agent_runtime_session_workflow_input() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' OR TG_OP='DELETE' THEN RAISE EXCEPTION 'session_workflow_input_immutable'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_runtime_session_workflow_input_guard BEFORE UPDATE OR DELETE ON public.nw_runtime_session_workflow_inputs
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_workflow_input();

ALTER TABLE public.nw_runtime_session_workflow_inputs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_workflow_inputs FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_workflow_input_tenant ON public.nw_runtime_session_workflow_inputs
    USING(tenant_id=COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK(tenant_id=COALESCE(current_setting('app.tenant_id',true),''));

COMMENT ON TABLE public.nw_runtime_session_workflow_inputs IS '受限 Workflow 模型准备采用的不可变原节点输入，不保存 Agent capability/persona/relationship/evidence';
COMMENT ON COLUMN public.nw_runtime_session_workflow_inputs.source_ref IS '原 Workflow 节点输入事实的稳定引用';
COMMENT ON COLUMN public.nw_runtime_session_workflow_inputs.input_data IS '完整 SessionPreparations.WorkflowInput 规范快照';
