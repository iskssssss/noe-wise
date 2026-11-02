CREATE TABLE public.agent_runtime_memory_fact_revisions (
    tenant_id text NOT NULL,
    fact_id text NOT NULL,
    revision bigint NOT NULL,
    end_user_id bigint NOT NULL,
    agent_id text NOT NULL,
    session_id text,
    scope varchar(16) NOT NULL,
    type_id text NOT NULL,
    status varchar(24) NOT NULL,
    source_session_id text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    valid_from_epoch_second bigint NOT NULL,
    valid_from_nano integer NOT NULL,
    valid_to_epoch_second bigint,
    valid_to_nano integer,
    expires_epoch_second bigint,
    expires_nano integer,
    superseded_fact_id text,
    fact_data bytea NOT NULL,
    CONSTRAINT pk_agent_runtime_memory_fact_revisions
        PRIMARY KEY (tenant_id, fact_id, revision),
    CONSTRAINT uq_agent_runtime_memory_revision_identity
        UNIQUE NULLS NOT DISTINCT
        (tenant_id, fact_id, revision, end_user_id, agent_id, session_id, scope, type_id),
    CONSTRAINT fk_agent_runtime_memory_revision_source_owner
        FOREIGN KEY (tenant_id, agent_id, source_session_id, end_user_id)
        REFERENCES public.agent_runtime_session_owners
            (tenant_id, agent_id, session_id, end_user_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_agent_runtime_memory_revision_scope_owner
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_session_owners
            (tenant_id, agent_id, session_id, end_user_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_memory_revision_identity
        CHECK (btrim(tenant_id) <> '' AND btrim(fact_id) <> '' AND revision > 0),
    CONSTRAINT ck_agent_runtime_memory_revision_owner
        CHECK (end_user_id > 0 AND btrim(agent_id) <> '' AND btrim(type_id) <> ''),
    CONSTRAINT ck_agent_runtime_memory_revision_scope
        CHECK (scope IN ('SESSION','USER_AGENT')),
    CONSTRAINT ck_agent_runtime_memory_revision_session_scope
        CHECK ((scope = 'SESSION' AND session_id = source_session_id)
        OR (scope = 'USER_AGENT' AND session_id IS NULL)),
    CONSTRAINT ck_agent_runtime_memory_revision_status
        CHECK (status IN ('ACTIVE','DECAYED','PENDING_CONFIRM','REJECTED','ARCHIVED','FORGOTTEN')),
    CONSTRAINT ck_agent_runtime_memory_revision_required_nanos
        CHECK (created_nano BETWEEN 0 AND 999999999
        AND valid_from_nano BETWEEN 0 AND 999999999),
    CONSTRAINT ck_agent_runtime_memory_revision_optional_times
        CHECK ((valid_to_epoch_second IS NULL) = (valid_to_nano IS NULL)
        AND (expires_epoch_second IS NULL) = (expires_nano IS NULL)),
    CONSTRAINT ck_agent_runtime_memory_revision_valid_to_nano
        CHECK (valid_to_nano IS NULL OR valid_to_nano BETWEEN 0 AND 999999999),
    CONSTRAINT ck_agent_runtime_memory_revision_expires_nano
        CHECK (expires_nano IS NULL OR expires_nano BETWEEN 0 AND 999999999),
    CONSTRAINT ck_agent_runtime_memory_revision_superseded_fact
        CHECK (superseded_fact_id IS NULL OR btrim(superseded_fact_id) <> '')
);

CREATE TABLE public.agent_runtime_memory_fact_sources (
    tenant_id text NOT NULL,
    fact_id text NOT NULL,
    revision bigint NOT NULL,
    source_ordinal integer NOT NULL,
    agent_id text NOT NULL,
    source_session_id text NOT NULL,
    source_event_id text NOT NULL,
    CONSTRAINT pk_agent_runtime_memory_fact_sources
        PRIMARY KEY (tenant_id, fact_id, revision, source_ordinal),
    CONSTRAINT uq_agent_runtime_memory_fact_source_event
        UNIQUE (tenant_id, fact_id, revision, source_event_id),
    CONSTRAINT fk_agent_runtime_memory_source_revision
        FOREIGN KEY (tenant_id, fact_id, revision)
        REFERENCES public.agent_runtime_memory_fact_revisions (tenant_id, fact_id, revision)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_agent_runtime_memory_source_session_event
        FOREIGN KEY (tenant_id, agent_id, source_session_id, source_event_id)
        REFERENCES public.agent_runtime_session_events
            (tenant_id, agent_id, session_id, event_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_memory_source_identity
        CHECK (source_ordinal >= 0 AND btrim(agent_id) <> ''
        AND btrim(source_session_id) <> '' AND btrim(source_event_id) <> '')
);

CREATE TABLE public.agent_runtime_memory_fact_heads (
    tenant_id text NOT NULL,
    fact_id text NOT NULL,
    current_revision bigint NOT NULL,
    end_user_id bigint NOT NULL,
    agent_id text NOT NULL,
    session_id text,
    scope varchar(16) NOT NULL,
    type_id text NOT NULL,
    CONSTRAINT pk_agent_runtime_memory_fact_heads
        PRIMARY KEY (tenant_id, fact_id),
    CONSTRAINT fk_agent_runtime_memory_head_revision
        FOREIGN KEY (tenant_id, fact_id, current_revision, end_user_id, agent_id,
                 session_id, scope, type_id)
        REFERENCES public.agent_runtime_memory_fact_revisions
            (tenant_id, fact_id, revision, end_user_id, agent_id, session_id, scope, type_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_memory_head_identity
        CHECK (current_revision > 0 AND end_user_id > 0
        AND btrim(agent_id) <> '' AND btrim(type_id) <> ''),
    CONSTRAINT ck_agent_runtime_memory_head_scope
        CHECK ((scope = 'SESSION' AND session_id IS NOT NULL AND btrim(session_id) <> '')
        OR (scope = 'USER_AGENT' AND session_id IS NULL))
);

ALTER TABLE public.agent_runtime_memory_fact_revisions
    ADD CONSTRAINT fk_agent_runtime_memory_superseded_head
    FOREIGN KEY (tenant_id, superseded_fact_id)
    REFERENCES public.agent_runtime_memory_fact_heads (tenant_id, fact_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE public.agent_runtime_memory_operations (
    tenant_id text NOT NULL,
    idempotency_key text NOT NULL,
    command_kind varchar(16) NOT NULL,
    command_data bytea NOT NULL,
    result_fact_id text NOT NULL,
    result_revision bigint NOT NULL,
    recorded_epoch_second bigint NOT NULL,
    recorded_nano integer NOT NULL,
    CONSTRAINT pk_agent_runtime_memory_operations
        PRIMARY KEY (tenant_id, idempotency_key),
    CONSTRAINT fk_agent_runtime_memory_operation_result
        FOREIGN KEY (tenant_id, result_fact_id, result_revision)
        REFERENCES public.agent_runtime_memory_fact_revisions (tenant_id, fact_id, revision)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_memory_operation_key
        CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT ck_agent_runtime_memory_operation_kind
        CHECK (command_kind IN ('CREATE','SUPERSEDE','TRANSITION')),
    CONSTRAINT ck_agent_runtime_memory_operation_result
        CHECK (result_revision > 0 AND recorded_nano BETWEEN 0 AND 999999999)
);

CREATE TABLE public.agent_runtime_memory_conflict_audits (
    tenant_id text NOT NULL,
    audit_ref text NOT NULL,
    idempotency_key text NOT NULL,
    fact_id text NOT NULL,
    current_revision bigint NOT NULL,
    reason_code text NOT NULL,
    command_digest varchar(64) NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    CONSTRAINT pk_agent_runtime_memory_conflict_audits
        PRIMARY KEY (tenant_id, audit_ref),
    CONSTRAINT fk_agent_runtime_memory_audit_head
        FOREIGN KEY (tenant_id, fact_id)
        REFERENCES public.agent_runtime_memory_fact_heads (tenant_id, fact_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_memory_audit_identity
        CHECK (btrim(audit_ref) <> '' AND btrim(idempotency_key) <> ''
        AND btrim(reason_code) <> ''),
    CONSTRAINT ck_agent_runtime_memory_audit_payload
        CHECK (command_digest ~ '^[0-9a-f]{64}$' AND current_revision > 0
        AND created_nano BETWEEN 0 AND 999999999)
);

CREATE INDEX ix_agent_runtime_memory_query
    ON public.agent_runtime_memory_fact_heads
        (tenant_id, end_user_id, agent_id, scope, type_id, current_revision);

CREATE OR REPLACE FUNCTION public.agent_runtime_memory_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime memory history is immutable';
END;
$$;

CREATE TRIGGER trg_agent_runtime_memory_revisions_immutable
BEFORE UPDATE OR DELETE ON public.agent_runtime_memory_fact_revisions
FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();
CREATE TRIGGER trg_agent_runtime_memory_sources_immutable
BEFORE UPDATE OR DELETE ON public.agent_runtime_memory_fact_sources
FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();
CREATE TRIGGER trg_agent_runtime_memory_operations_immutable
BEFORE UPDATE OR DELETE ON public.agent_runtime_memory_operations
FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();
CREATE TRIGGER trg_agent_runtime_memory_audits_immutable
BEFORE UPDATE OR DELETE ON public.agent_runtime_memory_conflict_audits
FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();

CREATE OR REPLACE FUNCTION public.agent_runtime_memory_head_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime memory head cannot be deleted';
    END IF;
    IF TG_OP = 'UPDATE' AND (
            NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
            OR NEW.fact_id IS DISTINCT FROM OLD.fact_id
            OR NEW.end_user_id IS DISTINCT FROM OLD.end_user_id
            OR NEW.agent_id IS DISTINCT FROM OLD.agent_id
            OR NEW.session_id IS DISTINCT FROM OLD.session_id
            OR NEW.scope IS DISTINCT FROM OLD.scope
            OR NEW.type_id IS DISTINCT FROM OLD.type_id
            OR NEW.current_revision <> OLD.current_revision + 1) THEN
        RAISE EXCEPTION 'agent runtime memory head identity or revision jump';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_agent_runtime_memory_head_guard
BEFORE UPDATE OR DELETE ON public.agent_runtime_memory_fact_heads
FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_head_guard();

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_memory_sources()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_memory_fact_sources s
         WHERE s.tenant_id = NEW.tenant_id AND s.fact_id = NEW.fact_id
           AND s.revision = NEW.revision) THEN
        RAISE EXCEPTION 'memory fact revision requires committed source events';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_agent_runtime_memory_sources_required
AFTER INSERT ON public.agent_runtime_memory_fact_revisions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_memory_sources();

ALTER TABLE public.agent_runtime_memory_fact_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_fact_revisions FORCE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_fact_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_fact_sources FORCE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_fact_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_fact_heads FORCE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_operations FORCE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_conflict_audits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_conflict_audits FORCE ROW LEVEL SECURITY;

CREATE POLICY agent_runtime_memory_revision_tenant
    ON public.agent_runtime_memory_fact_revisions
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));
CREATE POLICY agent_runtime_memory_source_tenant
    ON public.agent_runtime_memory_fact_sources
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));
CREATE POLICY agent_runtime_memory_head_tenant
    ON public.agent_runtime_memory_fact_heads
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));
CREATE POLICY agent_runtime_memory_operation_tenant
    ON public.agent_runtime_memory_operations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));
