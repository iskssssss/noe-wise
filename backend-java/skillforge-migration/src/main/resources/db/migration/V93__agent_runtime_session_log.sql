-- V93: Agent Runtime SDK SessionLog 的独立 append-only 事实流。
-- 本迁移不包装或双写 chat_messages/session_outbox；宿主切换留给 SDK-4。

CREATE TABLE public.agent_runtime_session_owners (
    tenant_id      text NOT NULL,
    session_id     text NOT NULL,
    agent_id       text NOT NULL,
    end_user_id    bigint NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_session_owners_pkey
        PRIMARY KEY (tenant_id, agent_id, session_id),
    CONSTRAINT uq_agent_runtime_session_owner
        UNIQUE (tenant_id, agent_id, session_id, end_user_id),
    CONSTRAINT ck_agent_runtime_session_owner_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(session_id) <> ''
        AND btrim(agent_id) <> '' AND end_user_id > 0)
);

CREATE TABLE public.agent_runtime_session_streams (
    tenant_id      text NOT NULL,
    session_id     text NOT NULL,
    agent_id       text NOT NULL,
    end_user_id    bigint NOT NULL,
    next_sequence  bigint NOT NULL DEFAULT 1,
    created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_session_streams_pkey
        PRIMARY KEY (tenant_id, agent_id, session_id),
    CONSTRAINT uq_agent_runtime_session_stream_owner
        UNIQUE (tenant_id, agent_id, session_id, end_user_id),
    CONSTRAINT agent_runtime_session_stream_owner_root_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_session_owners
            (tenant_id, agent_id, session_id, end_user_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_session_stream_tenant
        CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_agent_runtime_session_stream_session
        CHECK (btrim(session_id) <> ''),
    CONSTRAINT ck_agent_runtime_session_stream_agent
        CHECK (btrim(agent_id) <> ''),
    CONSTRAINT ck_agent_runtime_session_stream_end_user
        CHECK (end_user_id > 0),
    CONSTRAINT ck_agent_runtime_session_stream_sequence
        CHECK (next_sequence > 0)
);

CREATE TABLE public.agent_runtime_session_events (
    tenant_id              text NOT NULL,
    session_id             text NOT NULL,
    agent_id               text NOT NULL,
    end_user_id            bigint NOT NULL,
    session_sequence       bigint NOT NULL,
    input_id               text NOT NULL,
    run_id                 text,
    execution_id           text,
    action_id              text,
    delegation_id          text,
    event_id               text NOT NULL,
    event_fingerprint      text NOT NULL,
    event_type             text NOT NULL,
    event_version          text NOT NULL,
    trace_id               text NOT NULL,
    span_id                text NOT NULL,
    root_input_id          text NOT NULL,
    caused_by_event_id     text,
    payload_schema_id      text NOT NULL,
    payload_canonical_json text NOT NULL,
    occurred_at_epoch_second bigint NOT NULL,
    occurred_at_nano       integer NOT NULL,
    CONSTRAINT agent_runtime_session_events_pkey
        PRIMARY KEY (tenant_id, agent_id, session_id, session_sequence),
    CONSTRAINT uq_agent_runtime_session_event_identity
        UNIQUE (tenant_id, agent_id, session_id, event_id),
    CONSTRAINT agent_runtime_session_event_owner_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_session_streams
            (tenant_id, agent_id, session_id, end_user_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_session_event_sequence
        CHECK (session_sequence > 0),
    CONSTRAINT ck_agent_runtime_session_event_input
        CHECK (btrim(input_id) <> ''),
    CONSTRAINT ck_agent_runtime_session_event_run
        CHECK (run_id IS NULL OR btrim(run_id) <> ''),
    CONSTRAINT ck_agent_runtime_session_event_execution
        CHECK (execution_id IS NULL OR btrim(execution_id) <> ''),
    CONSTRAINT ck_agent_runtime_session_event_action
        CHECK (action_id IS NULL OR btrim(action_id) <> ''),
    CONSTRAINT ck_agent_runtime_session_event_delegation
        CHECK (delegation_id IS NULL OR btrim(delegation_id) <> ''),
    CONSTRAINT ck_agent_runtime_session_event_identity_text
        CHECK (btrim(event_id) <> '' AND btrim(event_fingerprint) <> ''),
    CONSTRAINT ck_agent_runtime_session_event_schema
        CHECK (btrim(event_type) <> '' AND btrim(event_version) <> ''),
    CONSTRAINT ck_agent_runtime_session_event_trace
        CHECK (btrim(trace_id) <> '' AND btrim(span_id) <> ''
            AND btrim(root_input_id) <> ''
            AND (caused_by_event_id IS NULL OR btrim(caused_by_event_id) <> '')),
    CONSTRAINT ck_agent_runtime_session_event_payload
        CHECK (btrim(payload_schema_id) <> '' AND btrim(payload_canonical_json) <> ''),
    CONSTRAINT ck_agent_runtime_session_event_occurred_at_epoch
        CHECK (occurred_at_epoch_second >= -31557014167219200
            AND occurred_at_epoch_second <= 31556889864403199),
    CONSTRAINT ck_agent_runtime_session_event_occurred_at_nano
        CHECK (occurred_at_nano >= 0 AND occurred_at_nano < 1000000000)
);

CREATE INDEX idx_agent_runtime_session_events_read
    ON public.agent_runtime_session_events
        (tenant_id, agent_id, session_id, end_user_id, session_sequence);

CREATE FUNCTION public.reject_agent_runtime_session_owner_mutation()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime session owner root is immutable';
    RETURN NULL;
END;
$$;

CREATE TRIGGER agent_runtime_session_owner_immutable
    BEFORE UPDATE OR DELETE ON public.agent_runtime_session_owners
    FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_session_owner_mutation();

CREATE FUNCTION public.guard_agent_runtime_session_stream()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.next_sequence <> 1 THEN
            RAISE EXCEPTION 'new agent runtime session stream sequence must start at one';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime session stream is immutable';
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.session_id IS DISTINCT FROM OLD.session_id
        OR NEW.agent_id IS DISTINCT FROM OLD.agent_id
        OR NEW.end_user_id IS DISTINCT FROM OLD.end_user_id
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'agent runtime session owner is immutable';
    END IF;
    IF NEW.next_sequence <> OLD.next_sequence + 1 THEN
        RAISE EXCEPTION 'agent runtime session sequence must advance by one';
    END IF;
    IF NOT EXISTS (
        SELECT 1
         FROM public.agent_runtime_session_events
         WHERE tenant_id = OLD.tenant_id
           AND agent_id = OLD.agent_id
           AND session_id = OLD.session_id
           AND session_sequence = OLD.next_sequence
    ) THEN
        RAISE EXCEPTION 'agent runtime session advance requires its event';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_runtime_session_stream_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.agent_runtime_session_streams
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_stream();

CREATE FUNCTION public.reject_agent_runtime_session_event_mutation()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime session event is append-only';
    RETURN NULL;
END;
$$;

CREATE TRIGGER agent_runtime_session_event_immutable
    BEFORE UPDATE OR DELETE ON public.agent_runtime_session_events
    FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_session_event_mutation();

CREATE FUNCTION public.guard_agent_runtime_session_event_append()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    stream_next_sequence bigint;
BEGIN
    SELECT next_sequence
      INTO stream_next_sequence
      FROM public.agent_runtime_session_streams
     WHERE tenant_id = NEW.tenant_id
       AND agent_id = NEW.agent_id
       AND session_id = NEW.session_id
     FOR UPDATE;

    IF stream_next_sequence IS NULL
        OR NEW.session_sequence <> stream_next_sequence THEN
        RAISE EXCEPTION 'agent runtime session event must use next sequence';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_runtime_session_event_append_guard
    BEFORE INSERT ON public.agent_runtime_session_events
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_event_append();

CREATE FUNCTION public.assert_agent_runtime_session_event_advanced()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    stream_next_sequence bigint;
BEGIN
    SELECT next_sequence
      INTO stream_next_sequence
      FROM public.agent_runtime_session_streams
     WHERE tenant_id = NEW.tenant_id
       AND agent_id = NEW.agent_id
       AND session_id = NEW.session_id;

    IF stream_next_sequence IS NULL
        OR stream_next_sequence <= NEW.session_sequence THEN
        RAISE EXCEPTION 'agent runtime session event was not advanced';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_session_event_advance_consistency
    AFTER INSERT ON public.agent_runtime_session_events
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_session_event_advanced();

ALTER TABLE public.agent_runtime_session_owners ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_owners FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_owner_tenant_isolation
    ON public.agent_runtime_session_owners
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_session_streams ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_streams FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_stream_tenant_isolation
    ON public.agent_runtime_session_streams
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_session_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_events FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_event_tenant_isolation
    ON public.agent_runtime_session_events
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.agent_runtime_session_owners IS
    'SessionLog、SessionPersistence 与 SessionStore 共用的不可变完整 owner root';
COMMENT ON COLUMN public.agent_runtime_session_owners.tenant_id IS
    'Session 所属租户；也是 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_session_owners.session_id IS
    '与 tenant、agent 共同组成跨 Session 持久域资源键';
COMMENT ON COLUMN public.agent_runtime_session_owners.agent_id IS
    'Session 建立时冻结的 Agent 标识';
COMMENT ON COLUMN public.agent_runtime_session_owners.end_user_id IS
    '资源键首次写入时冻结的终端用户标识';
COMMENT ON COLUMN public.agent_runtime_session_owners.created_at IS
    'owner root 首次建立的数据库时间';
COMMENT ON CONSTRAINT agent_runtime_session_owners_pkey
    ON public.agent_runtime_session_owners IS
    '同一 tenant、agent、session 只有一个线性化 owner root';
COMMENT ON CONSTRAINT uq_agent_runtime_session_owner
    ON public.agent_runtime_session_owners IS
    '为三个 Session 持久域提供完整 owner 外键目标';
COMMENT ON CONSTRAINT ck_agent_runtime_session_owner_identity
    ON public.agent_runtime_session_owners IS
    'owner 的文本标识非空白且 end_user_id 为正数';
COMMENT ON TABLE public.agent_runtime_session_streams IS
    'Agent Runtime SDK SessionLog 的固定 owner 与唯一下一 sequence；不复用旧 Session Outbox allocator';
COMMENT ON COLUMN public.agent_runtime_session_streams.tenant_id IS
    'Session 所属租户；也是 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_session_streams.session_id IS
    '租户内 Session 的稳定标识';
COMMENT ON COLUMN public.agent_runtime_session_streams.agent_id IS
    'Session 建立时冻结的 Agent 标识，后续不可修改';
COMMENT ON COLUMN public.agent_runtime_session_streams.end_user_id IS
    'Session 建立时冻结的终端用户标识，后续不可修改';
COMMENT ON COLUMN public.agent_runtime_session_streams.next_sequence IS
    '下一次成功 append 分配的正数；冲突、重放与回滚不得推进';
COMMENT ON COLUMN public.agent_runtime_session_streams.created_at IS
    'Session stream 首次建立的数据库时间，后续不可修改';
COMMENT ON CONSTRAINT agent_runtime_session_streams_pkey
    ON public.agent_runtime_session_streams IS
    '每个租户 Agent 的 Session 只允许一个 sequence stream';
COMMENT ON CONSTRAINT uq_agent_runtime_session_stream_owner
    ON public.agent_runtime_session_streams IS
    '为 event 的完整不可变 Session owner 提供复合外键目标';
COMMENT ON CONSTRAINT agent_runtime_session_stream_owner_root_fkey
    ON public.agent_runtime_session_streams IS
    'SessionLog stream 必须引用跨持久域共用的完整 owner root';
COMMENT ON CONSTRAINT ck_agent_runtime_session_stream_tenant
    ON public.agent_runtime_session_streams IS
    'tenant_id 必须包含非空白字符';
COMMENT ON CONSTRAINT ck_agent_runtime_session_stream_session
    ON public.agent_runtime_session_streams IS
    'session_id 必须包含非空白字符';
COMMENT ON CONSTRAINT ck_agent_runtime_session_stream_agent
    ON public.agent_runtime_session_streams IS
    'agent_id 必须包含非空白字符';
COMMENT ON CONSTRAINT ck_agent_runtime_session_stream_end_user
    ON public.agent_runtime_session_streams IS
    'end_user_id 必须为正数';
COMMENT ON CONSTRAINT ck_agent_runtime_session_stream_sequence
    ON public.agent_runtime_session_streams IS
    'next_sequence 必须保持正数';
COMMENT ON TABLE public.agent_runtime_session_events IS
    'Agent Runtime SDK append-only SessionEvent 事实；完整 envelope 可跨进程精确重放';
COMMENT ON COLUMN public.agent_runtime_session_events.tenant_id IS
    '事件所属租户；也是 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_session_events.session_id IS
    '事件所属 Session 标识';
COMMENT ON COLUMN public.agent_runtime_session_events.agent_id IS
    '从 Session stream 冻结并由复合外键约束的 Agent 标识';
COMMENT ON COLUMN public.agent_runtime_session_events.end_user_id IS
    '从 Session stream 冻结并由复合外键约束的终端用户标识';
COMMENT ON COLUMN public.agent_runtime_session_events.session_sequence IS
    'Session 内从一开始连续分配的提交顺序';
COMMENT ON COLUMN public.agent_runtime_session_events.input_id IS
    '事件投影绑定的输入标识';
COMMENT ON COLUMN public.agent_runtime_session_events.run_id IS
    '事件投影可选绑定的 Run 标识';
COMMENT ON COLUMN public.agent_runtime_session_events.execution_id IS
    '事件投影可选绑定的 Workflow execution 标识';
COMMENT ON COLUMN public.agent_runtime_session_events.action_id IS
    '事件投影可选绑定的 Action 标识';
COMMENT ON COLUMN public.agent_runtime_session_events.delegation_id IS
    '事件投影可选绑定的 Delegation 标识';
COMMENT ON COLUMN public.agent_runtime_session_events.event_id IS
    'Session 内稳定的幂等事件标识';
COMMENT ON COLUMN public.agent_runtime_session_events.event_fingerprint IS
    '调用方冻结的完整事件草稿指纹，用于精确 replay/conflict 判定';
COMMENT ON COLUMN public.agent_runtime_session_events.event_type IS
    '注册的 Session 事件类型';
COMMENT ON COLUMN public.agent_runtime_session_events.event_version IS
    'Session 事件 schema 版本字符串';
COMMENT ON COLUMN public.agent_runtime_session_events.trace_id IS
    '事件完整 trace envelope 的 trace 标识';
COMMENT ON COLUMN public.agent_runtime_session_events.span_id IS
    '事件完整 trace envelope 的 span 标识';
COMMENT ON COLUMN public.agent_runtime_session_events.root_input_id IS
    '事件完整 trace envelope 的根输入标识';
COMMENT ON COLUMN public.agent_runtime_session_events.caused_by_event_id IS
    '事件完整 trace envelope 的可选直接原因事件标识';
COMMENT ON COLUMN public.agent_runtime_session_events.payload_schema_id IS
    'TypedPayload 的注册 schema 标识';
COMMENT ON COLUMN public.agent_runtime_session_events.payload_canonical_json IS
    'TypedPayload 的规范 JSON 原文；以 text 保存，禁止数据库重排';
COMMENT ON COLUMN public.agent_runtime_session_events.occurred_at_epoch_second IS
    'occurredAt 的 Unix epoch second；与 nano 列共同保留 Java Instant 全精度';
COMMENT ON COLUMN public.agent_runtime_session_events.occurred_at_nano IS
    'occurredAt 的 0..999999999 纳秒部分；禁止 timestamptz 微秒截断破坏精确重放';
COMMENT ON CONSTRAINT agent_runtime_session_events_pkey
    ON public.agent_runtime_session_events IS
    '同一租户 Agent Session sequence 只能对应一个已提交事件';
COMMENT ON CONSTRAINT uq_agent_runtime_session_event_identity
    ON public.agent_runtime_session_events IS
    '同一租户 Agent Session event_id 只能首次追加一次，后续必须 replay 或 conflict';
COMMENT ON CONSTRAINT agent_runtime_session_event_owner_fkey
    ON public.agent_runtime_session_events IS
    '事件完整 owner 必须与既有 Session stream 完全一致';
COMMENT ON CONSTRAINT ck_agent_runtime_session_event_sequence
    ON public.agent_runtime_session_events IS
    '已提交 session_sequence 必须为正数';
COMMENT ON CONSTRAINT ck_agent_runtime_session_event_input
    ON public.agent_runtime_session_events IS
    'input_id 必须包含非空白字符';
COMMENT ON CONSTRAINT ck_agent_runtime_session_event_run
    ON public.agent_runtime_session_events IS
    '可选 run_id 出现时必须包含非空白字符';
COMMENT ON CONSTRAINT ck_agent_runtime_session_event_execution
    ON public.agent_runtime_session_events IS
    '可选 execution_id 出现时必须包含非空白字符';
COMMENT ON CONSTRAINT ck_agent_runtime_session_event_action
    ON public.agent_runtime_session_events IS
    '可选 action_id 出现时必须包含非空白字符';
COMMENT ON CONSTRAINT ck_agent_runtime_session_event_delegation
    ON public.agent_runtime_session_events IS
    '可选 delegation_id 出现时必须包含非空白字符';
COMMENT ON CONSTRAINT ck_agent_runtime_session_event_identity_text
    ON public.agent_runtime_session_events IS
    'event_id 与 event_fingerprint 必须包含非空白字符';
COMMENT ON CONSTRAINT ck_agent_runtime_session_event_schema
    ON public.agent_runtime_session_events IS
    'event_type 与 event_version 必须包含非空白字符';
COMMENT ON CONSTRAINT ck_agent_runtime_session_event_trace
    ON public.agent_runtime_session_events IS
    'trace envelope 的必填标识非空白，可选原因标识出现时非空白';
COMMENT ON CONSTRAINT ck_agent_runtime_session_event_payload
    ON public.agent_runtime_session_events IS
    'payload schema 与 canonical JSON 原文必须包含非空白字符';
COMMENT ON CONSTRAINT ck_agent_runtime_session_event_occurred_at_epoch
    ON public.agent_runtime_session_events IS
    'occurredAt epoch second 必须位于 Java Instant 可重建范围';
COMMENT ON CONSTRAINT ck_agent_runtime_session_event_occurred_at_nano
    ON public.agent_runtime_session_events IS
    'occurredAt 纳秒部分必须位于 Java Instant 的合法范围';
COMMENT ON INDEX public.idx_agent_runtime_session_events_read IS
    '按完整 Session owner 与 sequence 游标读取有界事件页';
COMMENT ON FUNCTION public.guard_agent_runtime_session_event_append() IS
    '锁定 stream 并拒绝不等于当前 next_sequence 的事件插入';
COMMENT ON TRIGGER agent_runtime_session_event_append_guard
    ON public.agent_runtime_session_events IS
    '每次事件插入必须精确消费当前 Session sequence';
COMMENT ON FUNCTION public.assert_agent_runtime_session_event_advanced() IS
    '提交时拒绝未由 stream allocator 消费的新增事件';
COMMENT ON TRIGGER agent_runtime_session_event_advance_consistency
    ON public.agent_runtime_session_events IS
    '延迟到事务提交时校验新增事件与 allocator 推进原子完成';
