-- V96: Agent Runtime SDK SessionStore 生命周期、CAS journal 与安全冲突审计。

CREATE TABLE public.agent_runtime_sessions (
    tenant_id              text NOT NULL,
    session_id             text NOT NULL,
    agent_id               text NOT NULL,
    end_user_id            bigint NOT NULL,
    channel                text NOT NULL,
    scope_key              text NOT NULL,
    persona_version        text NOT NULL,
    create_fingerprint     varchar(64) NOT NULL,
    create_command_data    text NOT NULL,
    status                 varchar(16) NOT NULL,
    session_revision       bigint NOT NULL,
    created_epoch_second   bigint NOT NULL,
    created_nano           integer NOT NULL,
    updated_epoch_second   bigint NOT NULL,
    updated_nano           integer NOT NULL,
    CONSTRAINT agent_runtime_sessions_pkey
        PRIMARY KEY (tenant_id, agent_id, session_id),
    CONSTRAINT uq_agent_runtime_session_full_owner
        UNIQUE (tenant_id, agent_id, session_id, end_user_id),
    CONSTRAINT agent_runtime_session_owner_root_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_session_owners
            (tenant_id, agent_id, session_id, end_user_id),
    CONSTRAINT ck_agent_runtime_session_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(session_id) <> ''
        AND btrim(agent_id) <> '' AND end_user_id > 0
        AND btrim(channel) <> '' AND btrim(scope_key) <> ''
        AND btrim(persona_version) <> ''),
    CONSTRAINT ck_agent_runtime_session_create_codec CHECK (
        create_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(create_command_data) <> ''),
    CONSTRAINT ck_agent_runtime_session_lifecycle CHECK (
        status IN ('CREATED', 'ACTIVE', 'CLOSED')
        AND session_revision > 0),
    CONSTRAINT ck_agent_runtime_session_times CHECK (
        created_nano BETWEEN 0 AND 999999999
        AND updated_nano BETWEEN 0 AND 999999999
        AND (updated_epoch_second, updated_nano)
            >= (created_epoch_second, created_nano))
);

CREATE TABLE public.agent_runtime_session_mutations (
    tenant_id              text NOT NULL,
    session_id             text NOT NULL,
    agent_id               text NOT NULL,
    end_user_id            bigint NOT NULL,
    expected_status        varchar(16) NOT NULL,
    target_status          varchar(16) NOT NULL,
    expected_revision      bigint NOT NULL,
    causation_input_id     text NOT NULL,
    command_fingerprint    varchar(64) NOT NULL,
    command_data           text NOT NULL,
    output_status          varchar(16) NOT NULL,
    output_revision        bigint NOT NULL,
    output_updated_epoch_second bigint NOT NULL,
    output_updated_nano    integer NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_session_mutations_pkey
        PRIMARY KEY (
            tenant_id, agent_id, session_id, expected_status, target_status,
            expected_revision, causation_input_id),
    CONSTRAINT agent_runtime_session_mutation_session_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_sessions
            (tenant_id, agent_id, session_id, end_user_id),
    CONSTRAINT uq_agent_runtime_session_mutation_output
        UNIQUE (tenant_id, agent_id, session_id, end_user_id, output_revision),
    CONSTRAINT ck_agent_runtime_session_mutation_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(session_id) <> ''
        AND btrim(agent_id) <> '' AND end_user_id > 0
        AND btrim(causation_input_id) <> ''),
    CONSTRAINT ck_agent_runtime_session_mutation_command CHECK (
        command_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(command_data) <> ''),
    CONSTRAINT ck_agent_runtime_session_mutation_transition CHECK (
        expected_revision > 0
        AND ((expected_status = 'CREATED' AND target_status = 'ACTIVE')
          OR (expected_status = 'ACTIVE' AND target_status = 'CLOSED'))
        AND output_status = target_status
        AND output_revision = expected_revision + 1),
    CONSTRAINT ck_agent_runtime_session_mutation_time CHECK (
        output_updated_nano BETWEEN 0 AND 999999999)
);