CREATE POLICY agent_runtime_memory_audit_tenant
    ON public.agent_runtime_memory_conflict_audits
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.agent_runtime_memory_fact_revisions IS
    'MemoryFact 的不可变全值 revision；head 只指向已提交 revision';
COMMENT ON TABLE public.agent_runtime_memory_fact_sources IS
    '每个 MemoryFact revision 的顺序 SessionLog 来源绑定';
COMMENT ON TABLE public.agent_runtime_memory_fact_heads IS
    'MemoryFact 当前 revision 与 scope-aware owner 索引';
COMMENT ON TABLE public.agent_runtime_memory_operations IS
    'tenant-scoped Memory mutation 原命令及首次输出 journal';
COMMENT ON TABLE public.agent_runtime_memory_conflict_audits IS
    '幂等异体与 revision CAS 冲突的独立稳定审计';

COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.tenant_id IS '租户隔离标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.fact_id IS 'MemoryFact 稳定标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.revision IS '从 1 连续递增的不可变版本';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.end_user_id IS '事实所属终端用户标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.agent_id IS '事实所属 Agent 标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.session_id IS 'SESSION scope 的会话标识；USER_AGENT scope 为空';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.scope IS '事实可见范围：SESSION 或 USER_AGENT';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.type_id IS '强类型 Memory schema 标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.status IS '该 revision 的六态生命周期状态';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.source_session_id IS '签发来源事件的会话标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.created_epoch_second IS '创建时间的 Unix epoch 秒';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.created_nano IS '创建时间的纳秒分量';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.valid_from_epoch_second IS '声明有效起点的 Unix epoch 秒';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.valid_from_nano IS '声明有效起点的纳秒分量';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.valid_to_epoch_second IS '声明有效终点的 Unix epoch 秒，可空';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.valid_to_nano IS '声明有效终点的纳秒分量，可空';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.expires_epoch_second IS '过期时间的 Unix epoch 秒，可空';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.expires_nano IS '过期时间的纳秒分量，可空';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.superseded_fact_id IS '被当前事实替换的 MemoryFact 标识，可空';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.fact_data IS '规范编码后的完整 MemoryFact revision';

