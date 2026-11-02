-- Workflow 组合节点首次创建子 Execution 时冻结的不可变父准备与逐子来源。

CREATE TABLE public.agent_runtime_workflow_child_preparations (
    tenant_id                       text NOT NULL,
    parent_execution_id             varchar(160) NOT NULL,
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
    source_checkpoint_fingerprint   varchar(64) NOT NULL,
    preparation_fingerprint         varchar(64) NOT NULL,
    preparation_data                text NOT NULL,
    prepared_at                     timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_workflow_child_preparations_pkey
        PRIMARY KEY (
            tenant_id, parent_execution_id, node_id, visit, call_index),
    CONSTRAINT uq_agent_runtime_workflow_child_preparation_full_owner
        UNIQUE (
            tenant_id, parent_execution_id, node_id, visit, call_index,
            target_kind, target_id, agent_id, session_id, end_user_id, run_id),
    CONSTRAINT agent_runtime_workflow_child_preparation_parent_fkey
        FOREIGN KEY (
            tenant_id, parent_execution_id, target_kind, target_id, agent_id,
            session_id, end_user_id, run_id)
        REFERENCES public.agent_runtime_executions
            (tenant_id, execution_id, target_kind, target_id, agent_id,
             session_id, end_user_id, run_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_workflow_child_preparation_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(parent_execution_id) <> ''
        AND btrim(session_id) <> ''
        AND target_kind IN ('AGENT', 'TEAM') AND btrim(target_id) <> ''
        AND btrim(agent_id) <> '' AND end_user_id > 0 AND btrim(run_id) <> ''
        AND btrim(node_id) <> '' AND visit > 0 AND call_index > 0),
    CONSTRAINT ck_agent_runtime_workflow_child_preparation_source CHECK (
        definition_revision > 0
        AND installation_revision > 0
        AND prepared_execution_revision > 0),
    CONSTRAINT ck_agent_runtime_workflow_child_preparation_codec CHECK (
        source_checkpoint_fingerprint ~ '^[0-9a-f]{64}$'
        AND preparation_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(preparation_data) <> '')
);

CREATE TABLE public.agent_runtime_workflow_child_execution_sources (
    tenant_id               text NOT NULL,
    child_execution_id      varchar(160) NOT NULL,
    parent_execution_id     varchar(160) NOT NULL,
    node_id                 text NOT NULL,
    visit                   integer NOT NULL,
    call_index              integer NOT NULL,
    child_ordinal           integer NOT NULL,
    branch_id               text NOT NULL,
    session_id              text NOT NULL,
    target_kind             text NOT NULL,
    target_id               text NOT NULL,
    agent_id                text NOT NULL,
    end_user_id             bigint NOT NULL,
    run_id                  varchar(160) NOT NULL,
    command_fingerprint     varchar(64) NOT NULL,
    command_data            text NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_workflow_child_execution_sources_pkey
        PRIMARY KEY (tenant_id, child_execution_id),
    CONSTRAINT uq_agent_runtime_workflow_child_source_branch
        UNIQUE (
            tenant_id, parent_execution_id, node_id, visit, call_index,
            branch_id),
    CONSTRAINT uq_agent_runtime_workflow_child_source_ordinal
        UNIQUE (
            tenant_id, parent_execution_id, node_id, visit, call_index,
            child_ordinal),
    CONSTRAINT agent_runtime_workflow_child_source_parent_fkey
        FOREIGN KEY (
            tenant_id, parent_execution_id, node_id, visit, call_index,
            target_kind, target_id, agent_id, session_id, end_user_id, run_id)
        REFERENCES public.agent_runtime_workflow_child_preparations
            (tenant_id, parent_execution_id, node_id, visit, call_index,
             target_kind, target_id, agent_id, session_id, end_user_id, run_id)
        ON DELETE RESTRICT,
    CONSTRAINT agent_runtime_workflow_child_source_child_fkey
        FOREIGN KEY (
            tenant_id, child_execution_id, target_kind, target_id, agent_id,
            session_id, end_user_id, run_id)
        REFERENCES public.agent_runtime_executions
            (tenant_id, execution_id, target_kind, target_id, agent_id,
             session_id, end_user_id, run_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_workflow_child_source_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(child_execution_id) <> ''
        AND btrim(parent_execution_id) <> ''
        AND btrim(node_id) <> '' AND visit > 0 AND call_index > 0
        AND child_ordinal BETWEEN 0 AND 31 AND btrim(branch_id) <> ''
        AND btrim(session_id) <> ''
        AND target_kind IN ('AGENT', 'TEAM') AND btrim(target_id) <> ''
        AND btrim(agent_id) <> '' AND end_user_id > 0 AND btrim(run_id) <> ''
        AND child_execution_id <> parent_execution_id),
    CONSTRAINT ck_agent_runtime_workflow_child_source_codec CHECK (
        command_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(command_data) <> '')
);