CREATE TABLE public.agent_runtime_session_conflict_audits (
    tenant_id              text NOT NULL,
    audit_ref              text NOT NULL,
    resource_id_hash       varchar(64) NOT NULL,
    conflict_kind          varchar(16) NOT NULL,
    current_revision       bigint NOT NULL,
    stored_fingerprint     varchar(64) NOT NULL,
    requested_fingerprint  varchar(64) NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_session_conflict_audits_pkey
        PRIMARY KEY (tenant_id, audit_ref),
    CONSTRAINT ck_agent_runtime_session_conflict_audit_identity CHECK (
        btrim(tenant_id) <> ''
        AND audit_ref ~ '^session-store-conflict:sha256:[0-9a-f]{64}$'
        AND resource_id_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_runtime_session_conflict_audit_facts CHECK (
        conflict_kind IN ('CREATE', 'CAS')
        AND current_revision > 0
        AND stored_fingerprint ~ '^[0-9a-f]{64}$'
        AND requested_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION public.guard_agent_runtime_session()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'CREATED' OR NEW.session_revision <> 1
           OR (NEW.updated_epoch_second, NEW.updated_nano)
                IS DISTINCT FROM (NEW.created_epoch_second, NEW.created_nano) THEN
            RAISE EXCEPTION 'new agent runtime session must start CREATED at revision one';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime session is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.session_id, NEW.agent_id, NEW.end_user_id,
        NEW.channel, NEW.scope_key, NEW.persona_version,
        NEW.create_fingerprint, NEW.create_command_data,
        NEW.created_epoch_second, NEW.created_nano)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.session_id, OLD.agent_id, OLD.end_user_id,
        OLD.channel, OLD.scope_key, OLD.persona_version,
        OLD.create_fingerprint, OLD.create_command_data,
        OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'agent runtime session immutable facts changed';
    END IF;
    IF NEW.session_revision <> OLD.session_revision + 1
       OR NOT ((OLD.status = 'CREATED' AND NEW.status = 'ACTIVE')
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'CLOSED')) THEN
        RAISE EXCEPTION 'illegal agent runtime session lifecycle transition';
    END IF;
    IF (NEW.updated_epoch_second, NEW.updated_nano)
        < (OLD.updated_epoch_second, OLD.updated_nano) THEN
        RAISE EXCEPTION 'agent runtime session updated time moved backwards';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_runtime_session_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.agent_runtime_sessions
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session();

CREATE FUNCTION public.reject_agent_runtime_session_store_fact_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime session store fact is immutable';
    RETURN NULL;
END;
$$;

CREATE TRIGGER agent_runtime_session_mutation_immutable
    BEFORE UPDATE OR DELETE ON public.agent_runtime_session_mutations
    FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_session_store_fact_mutation();

CREATE TRIGGER agent_runtime_session_conflict_audit_immutable
    BEFORE UPDATE OR DELETE ON public.agent_runtime_session_conflict_audits
    FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_session_store_fact_mutation();

CREATE FUNCTION public.assert_agent_runtime_session_mutation_journaled()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_session_mutations mutation
         WHERE mutation.tenant_id = NEW.tenant_id
           AND mutation.agent_id = NEW.agent_id
           AND mutation.session_id = NEW.session_id
           AND mutation.end_user_id = NEW.end_user_id
           AND mutation.expected_status = OLD.status
           AND mutation.target_status = NEW.status
           AND mutation.expected_revision = OLD.session_revision
           AND mutation.output_status = NEW.status
           AND mutation.output_revision = NEW.session_revision
           AND (mutation.output_updated_epoch_second,
                mutation.output_updated_nano)
               = (NEW.updated_epoch_second, NEW.updated_nano)) THEN
        RAISE EXCEPTION 'session lifecycle transition must commit its journal';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_session_mutation_journal
    AFTER UPDATE ON public.agent_runtime_sessions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_session_mutation_journaled();

CREATE FUNCTION public.assert_agent_runtime_session_mutation_applied()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_sessions session_record
         WHERE session_record.tenant_id = NEW.tenant_id
           AND session_record.agent_id = NEW.agent_id
           AND session_record.session_id = NEW.session_id
           AND session_record.end_user_id = NEW.end_user_id
           AND session_record.status = NEW.output_status
           AND session_record.session_revision = NEW.output_revision
           AND (session_record.updated_epoch_second,
                session_record.updated_nano)
               = (NEW.output_updated_epoch_second, NEW.output_updated_nano)) THEN
        RAISE EXCEPTION 'session mutation journal must match committed lifecycle output';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_session_mutation_applied
    AFTER INSERT ON public.agent_runtime_session_mutations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_session_mutation_applied();

ALTER TABLE public.agent_runtime_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_tenant_isolation
    ON public.agent_runtime_sessions
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_session_mutations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_mutations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_mutation_tenant_isolation
    ON public.agent_runtime_session_mutations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_session_conflict_audits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_conflict_audits FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_conflict_audit_tenant_isolation
    ON public.agent_runtime_session_conflict_audits
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.agent_runtime_sessions IS
    'Session 固定入口事实与 CREATED-ACTIVE-CLOSED 生命周期权威记录；lastSequence 不复制到本表';