COMMENT ON COLUMN public.agent_runtime_memory_fact_sources.tenant_id IS '租户隔离标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_sources.fact_id IS 'MemoryFact 稳定标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_sources.revision IS '来源所属事实版本';
COMMENT ON COLUMN public.agent_runtime_memory_fact_sources.source_ordinal IS '来源事件在该 revision 内的零基顺序';
COMMENT ON COLUMN public.agent_runtime_memory_fact_sources.agent_id IS '来源会话的 Agent 标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_sources.source_session_id IS '来源会话标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_sources.source_event_id IS '已提交 SessionLog 事件标识';

COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.tenant_id IS '租户隔离标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.fact_id IS 'MemoryFact 稳定标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.current_revision IS '当前可读取 revision';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.end_user_id IS '事实所属终端用户标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.agent_id IS '事实所属 Agent 标识';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.session_id IS 'SESSION scope 的会话标识；USER_AGENT scope 为空';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.scope IS '事实可见范围';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.type_id IS '强类型 Memory schema 标识';

COMMENT ON COLUMN public.agent_runtime_memory_operations.tenant_id IS '租户隔离标识';
COMMENT ON COLUMN public.agent_runtime_memory_operations.idempotency_key IS '调用方提供的 mutation 幂等键';
COMMENT ON COLUMN public.agent_runtime_memory_operations.command_kind IS '原始 mutation 类型';
COMMENT ON COLUMN public.agent_runtime_memory_operations.command_data IS '规范编码后的完整原始命令';
COMMENT ON COLUMN public.agent_runtime_memory_operations.result_fact_id IS '首次成功输出的 MemoryFact 标识';
COMMENT ON COLUMN public.agent_runtime_memory_operations.result_revision IS '首次成功输出的 revision';
COMMENT ON COLUMN public.agent_runtime_memory_operations.recorded_epoch_second IS '首次输出持久化时间的 Unix epoch 秒';
COMMENT ON COLUMN public.agent_runtime_memory_operations.recorded_nano IS '首次输出持久化时间的纳秒分量';