ALTER TABLE public.agent_runtime_workflow_child_preparations
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_workflow_child_preparations
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_workflow_child_preparation_tenant_isolation
    ON public.agent_runtime_workflow_child_preparations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_workflow_child_execution_sources
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_workflow_child_execution_sources
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_workflow_child_source_tenant_isolation
    ON public.agent_runtime_workflow_child_execution_sources
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

CREATE FUNCTION public.agent_runtime_workflow_child_preparation_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'workflow_child_preparation_immutable';
END;
$$;

CREATE TRIGGER agent_runtime_workflow_child_preparation_immutable
    BEFORE UPDATE OR DELETE
    ON public.agent_runtime_workflow_child_preparations
    FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_workflow_child_preparation_immutable();

CREATE FUNCTION public.agent_runtime_workflow_child_source_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'workflow_child_execution_source_immutable';
END;
$$;

CREATE TRIGGER agent_runtime_workflow_child_source_immutable
    BEFORE UPDATE OR DELETE
    ON public.agent_runtime_workflow_child_execution_sources
    FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_workflow_child_source_immutable();

COMMENT ON TABLE public.agent_runtime_workflow_child_preparations IS
    'Workflow 组合节点在父 Execution lease 下首次冻结的完整子 Execution 准备 journal';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.tenant_id IS
    '父 Execution 租户及强制 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.parent_execution_id IS
    '持有组合节点游标和当前写租约的父 Workflow Execution';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.session_id IS
    '由父 Execution 复合外键冻结的 Session owner';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.target_kind IS
    '由父 Execution 复合外键冻结的 Session target 类别';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.target_id IS
    '由父 Execution 复合外键冻结的 Session target 标识';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.agent_id IS
    '由父 Execution 复合外键冻结的执行 Agent';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.end_user_id IS
    '由父 Execution 复合外键冻结的终端用户';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.run_id IS
    '首次准备前先行锁定并核验的 façade Run';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.node_id IS
    'Workflow 定义中的稳定组合节点标识';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.visit IS
    '本父 Execution 对组合节点的正整数访问序号';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.call_index IS
    '本次节点访问内的正整数组合调用序号';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.definition_revision IS
    '组合调度绑定的精确 Workflow 定义 revision';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.installation_revision IS
    '组合调度绑定的精确租户安装 revision';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.prepared_execution_revision IS
    '首次准备时持有行锁核验的父 Execution revision';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.source_checkpoint_fingerprint IS
    '首次准备来源 checkpoint 规范字节的 lowercase SHA-256';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.preparation_fingerprint IS
    '完整父准备规范字节的 lowercase SHA-256';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.preparation_data IS
    '父调用身份、版本、checkpoint、分支及原 CreateExecution 的版本化规范私有编码';
COMMENT ON COLUMN public.agent_runtime_workflow_child_preparations.prepared_at IS
    '首次原子创建全部子 Execution 并冻结准备的数据库审计时间';

COMMENT ON TABLE public.agent_runtime_workflow_child_execution_sources IS
    '子 Execution 到父组合准备、分支和原 CreateExecution 的不可变来源映射';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.tenant_id IS
    '子与父 Execution 的共同租户及强制 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.child_execution_id IS
    '仅可绑定一个父组合来源的子 Workflow Execution';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.parent_execution_id IS
    '创建该子 Execution 的父 Workflow Execution';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.node_id IS
    '父准备中的稳定组合节点标识';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.visit IS
    '父准备中的节点访问序号';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.call_index IS
    '父准备中的组合调用序号';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.child_ordinal IS
    '子项在完整父准备中的零基稳定顺序，范围为 0 到 31';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.branch_id IS
    '父组合调用内唯一且不可变的分支标识';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.session_id IS
    '父子 Execution 共享并由两侧复合外键核验的 Session owner';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.target_kind IS
    '父子 Execution 共享并由两侧复合外键核验的 Session target 类别';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.target_id IS
    '父子 Execution 共享并由两侧复合外键核验的 Session target 标识';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.agent_id IS
    '父子 Execution 共享并由两侧复合外键核验的执行 Agent';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.end_user_id IS
    '父子 Execution 共享并由两侧复合外键核验的终端用户';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.run_id IS
    '父子 Execution 共享并由两侧复合外键核验的 façade Run';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.command_fingerprint IS
    '原 CreateExecution 规范字节的 lowercase SHA-256';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.command_data IS
    '用于来源恢复和固定身份反校验的原 CreateExecution 规范私有编码';
COMMENT ON COLUMN public.agent_runtime_workflow_child_execution_sources.created_at IS
    '与子 Execution、父准备在同一事务首次提交的数据库审计时间';

COMMENT ON FUNCTION public.agent_runtime_workflow_child_preparation_immutable() IS
    '禁止更新或删除原 Workflow 组合节点子 Execution 准备';
COMMENT ON FUNCTION public.agent_runtime_workflow_child_source_immutable() IS
    '禁止更新或删除子 Execution 的原父组合来源映射';