COMMENT ON COLUMN public.agent_runtime_sessions.tenant_id IS
    'Session 所属租户与 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_sessions.session_id IS
    '租户和 Agent 内稳定的 Session 标识';
COMMENT ON COLUMN public.agent_runtime_sessions.agent_id IS
    'Session 首次创建时冻结的 Agent 标识';
COMMENT ON COLUMN public.agent_runtime_sessions.end_user_id IS
    '由共用 owner root 冻结的终端用户标识';
COMMENT ON COLUMN public.agent_runtime_sessions.channel IS
    'Session 首次创建时冻结的入口 channel';
COMMENT ON COLUMN public.agent_runtime_sessions.scope_key IS
    'Session 首次创建时冻结的 scope 标识';
COMMENT ON COLUMN public.agent_runtime_sessions.persona_version IS
    'Session 首次创建时冻结的人格版本';
COMMENT ON COLUMN public.agent_runtime_sessions.create_fingerprint IS
    '完整 CreateSession canonical command 的 SHA-256 指纹';
COMMENT ON COLUMN public.agent_runtime_sessions.create_command_data IS
    '完整 CreateSession 的 adapter-private canonical codec，保留 requestedAt、causation 与 trace';
COMMENT ON COLUMN public.agent_runtime_sessions.status IS
    '独立 Session 生命周期状态：CREATED、ACTIVE 或 CLOSED';
COMMENT ON COLUMN public.agent_runtime_sessions.session_revision IS
    '从一开始、每次合法生命周期 CAS 单步增加的版本';
COMMENT ON COLUMN public.agent_runtime_sessions.created_epoch_second IS
    '数据库存储时钟生成的创建 Unix epoch second';
COMMENT ON COLUMN public.agent_runtime_sessions.created_nano IS
    '创建时间的纳秒部分';
COMMENT ON COLUMN public.agent_runtime_sessions.updated_epoch_second IS
    '数据库存储时钟生成的最近生命周期更新时间秒';
COMMENT ON COLUMN public.agent_runtime_sessions.updated_nano IS
    '最近生命周期更新时间的纳秒部分';
COMMENT ON CONSTRAINT agent_runtime_session_owner_root_fkey
    ON public.agent_runtime_sessions IS
    'SessionStore 行必须引用三个持久域共用的完整 owner root';
COMMENT ON CONSTRAINT agent_runtime_sessions_pkey
    ON public.agent_runtime_sessions IS
    'tenant、agent、session 唯一定位一条生命周期记录';
COMMENT ON CONSTRAINT uq_agent_runtime_session_full_owner
    ON public.agent_runtime_sessions IS
    '为 mutation journal 提供完整不可变 owner 外键目标';
COMMENT ON CONSTRAINT ck_agent_runtime_session_identity
    ON public.agent_runtime_sessions IS
    'owner 与固定入口配置必须完整合法';
COMMENT ON CONSTRAINT ck_agent_runtime_session_create_codec
    ON public.agent_runtime_sessions IS
    'create 指纹必须为 SHA-256 且 canonical command 非空白';
COMMENT ON CONSTRAINT ck_agent_runtime_session_lifecycle
    ON public.agent_runtime_sessions IS
    'Session 状态属于封闭集合且 revision 为正数';
COMMENT ON CONSTRAINT ck_agent_runtime_session_times
    ON public.agent_runtime_sessions IS
    '存储时间纳秒合法且更新时间不早于创建时间';

COMMENT ON TABLE public.agent_runtime_session_mutations IS
    'Session lifecycle CAS 的不可变响应丢失重放 journal';
COMMENT ON COLUMN public.agent_runtime_session_mutations.tenant_id IS
    'CAS 所属租户与 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_session_mutations.session_id IS
    'CAS 所属 Session 标识';
COMMENT ON COLUMN public.agent_runtime_session_mutations.agent_id IS
    'CAS 所属 Agent 标识';
COMMENT ON COLUMN public.agent_runtime_session_mutations.end_user_id IS
    'CAS 所属且由 Session 行约束的终端用户标识';
COMMENT ON COLUMN public.agent_runtime_session_mutations.expected_status IS
    '首次 CAS 要求的生命周期状态';
COMMENT ON COLUMN public.agent_runtime_session_mutations.target_status IS
    '首次 CAS 要推进到的生命周期状态';
COMMENT ON COLUMN public.agent_runtime_session_mutations.expected_revision IS
    '首次 CAS 要求的正 Session revision';
COMMENT ON COLUMN public.agent_runtime_session_mutations.causation_input_id IS
    '与 owner、状态和 revision 共同定位 operation 的因果 InputId';