COMMENT ON COLUMN public.agent_runtime_memory_conflict_audits.tenant_id IS '租户隔离标识';
COMMENT ON COLUMN public.agent_runtime_memory_conflict_audits.audit_ref IS '稳定冲突审计标识';
COMMENT ON COLUMN public.agent_runtime_memory_conflict_audits.idempotency_key IS '触发冲突的 mutation 幂等键';
COMMENT ON COLUMN public.agent_runtime_memory_conflict_audits.fact_id IS '冲突发生时的 MemoryFact 标识';
COMMENT ON COLUMN public.agent_runtime_memory_conflict_audits.current_revision IS '冲突发生时观察到的当前 revision';
COMMENT ON COLUMN public.agent_runtime_memory_conflict_audits.reason_code IS '稳定冲突原因码';
COMMENT ON COLUMN public.agent_runtime_memory_conflict_audits.command_digest IS '冲突命令规范编码的 SHA-256 十六进制摘要';
COMMENT ON COLUMN public.agent_runtime_memory_conflict_audits.created_epoch_second IS '审计创建时间的 Unix epoch 秒';
COMMENT ON COLUMN public.agent_runtime_memory_conflict_audits.created_nano IS '审计创建时间的纳秒分量';

COMMENT ON CONSTRAINT pk_agent_runtime_memory_fact_revisions ON public.agent_runtime_memory_fact_revisions IS '按租户、fact 与 revision 唯一定位不可变事实';
COMMENT ON CONSTRAINT uq_agent_runtime_memory_revision_identity ON public.agent_runtime_memory_fact_revisions IS '为 head 的完整身份外键提供候选键，空 session 也按相同身份判定';
COMMENT ON CONSTRAINT fk_agent_runtime_memory_revision_source_owner ON public.agent_runtime_memory_fact_revisions IS '来源会话必须属于当前租户、Agent 与终端用户';
COMMENT ON CONSTRAINT fk_agent_runtime_memory_revision_scope_owner ON public.agent_runtime_memory_fact_revisions IS 'SESSION scope 的当前 owner 必须存在；USER_AGENT scope 允许空 session';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_revision_identity ON public.agent_runtime_memory_fact_revisions IS '拒绝空租户、空 fact 与非正 revision';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_revision_owner ON public.agent_runtime_memory_fact_revisions IS '拒绝非法用户、Agent 与类型标识';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_revision_scope ON public.agent_runtime_memory_fact_revisions IS '只允许冻结的 Memory scope';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_revision_session_scope ON public.agent_runtime_memory_fact_revisions IS 'SESSION 必须绑定来源会话，USER_AGENT 不得绑定 session';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_revision_status ON public.agent_runtime_memory_fact_revisions IS '只允许冻结的 MemoryFact 六态';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_revision_required_nanos ON public.agent_runtime_memory_fact_revisions IS '必填时间纳秒分量必须可构造 Instant';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_revision_optional_times ON public.agent_runtime_memory_fact_revisions IS '可选时间的秒与纳秒必须同时为空或同时存在';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_revision_valid_to_nano ON public.agent_runtime_memory_fact_revisions IS '有效终点纳秒分量必须可构造 Instant';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_revision_expires_nano ON public.agent_runtime_memory_fact_revisions IS '过期时间纳秒分量必须可构造 Instant';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_revision_superseded_fact ON public.agent_runtime_memory_fact_revisions IS '替换目标标识存在时不得为空白';
COMMENT ON CONSTRAINT fk_agent_runtime_memory_superseded_head ON public.agent_runtime_memory_fact_revisions IS '替换目标必须是已存在的 MemoryFact head';
COMMENT ON TRIGGER trg_agent_runtime_memory_sources_required ON public.agent_runtime_memory_fact_revisions IS '提交时保证每个事实 revision 至少绑定一个已提交 SessionLog 来源';

