-- V97: Agent Runtime Session 准入与继续提交的 adapter-private durable journals。
-- journal 只冻结原命令；V95 input ledger 与 Run 仍分别拥有有效准入结果和 Run 状态事实。

ALTER TABLE public.agent_runtime_runs
    ADD CONSTRAINT uq_agent_runtime_run_full_owner
        UNIQUE (tenant_id, agent_id, session_id, end_user_id, run_id);

COMMENT ON CONSTRAINT uq_agent_runtime_run_full_owner
    ON public.agent_runtime_runs IS
    '为 SessionCommitter journal 提供完整不可变 owner 与 Run 的复合外键目标';

CREATE TABLE public.agent_runtime_session_admission_prepares (
    tenant_id              text NOT NULL,
    agent_id               text NOT NULL,
    session_id             text NOT NULL,
    end_user_id            bigint NOT NULL,
    input_id               varchar(160) NOT NULL,
    request_fingerprint    varchar(64) NOT NULL,
    lifecycle_policy       varchar(40) NOT NULL,
    lifecycle_proof        text,
    command_fingerprint    varchar(64) NOT NULL,
    command_data           text NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_session_admission_prepares_pkey
        PRIMARY KEY (tenant_id, agent_id, session_id, input_id),
    CONSTRAINT agent_runtime_session_admission_owner_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_session_owners
            (tenant_id, agent_id, session_id, end_user_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_session_admission_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(agent_id) <> ''
        AND btrim(session_id) <> '' AND end_user_id > 0
        AND btrim(input_id) <> ''),
    CONSTRAINT ck_agent_runtime_session_admission_request CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_runtime_session_admission_lifecycle CHECK (
        (lifecycle_policy = 'ACTIVE_ONLY' AND lifecycle_proof IS NULL)
        OR (lifecycle_policy = 'CLOSED_EXISTING_RUN_RECOVERY'
            AND lifecycle_proof IS NOT NULL
            AND btrim(lifecycle_proof) <> '')),
    CONSTRAINT ck_agent_runtime_session_admission_command CHECK (
        command_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(command_data) <> '')
);

