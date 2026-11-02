-- Workflow 节点外调在当前 Execution lease 下首次冻结的不可变准备；不引用尚未 claim 的 Action。

CREATE TABLE public.nw_runtime_workflow_node_preparations (
    tenant_id                       text NOT NULL,
    execution_id                    varchar(160) NOT NULL,
    session_id                      text NOT NULL,
    target_kind                     text NOT NULL,
    target_id                       text NOT NULL,
    agent_id                        text NOT NULL,
    end_user_id                     bigint NOT NULL,
    run_id                          varchar(160) NOT NULL,
    node_id                         text NOT NULL,
    visit                           integer NOT NULL,
    call_index                      integer NOT NULL,
    call_kind                       varchar(32) NOT NULL,
    preparation_ref                 text NOT NULL,
    definition_revision             bigint NOT NULL,
    installation_revision           bigint NOT NULL,
    prepared_execution_revision     bigint NOT NULL,
    source_checkpoint_fingerprint   varchar(64) NOT NULL,
    record_fingerprint              varchar(64) NOT NULL,
    call_fingerprint                varchar(64) NOT NULL,
    record_data                     text NOT NULL,
    execute_call_data               text NOT NULL,
    prepared_at                     timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_runtime_workflow_node_preparations_pkey
        PRIMARY KEY (
            tenant_id, execution_id, node_id, visit, call_index, call_kind),
    CONSTRAINT agent_runtime_workflow_node_preparation_execution_fkey
        FOREIGN KEY (
            tenant_id, execution_id, target_kind, target_id, agent_id,
            session_id, end_user_id, run_id)
        REFERENCES public.agent_runtime_executions
            (tenant_id, execution_id, target_kind, target_id, agent_id,
             session_id, end_user_id, run_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_workflow_node_preparation_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(execution_id) <> ''
        AND btrim(session_id) <> ''
        AND target_kind IN ('AGENT', 'TEAM') AND btrim(target_id) <> ''
        AND btrim(agent_id) <> ''
        AND end_user_id > 0 AND btrim(run_id) <> ''
        AND btrim(node_id) <> '' AND visit > 0 AND call_index > 0),
    CONSTRAINT ck_agent_runtime_workflow_node_preparation_call_kind CHECK (
        call_kind IN (
            'MODEL_GENERATE', 'MODEL_STREAM', 'TOOL_INVOKE',
            'WORKFLOW_DISPATCH', 'WORKFLOW_RESUME',
            'WORKFLOW_RECOVER_RESULT', 'WORKFLOW_CANCEL',
            'KNOWLEDGE_EXECUTE')),
    CONSTRAINT ck_agent_runtime_workflow_node_preparation_source CHECK (
        btrim(preparation_ref) <> ''
        AND definition_revision > 0
        AND installation_revision > 0
        AND prepared_execution_revision > 0),
    CONSTRAINT ck_agent_runtime_workflow_node_preparation_codec CHECK (
        source_checkpoint_fingerprint ~ '^[0-9a-f]{64}$'
        AND record_fingerprint ~ '^[0-9a-f]{64}$'
        AND call_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(record_data) <> ''
        AND btrim(execute_call_data) <> '')
);

ALTER TABLE public.nw_runtime_workflow_node_preparations
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_workflow_node_preparations
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_workflow_node_preparation_tenant_isolation
    ON public.nw_runtime_workflow_node_preparations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

CREATE FUNCTION public.agent_runtime_workflow_node_preparation_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'workflow_node_preparation_immutable';
END;
$$;

CREATE TRIGGER agent_runtime_workflow_node_preparation_immutable
    BEFORE UPDATE OR DELETE
    ON public.nw_runtime_workflow_node_preparations
    FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_workflow_node_preparation_immutable();

COMMENT ON TABLE public.nw_runtime_workflow_node_preparations IS
    'Workflow 节点外调在 Execution lease 下冻结的不可变准备；不授予 Action claim 或 Provider 调用权';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.tenant_id IS
    'Execution 租户及强制 RLS 隔离键';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.execution_id IS
    '准备所属 Workflow Execution 标识';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.session_id IS
    '冻结并由 Execution 复合外键核验的 Session owner';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.target_kind IS
    '冻结并由 Execution 复合外键核验的 Session target 类别';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.target_id IS
    '冻结并由 Execution 复合外键核验的 Session target 标识';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.agent_id IS
    '冻结并由 Execution 复合外键核验的执行 Agent';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.end_user_id IS
    '冻结并由 Execution 复合外键核验的终端用户';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.run_id IS
    '冻结并由 Execution 复合外键核验的 façade Run';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.node_id IS
    'Workflow 定义中的稳定节点标识';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.visit IS
    '本 Execution 对节点的正整数访问序号';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.call_index IS
    '本次节点访问内的正整数调用序号';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.call_kind IS
    '进入 Action Ledger 的规范 PortalCallKind';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.preparation_ref IS
    '宿主生成并由完整来源回调核验的稳定准备引用';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.definition_revision IS
    '准备绑定的精确 Workflow 定义 revision';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.installation_revision IS
    '准备绑定的精确租户安装 revision';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.prepared_execution_revision IS
    '首次准备时持有行锁核验的 Execution revision';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.source_checkpoint_fingerprint IS
    '首次准备时与 Execution 当前 checkpoint 全值相等的规范 SHA-256';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.record_fingerprint IS
    '完整准备记录规范字节的 lowercase SHA-256';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.call_fingerprint IS
    '完整 ActionCallSnapshot 规范字节的 lowercase SHA-256';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.record_data IS
    '含来源、完整调用、checkpoint、策略、deadline 与重试模式的版本化规范私有编码';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.execute_call_data IS
    '单独保存并与完整准备反校验的 ActionCallSnapshot 规范私有编码';
COMMENT ON COLUMN public.nw_runtime_workflow_node_preparations.prepared_at IS
    '首次插入准备的数据库时间，仅用于审计排序';
COMMENT ON FUNCTION public.agent_runtime_workflow_node_preparation_immutable() IS
    '禁止更新或删除原 Workflow 节点外调准备';
