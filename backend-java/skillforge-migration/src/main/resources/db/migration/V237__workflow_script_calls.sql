-- Workflow 脚本节点的单次提交 journal；DISPATCHED 后只能查询原 taskId 并保存终态。

CREATE TABLE public.agent_runtime_workflow_script_calls (
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
    definition_revision             bigint NOT NULL,
    installation_revision           bigint NOT NULL,
    prepared_execution_revision     bigint NOT NULL,
    task_id                         varchar(64) NOT NULL,
    request_schema_id               text NOT NULL,
    deadline_epoch_second           bigint NOT NULL,
    deadline_nano                   integer NOT NULL,
    source_checkpoint_fingerprint   varchar(64) NOT NULL,
    request_fingerprint             varchar(64) NOT NULL,
    preparation_fingerprint         varchar(64) NOT NULL,
    preparation_data                text NOT NULL,
    state                           varchar(16) NOT NULL DEFAULT 'PREPARED',
    terminal_fingerprint            varchar(64),
    terminal_data                   text,
    prepared_at                     timestamptz NOT NULL DEFAULT clock_timestamp(),
    dispatched_at                   timestamptz,
    terminal_at                     timestamptz,
    CONSTRAINT agent_runtime_workflow_script_calls_pkey
        PRIMARY KEY (
            tenant_id, execution_id, node_id, visit, call_index),
    CONSTRAINT uq_agent_runtime_workflow_script_task
        UNIQUE (tenant_id, task_id),
    CONSTRAINT agent_runtime_workflow_script_execution_fkey
        FOREIGN KEY (
            tenant_id, execution_id, target_kind, target_id, agent_id,
            session_id, end_user_id, run_id)
        REFERENCES public.agent_runtime_executions
            (tenant_id, execution_id, target_kind, target_id, agent_id,
             session_id, end_user_id, run_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_workflow_script_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(execution_id) <> ''
        AND btrim(session_id) <> ''
        AND target_kind IN ('AGENT', 'TEAM') AND btrim(target_id) <> ''
        AND btrim(agent_id) <> '' AND end_user_id > 0
        AND btrim(run_id) <> '' AND btrim(node_id) <> ''
        AND visit > 0 AND call_index > 0),
    CONSTRAINT ck_agent_runtime_workflow_script_source CHECK (
        definition_revision > 0
        AND installation_revision > 0
        AND prepared_execution_revision > 0
        AND task_id ~ '^[0-9a-f]{64}$'
        AND request_schema_id = 'application/json'
        AND deadline_nano BETWEEN 0 AND 999999999),
    CONSTRAINT ck_agent_runtime_workflow_script_preparation CHECK (
        source_checkpoint_fingerprint ~ '^[0-9a-f]{64}$'
        AND request_fingerprint ~ '^[0-9a-f]{64}$'
        AND preparation_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(preparation_data) <> ''),
    CONSTRAINT ck_agent_runtime_workflow_script_state CHECK (
        state IN ('PREPARED', 'DISPATCHED', 'COMPLETED', 'FAILED',
                  'CANCELLED', 'TIMED_OUT')),
    CONSTRAINT ck_agent_runtime_workflow_script_terminal CHECK (
        (state = 'PREPARED'
         AND dispatched_at IS NULL AND terminal_at IS NULL
         AND terminal_fingerprint IS NULL AND terminal_data IS NULL)
        OR (state = 'DISPATCHED'
            AND dispatched_at IS NOT NULL AND terminal_at IS NULL
            AND terminal_fingerprint IS NULL AND terminal_data IS NULL)
        OR (state IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT')
            AND dispatched_at IS NOT NULL AND terminal_at IS NOT NULL
            AND terminal_fingerprint ~ '^[0-9a-f]{64}$'
            AND btrim(terminal_data) <> '')),
    CONSTRAINT ck_agent_runtime_workflow_script_times CHECK (
        (dispatched_at IS NULL OR dispatched_at >= prepared_at)
        AND (terminal_at IS NULL OR terminal_at >= dispatched_at))
);