COMMENT ON COLUMN public.agent_runtime_session_mutations.command_data IS
    '完整 ChangeSessionStatus canonical command，保留 requestedAt 与 trace 审计事实';
COMMENT ON COLUMN public.agent_runtime_session_mutations.command_fingerprint IS
    '完整 CAS command 的 SHA-256 指纹';
COMMENT ON COLUMN public.agent_runtime_session_mutations.output_status IS
    '首次应用 CAS 后的 SessionStore-owned status';
COMMENT ON COLUMN public.agent_runtime_session_mutations.output_revision IS
    '首次应用 CAS 后的 Session revision';
COMMENT ON COLUMN public.agent_runtime_session_mutations.output_updated_epoch_second IS
    '首次应用 CAS 的数据库更新时间秒';
COMMENT ON COLUMN public.agent_runtime_session_mutations.output_updated_nano IS
    '首次应用 CAS 的数据库更新时间纳秒部分';
COMMENT ON COLUMN public.agent_runtime_session_mutations.created_at IS
    'journal 首次插入的数据库时间';
COMMENT ON CONSTRAINT agent_runtime_session_mutations_pkey
    ON public.agent_runtime_session_mutations IS
    '完整 owner、expected/target、revision 与 causationInputId 唯一定位 CAS operation';
COMMENT ON CONSTRAINT agent_runtime_session_mutation_session_fkey
    ON public.agent_runtime_session_mutations IS
    'mutation 必须引用同一完整 owner 的 Session 生命周期记录';
COMMENT ON CONSTRAINT uq_agent_runtime_session_mutation_output
    ON public.agent_runtime_session_mutations IS
    '每个完整 owner 的 lifecycle output revision 只能对应一个 first-winner journal';
COMMENT ON CONSTRAINT ck_agent_runtime_session_mutation_identity
    ON public.agent_runtime_session_mutations IS
    'mutation owner 与 causationInputId 必须完整合法';
COMMENT ON CONSTRAINT ck_agent_runtime_session_mutation_command
    ON public.agent_runtime_session_mutations IS
    'CAS 指纹必须为 SHA-256 且 canonical command 非空白';
COMMENT ON CONSTRAINT ck_agent_runtime_session_mutation_transition
    ON public.agent_runtime_session_mutations IS
    'journal 只保存合法迁移且输出 revision 精确增加一';
COMMENT ON CONSTRAINT ck_agent_runtime_session_mutation_time
    ON public.agent_runtime_session_mutations IS
    'journal 输出更新时间纳秒必须位于 Java Instant 合法范围';

COMMENT ON TABLE public.agent_runtime_session_conflict_audits IS
    'Create/CAS 冲突的受 RLS 保护持久摘要；不保存 owner、入口、trace 或 requestedAt 原文';
COMMENT ON COLUMN public.agent_runtime_session_conflict_audits.tenant_id IS
    '冲突审计所属租户与 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_session_conflict_audits.audit_ref IS
    '对外返回的 domain-separated 固定长度不透明审计引用';
COMMENT ON COLUMN public.agent_runtime_session_conflict_audits.resource_id_hash IS
    'tenant、agent、session 资源身份的不可逆摘要';
COMMENT ON COLUMN public.agent_runtime_session_conflict_audits.conflict_kind IS
    '冲突所属 CREATE 或 CAS 操作域';
COMMENT ON COLUMN public.agent_runtime_session_conflict_audits.current_revision IS
    '冲突发生时允许对外返回的当前正 revision';
COMMENT ON COLUMN public.agent_runtime_session_conflict_audits.stored_fingerprint IS
    '既存 create、journal 或 lifecycle tuple 的不可逆摘要';
COMMENT ON COLUMN public.agent_runtime_session_conflict_audits.requested_fingerprint IS
    '冲突请求完整 canonical command 的不可逆摘要';
COMMENT ON COLUMN public.agent_runtime_session_conflict_audits.created_at IS
    '首次记录相同稳定冲突摘要的数据库时间';
COMMENT ON CONSTRAINT agent_runtime_session_conflict_audits_pkey
    ON public.agent_runtime_session_conflict_audits IS
    '同一租户相同稳定 auditRef 只持久化一次';
COMMENT ON CONSTRAINT ck_agent_runtime_session_conflict_audit_identity
    ON public.agent_runtime_session_conflict_audits IS
    'auditRef 与资源引用必须为指定 domain 的固定长度摘要';
COMMENT ON CONSTRAINT ck_agent_runtime_session_conflict_audit_facts
    ON public.agent_runtime_session_conflict_audits IS
    '冲突域、current revision 与两侧指纹必须完整合法';