COMMENT ON CONSTRAINT pk_agent_runtime_memory_fact_sources ON public.agent_runtime_memory_fact_sources IS '每个 revision 的来源顺序唯一';
COMMENT ON CONSTRAINT uq_agent_runtime_memory_fact_source_event ON public.agent_runtime_memory_fact_sources IS '同一来源事件在一个 revision 中只允许出现一次';
COMMENT ON CONSTRAINT fk_agent_runtime_memory_source_revision ON public.agent_runtime_memory_fact_sources IS '来源必须指向同事务内存在的事实 revision';
COMMENT ON CONSTRAINT fk_agent_runtime_memory_source_session_event ON public.agent_runtime_memory_fact_sources IS '来源必须指向当前租户与 Agent 的已提交 SessionLog 事件';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_source_identity ON public.agent_runtime_memory_fact_sources IS '来源顺序非负且来源身份不得为空白';

COMMENT ON CONSTRAINT pk_agent_runtime_memory_fact_heads ON public.agent_runtime_memory_fact_heads IS '每个租户内一个 fact 只有一个 current head';
COMMENT ON CONSTRAINT fk_agent_runtime_memory_head_revision ON public.agent_runtime_memory_fact_heads IS 'head 的 revision 与完整 owner、scope、type 身份必须一致';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_head_identity ON public.agent_runtime_memory_fact_heads IS 'head 只接受合法 revision、用户、Agent 与类型标识';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_head_scope ON public.agent_runtime_memory_fact_heads IS 'SESSION head 必须带 session，USER_AGENT head 不得带 session';

COMMENT ON CONSTRAINT pk_agent_runtime_memory_operations ON public.agent_runtime_memory_operations IS 'tenant-scoped 幂等键只冻结一条原命令与首次输出';
COMMENT ON CONSTRAINT fk_agent_runtime_memory_operation_result ON public.agent_runtime_memory_operations IS 'operation 输出必须引用已提交事实 revision';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_operation_key ON public.agent_runtime_memory_operations IS '幂等键不得为空白';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_operation_kind ON public.agent_runtime_memory_operations IS '只允许冻结的三类 mutation';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_operation_result ON public.agent_runtime_memory_operations IS '输出 revision 与持久化纳秒必须有效';

COMMENT ON CONSTRAINT pk_agent_runtime_memory_conflict_audits ON public.agent_runtime_memory_conflict_audits IS '每个租户内审计标识唯一';
COMMENT ON CONSTRAINT fk_agent_runtime_memory_audit_head ON public.agent_runtime_memory_conflict_audits IS '冲突审计必须引用存在的 MemoryFact head';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_audit_identity ON public.agent_runtime_memory_conflict_audits IS '审计标识、幂等键与原因码不得为空白';
COMMENT ON CONSTRAINT ck_agent_runtime_memory_audit_payload ON public.agent_runtime_memory_conflict_audits IS '命令摘要、观察 revision 与审计纳秒必须有效';

COMMENT ON INDEX public.ix_agent_runtime_memory_query IS
    '按租户、owner、scope、type 与 current revision 读取查询候选';