ALTER TABLE public.agent_runtime_workflow_script_calls
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_workflow_script_calls
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_workflow_script_tenant_isolation
    ON public.agent_runtime_workflow_script_calls
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (
        tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

CREATE FUNCTION public.guard_agent_runtime_workflow_script_call()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'PREPARED'
           OR NEW.dispatched_at IS NOT NULL
           OR NEW.terminal_at IS NOT NULL
           OR NEW.terminal_fingerprint IS NOT NULL
           OR NEW.terminal_data IS NOT NULL THEN
            RAISE EXCEPTION 'workflow_script_call_must_start_prepared';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'workflow_script_call_delete_forbidden';
    END IF;

    IF ROW(
        NEW.tenant_id, NEW.execution_id, NEW.session_id, NEW.target_kind,
        NEW.target_id, NEW.agent_id, NEW.end_user_id, NEW.run_id,
        NEW.node_id, NEW.visit, NEW.call_index, NEW.definition_revision,
        NEW.installation_revision, NEW.prepared_execution_revision,
        NEW.task_id, NEW.request_schema_id, NEW.deadline_epoch_second,
        NEW.deadline_nano, NEW.source_checkpoint_fingerprint,
        NEW.request_fingerprint, NEW.preparation_fingerprint,
        NEW.preparation_data, NEW.prepared_at)
       IS DISTINCT FROM ROW(
        OLD.tenant_id, OLD.execution_id, OLD.session_id, OLD.target_kind,
        OLD.target_id, OLD.agent_id, OLD.end_user_id, OLD.run_id,
        OLD.node_id, OLD.visit, OLD.call_index, OLD.definition_revision,
        OLD.installation_revision, OLD.prepared_execution_revision,
        OLD.task_id, OLD.request_schema_id, OLD.deadline_epoch_second,
        OLD.deadline_nano, OLD.source_checkpoint_fingerprint,
        OLD.request_fingerprint, OLD.preparation_fingerprint,
        OLD.preparation_data, OLD.prepared_at) THEN
        RAISE EXCEPTION 'workflow_script_call_preparation_immutable';
    END IF;

    IF OLD.state = 'PREPARED' AND NEW.state = 'DISPATCHED' THEN
        IF NEW.dispatched_at IS NULL
           OR NEW.terminal_at IS NOT NULL
           OR NEW.terminal_fingerprint IS NOT NULL
           OR NEW.terminal_data IS NOT NULL THEN
            RAISE EXCEPTION 'workflow_script_call_dispatch_invalid';
        END IF;
    ELSIF OLD.state = 'DISPATCHED'
          AND NEW.state IN (
              'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT') THEN
        IF NEW.dispatched_at IS DISTINCT FROM OLD.dispatched_at
           OR NEW.terminal_at IS NULL
           OR NEW.terminal_fingerprint IS NULL
           OR NEW.terminal_data IS NULL THEN
            RAISE EXCEPTION 'workflow_script_call_terminal_invalid';
        END IF;
    ELSE
        RAISE EXCEPTION 'workflow_script_call_transition_invalid';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guard_agent_runtime_workflow_script_call
    BEFORE INSERT OR UPDATE OR DELETE
    ON public.agent_runtime_workflow_script_calls
    FOR EACH ROW
    EXECUTE FUNCTION public.guard_agent_runtime_workflow_script_call();

COMMENT ON TABLE public.agent_runtime_workflow_script_calls IS
    'Workflow 脚本节点的不可变准备、单次 dispatch 授权和终态结果 journal';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.tenant_id IS
    'Execution 租户及强制 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.execution_id IS
    '脚本节点所属 Workflow Execution';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.session_id IS
    '由 Execution 复合外键冻结的 Session owner';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.target_kind IS
    '由 Execution 复合外键冻结的 Session target 类别';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.target_id IS
    '由 Execution 复合外键冻结的 Session target 标识';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.agent_id IS
    '由 Execution 复合外键冻结的执行 Agent';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.end_user_id IS
    '由 Execution 复合外键冻结的终端用户';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.run_id IS
    '每次 mutation 首先锁定并核验的 façade Run';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.node_id IS
    'Workflow 定义中的稳定脚本节点标识';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.visit IS
    '本 Execution 对脚本节点的正整数访问序号';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.call_index IS
    '本次节点访问内的正整数脚本调用序号';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.definition_revision IS
    '脚本调用绑定的精确 Workflow 定义 revision';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.installation_revision IS
    '脚本调用绑定的精确租户安装 revision';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.prepared_execution_revision IS
    '首次准备时持有行锁核验的 Execution revision';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.task_id IS
    '仅可提交一次并在恢复时查询的 lowercase SHA-256 沙箱任务标识';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.request_schema_id IS
    '固定为 application/json 的沙箱请求 schema';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.deadline_epoch_second IS
    '首次准备冻结的业务 deadline 秒部分';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.deadline_nano IS
    '首次准备冻结的业务 deadline 纳秒部分';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.source_checkpoint_fingerprint IS
    '首次准备来源 checkpoint 规范字节的 lowercase SHA-256';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.request_fingerprint IS
    '沙箱请求 TypedPayload 规范字节的 lowercase SHA-256';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.preparation_fingerprint IS
    '完整不可变准备记录规范字节的 lowercase SHA-256';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.preparation_data IS
    '身份、版本、checkpoint、taskId、请求和 deadline 的版本化规范私有编码';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.state IS
    'PREPARED 单向进入 DISPATCHED，再单向进入一个终态';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.terminal_fingerprint IS
    '终态类型和完整结果规范字节的 lowercase SHA-256';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.terminal_data IS
    '终态类型和完整 TypedPayload 结果的版本化规范私有编码';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.prepared_at IS
    '首次冻结准备的数据库审计时间';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.dispatched_at IS
    '首次取得沙箱 submit 权的数据库审计时间';
COMMENT ON COLUMN public.agent_runtime_workflow_script_calls.terminal_at IS
    '首次保存权威终态结果的数据库审计时间';
COMMENT ON FUNCTION public.guard_agent_runtime_workflow_script_call() IS
    '禁止改写或删除准备，并只允许 PREPARED→DISPATCHED→终态';