CREATE TABLE public.nw_runtime_session_admission_conflict_audits (
    tenant_id              text NOT NULL,
    audit_ref              text NOT NULL,
    resource_id_hash       varchar(64) NOT NULL,
    stored_fingerprint     varchar(64) NOT NULL,
    requested_fingerprint  varchar(64) NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_session_admission_conflicts_pkey
        PRIMARY KEY (tenant_id, audit_ref),
    CONSTRAINT ck_agent_runtime_session_admission_conflict_identity CHECK (
        btrim(tenant_id) <> ''
        AND audit_ref ~ '^session-admission-conflict:sha256:[0-9a-f]{64}$'
        AND resource_id_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_runtime_session_admission_conflict_facts CHECK (
        stored_fingerprint ~ '^[0-9a-f]{64}$'
        AND requested_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.agent_runtime_session_commit_journals (
    tenant_id              text NOT NULL,
    run_id                 varchar(160) NOT NULL,
    operation_hash         varchar(64) NOT NULL,
    agent_id               text NOT NULL,
    session_id             text NOT NULL,
    end_user_id            bigint NOT NULL,
    causation_input_id     varchar(160) NOT NULL,
    operation_ref          text NOT NULL,
    expected_status        varchar(16) NOT NULL,
    target_status          varchar(16) NOT NULL,
    expected_revision      bigint NOT NULL,
    command_fingerprint    varchar(64) NOT NULL,
    command_data           text NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_session_commit_journals_pkey
        PRIMARY KEY (tenant_id, run_id, operation_hash),
    CONSTRAINT agent_runtime_session_commit_run_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id, run_id)
        REFERENCES public.agent_runtime_runs
            (tenant_id, agent_id, session_id, end_user_id, run_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_session_commit_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(agent_id) <> ''
        AND btrim(session_id) <> '' AND end_user_id > 0
        AND btrim(run_id) <> '' AND btrim(causation_input_id) <> ''),
    CONSTRAINT ck_agent_runtime_session_commit_operation CHECK (
        operation_hash ~ '^[0-9a-f]{64}$'
        AND btrim(operation_ref) <> ''),
    CONSTRAINT ck_agent_runtime_session_commit_status CHECK (
        expected_status IN ('QUEUED', 'RUNNING', 'SUSPENDED', 'RESUMING',
                            'COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED')
        AND target_status IN ('QUEUED', 'RUNNING', 'SUSPENDED', 'RESUMING',
                              'COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_agent_runtime_session_commit_revision CHECK (
        expected_revision > 0),
    CONSTRAINT ck_agent_runtime_session_commit_command CHECK (
        command_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(command_data) <> '')
);

CREATE FUNCTION public.reject_agent_runtime_session_private_journal_mutation()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime session private journal is append-only';
    RETURN NULL;
END;
$$;

CREATE TRIGGER agent_runtime_session_admission_prepare_immutable
    BEFORE UPDATE OR DELETE
    ON public.agent_runtime_session_admission_prepares
    FOR EACH ROW
    EXECUTE FUNCTION public.reject_agent_runtime_session_private_journal_mutation();

CREATE TRIGGER agent_runtime_session_admission_conflict_immutable
    BEFORE UPDATE OR DELETE
    ON public.nw_runtime_session_admission_conflict_audits
    FOR EACH ROW
    EXECUTE FUNCTION public.reject_agent_runtime_session_private_journal_mutation();

CREATE TRIGGER agent_runtime_session_commit_journal_immutable
    BEFORE UPDATE OR DELETE
    ON public.agent_runtime_session_commit_journals
    FOR EACH ROW
    EXECUTE FUNCTION public.reject_agent_runtime_session_private_journal_mutation();

ALTER TABLE public.agent_runtime_session_admission_prepares
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_admission_prepares
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_admission_prepare_tenant_isolation
    ON public.agent_runtime_session_admission_prepares
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.nw_runtime_session_admission_conflict_audits
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_admission_conflict_audits
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_admission_conflict_tenant_isolation
    ON public.nw_runtime_session_admission_conflict_audits
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_session_commit_journals
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_commit_journals
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_commit_journal_tenant_isolation
    ON public.agent_runtime_session_commit_journals
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.agent_runtime_session_admission_prepares IS
    'SessionAdmission 首次 prepare 后冻结的完整原命令；独立于 V95 有效 input ledger 与 receipt';
COMMENT ON COLUMN public.agent_runtime_session_admission_prepares.tenant_id IS
    '准入命令所属租户与 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_session_admission_prepares.agent_id IS
    '准入时冻结的 Agent 标识';
COMMENT ON COLUMN public.agent_runtime_session_admission_prepares.session_id IS
    '准入时冻结的 Session 标识';
COMMENT ON COLUMN public.agent_runtime_session_admission_prepares.end_user_id IS
    '准入时冻结且由 owner root 约束的终端用户标识';
COMMENT ON COLUMN public.agent_runtime_session_admission_prepares.input_id IS
    'Session 内首次冻结准入命令的 Input 标识';
COMMENT ON COLUMN public.agent_runtime_session_admission_prepares.request_fingerprint IS
    'runtime.accept 授权资源与完整业务输入的 lowercase SHA-256 指纹';
COMMENT ON COLUMN public.agent_runtime_session_admission_prepares.lifecycle_policy IS
    '冻结的生命周期准入策略：ACTIVE_ONLY 或 CLOSED_EXISTING_RUN_RECOVERY';
COMMENT ON COLUMN public.agent_runtime_session_admission_prepares.lifecycle_proof IS
    'CLOSED_EXISTING_RUN_RECOVERY 的持久来源证明 ResultRef 原文；ACTIVE_ONLY 必须为空';
COMMENT ON COLUMN public.agent_runtime_session_admission_prepares.command_fingerprint IS
    '完整 adapter-private admission command codec 的 lowercase SHA-256 指纹';
COMMENT ON COLUMN public.agent_runtime_session_admission_prepares.command_data IS
    '完整 adapter-private admission command canonical codec，保存 owner、身份、输入、决策与时间';
COMMENT ON COLUMN public.agent_runtime_session_admission_prepares.created_at IS
    '准入命令首次冻结的数据库时间';
COMMENT ON CONSTRAINT agent_runtime_session_admission_prepares_pkey
    ON public.agent_runtime_session_admission_prepares IS
    '完整 Session 资源内同一 input 只允许冻结一个首次准入命令';
COMMENT ON CONSTRAINT agent_runtime_session_admission_owner_fkey
    ON public.agent_runtime_session_admission_prepares IS
    '准入命令必须引用 V93 冻结的完整 Session owner root';
COMMENT ON CONSTRAINT ck_agent_runtime_session_admission_identity
    ON public.agent_runtime_session_admission_prepares IS
    '完整 owner 与 input 标识必须合法且非空白';
COMMENT ON CONSTRAINT ck_agent_runtime_session_admission_request
    ON public.agent_runtime_session_admission_prepares IS
    '业务请求指纹必须为 64 字符 lowercase SHA-256';
COMMENT ON CONSTRAINT ck_agent_runtime_session_admission_lifecycle
    ON public.agent_runtime_session_admission_prepares IS
    'ACTIVE_ONLY 不携带 proof；关闭 Session 的既存 Run 恢复必须携带持久 proof 引用';
COMMENT ON CONSTRAINT ck_agent_runtime_session_admission_command
    ON public.agent_runtime_session_admission_prepares IS
    '完整命令指纹必须为 64 字符 lowercase SHA-256 且 canonical codec 非空白';

COMMENT ON TABLE public.nw_runtime_session_admission_conflict_audits IS
    'SessionAdmission 同 input 异内容冲突的稳定摘要；除 RLS tenant 外不保存 owner、命令或身份原文';
COMMENT ON COLUMN public.nw_runtime_session_admission_conflict_audits.tenant_id IS
    '冲突审计所属租户与 RLS 隔离键';
COMMENT ON COLUMN public.nw_runtime_session_admission_conflict_audits.audit_ref IS
    '对外返回的 domain-separated 固定格式不透明审计引用';
COMMENT ON COLUMN public.nw_runtime_session_admission_conflict_audits.resource_id_hash IS
    '完整 Session owner 与 input 资源身份的不可逆摘要';
COMMENT ON COLUMN public.nw_runtime_session_admission_conflict_audits.stored_fingerprint IS
    '既存 prepare、input ledger、Run 或 owner 事实的不可逆摘要';
COMMENT ON COLUMN public.nw_runtime_session_admission_conflict_audits.requested_fingerprint IS
    '冲突请求的不可逆指纹';
COMMENT ON COLUMN public.nw_runtime_session_admission_conflict_audits.created_at IS
    '相同稳定冲突摘要首次记录的数据库时间';
COMMENT ON CONSTRAINT agent_runtime_session_admission_conflicts_pkey
    ON public.nw_runtime_session_admission_conflict_audits IS
    '同一租户相同稳定 auditRef 只持久化一次';
COMMENT ON CONSTRAINT ck_agent_runtime_session_admission_conflict_identity
    ON public.nw_runtime_session_admission_conflict_audits IS
    'auditRef 与资源身份摘要必须使用各自固定 domain 的 lowercase SHA-256 格式';
COMMENT ON CONSTRAINT ck_agent_runtime_session_admission_conflict_facts
    ON public.nw_runtime_session_admission_conflict_audits IS
    '既存与请求指纹必须为 64 字符 lowercase SHA-256';

COMMENT ON TABLE public.agent_runtime_session_commit_journals IS
    'SessionCommitter 在 SessionLog append 与 Run CAS 前冻结的完整原命令；存在 journal 不表示命令已采用';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.tenant_id IS
    '继续提交命令所属租户与 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.run_id IS
    '继续提交命令所属 Run 标识，与 tenant、operation hash 组成读取键';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.operation_hash IS
    '对无长度上限 operationRef 使用专用 domain 和长度前缀计算的 64 字符 lowercase SHA-256 定位摘要';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.agent_id IS
    '继续提交命令冻结的 Agent 标识';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.session_id IS
    '继续提交命令冻结的 Session 标识';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.end_user_id IS
    '继续提交命令冻结且由 Run 行约束的终端用户标识';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.causation_input_id IS
    'RunIdentity 冻结的因果 Input 标识';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.operation_ref IS
    '无长度上限 ResultRef 原文；按 operationHash 读取后必须由 Java 逐字核验';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.expected_status IS
    '原 before RunRecord 状态，也是提交 Run CAS 时要求的状态';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.target_status IS
    '完整继续命令要求提交的目标 Run 状态';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.expected_revision IS
    '原 before RunRecord 的正 revision，也是提交 Run CAS 时要求的 revision';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.command_fingerprint IS
    '完整 adapter-private SessionCommitCommand codec 的 64 字符 lowercase SHA-256 指纹';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.command_data IS
    '完整 SessionCommitCommand canonical codec，保存原 Run、lease、checkpoint、event、appends 与 sequence';
COMMENT ON COLUMN public.agent_runtime_session_commit_journals.created_at IS
    '继续提交命令首次冻结的数据库时间';
COMMENT ON CONSTRAINT agent_runtime_session_commit_journals_pkey
    ON public.agent_runtime_session_commit_journals IS
    '同一 tenant 和 Run 内以固定长度 operation 摘要唯一冻结原命令';
COMMENT ON CONSTRAINT agent_runtime_session_commit_run_fkey
    ON public.agent_runtime_session_commit_journals IS
    '继续提交命令必须引用同一完整 owner 的权威 V95 Run';
COMMENT ON CONSTRAINT ck_agent_runtime_session_commit_identity
    ON public.agent_runtime_session_commit_journals IS
    '完整 Run owner、Run 与因果 Input 标识必须合法且非空白';
COMMENT ON CONSTRAINT ck_agent_runtime_session_commit_operation
    ON public.agent_runtime_session_commit_journals IS
    'operation 定位摘要必须为 lowercase SHA-256，ResultRef 原文必须非空白';
COMMENT ON CONSTRAINT ck_agent_runtime_session_commit_status
    ON public.agent_runtime_session_commit_journals IS
    'expected 与 target status 必须属于封闭 RunStatus 集合';
COMMENT ON CONSTRAINT ck_agent_runtime_session_commit_revision
    ON public.agent_runtime_session_commit_journals IS
    'expected revision 必须为正数';
COMMENT ON CONSTRAINT ck_agent_runtime_session_commit_command
    ON public.agent_runtime_session_commit_journals IS
    '完整命令指纹必须为 64 字符 lowercase SHA-256 且 canonical codec 非空白';
