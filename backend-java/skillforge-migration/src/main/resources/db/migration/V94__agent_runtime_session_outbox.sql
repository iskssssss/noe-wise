-- V94: Agent Runtime SDK 用户 Session Outbox 的持久投递状态与 mutation journal。
-- 本迁移不复用、迁移或双写旧 session_outbox/session_outbox_sequences/webhook 表。

ALTER TABLE public.agent_runtime_session_events
    ADD CONSTRAINT uq_agent_runtime_session_event_outbox_reference
    UNIQUE (tenant_id, agent_id, session_id, end_user_id, session_sequence, event_id);

CREATE SEQUENCE public.agent_runtime_session_outbox_fencing_seq
    AS bigint
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.agent_runtime_session_outbox (
    tenant_id                       text NOT NULL,
    session_id                      text NOT NULL,
    agent_id                        text NOT NULL,
    end_user_id                     bigint NOT NULL,
    session_sequence                bigint NOT NULL,
    event_id                        text NOT NULL,
    destination                     text NOT NULL,
    initial_available_epoch_second  bigint NOT NULL,
    initial_available_nano          integer NOT NULL,
    available_epoch_second          bigint NOT NULL,
    available_nano                  integer NOT NULL,
    status                          text NOT NULL,
    outbox_revision                 bigint NOT NULL,
    attempts                        integer NOT NULL,
    lease_claim_id                  text,
    lease_worker_id                 text,
    lease_fencing_token             bigint,
    lease_until_epoch_second        bigint,
    lease_until_nano                integer,
    last_requested_epoch_second     bigint,
    last_requested_nano             integer,
    reason_code                     text,
    diagnostic_ref                  text,
    created_at                      timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at                      timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT agent_runtime_session_outbox_pkey
        PRIMARY KEY (tenant_id, agent_id, session_id, event_id),
    CONSTRAINT uq_agent_runtime_session_outbox_sequence
        UNIQUE (tenant_id, agent_id, session_id, session_sequence),
    CONSTRAINT agent_runtime_session_outbox_event_fkey
        FOREIGN KEY (
            tenant_id, agent_id, session_id, end_user_id, session_sequence, event_id)
        REFERENCES public.agent_runtime_session_events
            (tenant_id, agent_id, session_id, end_user_id, session_sequence, event_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_session_outbox_identity
        CHECK (btrim(tenant_id) <> '' AND btrim(session_id) <> ''
            AND btrim(agent_id) <> '' AND end_user_id > 0
            AND session_sequence > 0 AND btrim(event_id) <> ''),
    CONSTRAINT ck_agent_runtime_session_outbox_destination
        CHECK (btrim(destination) <> ''),
    CONSTRAINT ck_agent_runtime_session_outbox_status
        CHECK (status IN ('PENDING', 'DELIVERING', 'SENT', 'DEAD_LETTER')),
    CONSTRAINT ck_agent_runtime_session_outbox_revision_attempts
        CHECK (outbox_revision > 0 AND attempts >= 0),
    CONSTRAINT ck_agent_runtime_session_outbox_initial_available_epoch
        CHECK (initial_available_epoch_second >= -31557014167219200
            AND initial_available_epoch_second <= 31556889864403199
            AND initial_available_nano >= 0 AND initial_available_nano < 1000000000),
    CONSTRAINT ck_agent_runtime_session_outbox_available_epoch
        CHECK (available_epoch_second >= -31557014167219200
            AND available_epoch_second <= 31556889864403199
            AND available_nano >= 0 AND available_nano < 1000000000),
    CONSTRAINT ck_agent_runtime_session_outbox_lease_shape
        CHECK ((status = 'DELIVERING'
                AND lease_claim_id IS NOT NULL AND btrim(lease_claim_id) <> ''
                AND lease_worker_id IS NOT NULL AND btrim(lease_worker_id) <> ''
                AND lease_fencing_token IS NOT NULL AND lease_fencing_token > 0
                AND lease_until_epoch_second IS NOT NULL
                AND lease_until_epoch_second >= -31557014167219200
                AND lease_until_epoch_second <= 31556889864403199
                AND lease_until_nano IS NOT NULL
                AND lease_until_nano >= 0 AND lease_until_nano < 1000000000)
            OR (status <> 'DELIVERING'
                AND lease_claim_id IS NULL AND lease_worker_id IS NULL
                AND lease_fencing_token IS NULL
                AND lease_until_epoch_second IS NULL AND lease_until_nano IS NULL)),
    CONSTRAINT ck_agent_runtime_session_outbox_requested_at_pair
        CHECK ((last_requested_epoch_second IS NULL AND last_requested_nano IS NULL)
            OR (last_requested_epoch_second IS NOT NULL
                AND last_requested_epoch_second >= -31557014167219200
                AND last_requested_epoch_second <= 31556889864403199
                AND last_requested_nano IS NOT NULL
                AND last_requested_nano >= 0 AND last_requested_nano < 1000000000)),
    CONSTRAINT ck_agent_runtime_session_outbox_diagnostic_pair
        CHECK ((reason_code IS NULL AND diagnostic_ref IS NULL)
            OR (reason_code IS NOT NULL AND btrim(reason_code) <> ''
                AND diagnostic_ref IS NOT NULL AND btrim(diagnostic_ref) <> '')),
    CONSTRAINT ck_agent_runtime_session_outbox_dead_letter_diagnostic
        CHECK (status <> 'DEAD_LETTER'
            OR (reason_code IS NOT NULL AND diagnostic_ref IS NOT NULL)),
    CONSTRAINT ck_agent_runtime_session_outbox_timestamps
        CHECK (updated_at >= created_at)
);

CREATE INDEX idx_agent_runtime_session_outbox_head
    ON public.agent_runtime_session_outbox
        (tenant_id, agent_id, session_id, session_sequence)
    WHERE status IN ('PENDING', 'DELIVERING');

CREATE INDEX idx_agent_runtime_session_outbox_pending_schedule
    ON public.agent_runtime_session_outbox
        (tenant_id, status, available_epoch_second, available_nano,
         agent_id, session_id, session_sequence)
    WHERE status = 'PENDING';

CREATE TABLE public.nw_runtime_session_outbox_operations (
    tenant_id                    text NOT NULL,
    session_id                   text NOT NULL,
    agent_id                     text NOT NULL,
    event_id                     text NOT NULL,
    claim_id                     text NOT NULL,
    fencing_token                bigint NOT NULL,
    expected_revision            bigint NOT NULL,
    mutation_kind                text NOT NULL,
    command_fingerprint          text NOT NULL,
    retry_available_epoch_second bigint,
    retry_available_nano         integer,
    reason_code                  text,
    diagnostic_ref               text,
    output_revision              bigint NOT NULL,
    output_status                text NOT NULL,
    output_state_fingerprint     text NOT NULL,
    created_at                   timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_runtime_session_outbox_operations_pkey
        PRIMARY KEY (
            tenant_id, agent_id, session_id, event_id, claim_id, fencing_token,
            expected_revision, mutation_kind),
    CONSTRAINT agent_runtime_session_outbox_operation_entry_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, event_id)
        REFERENCES public.agent_runtime_session_outbox
            (tenant_id, agent_id, session_id, event_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_session_outbox_operation_identity
        CHECK (btrim(tenant_id) <> '' AND btrim(session_id) <> ''
            AND btrim(agent_id) <> '' AND btrim(event_id) <> ''
            AND btrim(claim_id) <> ''
            AND fencing_token > 0 AND expected_revision > 0),
    CONSTRAINT ck_agent_runtime_session_outbox_operation_kind
        CHECK (mutation_kind IN ('MARK_SENT', 'RETRY', 'DEAD_LETTER')),
    CONSTRAINT ck_agent_runtime_session_outbox_operation_fingerprints
        CHECK (btrim(command_fingerprint) <> ''
            AND btrim(output_state_fingerprint) <> ''),
    CONSTRAINT ck_agent_runtime_session_outbox_operation_output_revision
        CHECK (output_revision = expected_revision + 1),
    CONSTRAINT ck_agent_runtime_session_outbox_operation_command_shape
        CHECK ((mutation_kind = 'MARK_SENT'
                AND retry_available_epoch_second IS NULL
                AND retry_available_nano IS NULL
                AND reason_code IS NULL AND diagnostic_ref IS NULL
                AND output_status = 'SENT')
            OR (mutation_kind = 'RETRY'
                AND retry_available_epoch_second IS NOT NULL
                AND retry_available_epoch_second >= -31557014167219200
                AND retry_available_epoch_second <= 31556889864403199
                AND retry_available_nano IS NOT NULL
                AND retry_available_nano >= 0 AND retry_available_nano < 1000000000
                AND reason_code IS NOT NULL AND btrim(reason_code) <> ''
                AND diagnostic_ref IS NOT NULL AND btrim(diagnostic_ref) <> ''
                AND output_status = 'PENDING')
            OR (mutation_kind = 'DEAD_LETTER'
                AND retry_available_epoch_second IS NULL
                AND retry_available_nano IS NULL
                AND reason_code IS NOT NULL AND btrim(reason_code) <> ''
                AND diagnostic_ref IS NOT NULL AND btrim(diagnostic_ref) <> ''
                AND output_status = 'DEAD_LETTER'))
);

CREATE FUNCTION public.guard_agent_runtime_session_outbox()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING'
            OR NEW.outbox_revision <> 1
            OR NEW.attempts <> 0
            OR NEW.initial_available_epoch_second <> NEW.available_epoch_second
            OR NEW.initial_available_nano <> NEW.available_nano
            OR NEW.last_requested_epoch_second IS NOT NULL
            OR NEW.last_requested_nano IS NOT NULL
            OR NEW.reason_code IS NOT NULL
            OR NEW.diagnostic_ref IS NOT NULL THEN
            RAISE EXCEPTION 'new agent runtime session outbox entry must be initial PENDING';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime session outbox entry cannot be deleted';
    END IF;

    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.session_id IS DISTINCT FROM OLD.session_id
        OR NEW.agent_id IS DISTINCT FROM OLD.agent_id
        OR NEW.end_user_id IS DISTINCT FROM OLD.end_user_id
        OR NEW.session_sequence IS DISTINCT FROM OLD.session_sequence
        OR NEW.event_id IS DISTINCT FROM OLD.event_id
        OR NEW.destination IS DISTINCT FROM OLD.destination
        OR NEW.initial_available_epoch_second IS DISTINCT FROM OLD.initial_available_epoch_second
        OR NEW.initial_available_nano IS DISTINCT FROM OLD.initial_available_nano
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'agent runtime session outbox identity and original append are immutable';
    END IF;
    IF NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'agent runtime session outbox updated_at cannot move backwards';
    END IF;
    IF OLD.status IN ('SENT', 'DEAD_LETTER') THEN
        RAISE EXCEPTION 'terminal agent runtime session outbox entry is immutable';
    END IF;

    IF NEW.outbox_revision = OLD.outbox_revision THEN
        IF OLD.status <> 'DELIVERING' OR NEW.status <> 'DELIVERING'
            OR NEW.attempts <> OLD.attempts
            OR NEW.available_epoch_second <> OLD.available_epoch_second
            OR NEW.available_nano <> OLD.available_nano
            OR NEW.lease_claim_id IS DISTINCT FROM OLD.lease_claim_id
            OR NEW.lease_worker_id IS DISTINCT FROM OLD.lease_worker_id
            OR NEW.lease_fencing_token IS DISTINCT FROM OLD.lease_fencing_token
            OR (NEW.lease_until_epoch_second, NEW.lease_until_nano)
                <= (OLD.lease_until_epoch_second, OLD.lease_until_nano)
            OR NEW.last_requested_epoch_second IS DISTINCT FROM OLD.last_requested_epoch_second
            OR NEW.last_requested_nano IS DISTINCT FROM OLD.last_requested_nano
            OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
            OR NEW.diagnostic_ref IS DISTINCT FROM OLD.diagnostic_ref THEN
            RAISE EXCEPTION 'outbox revision may stay unchanged only for lease renewal';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.outbox_revision <> OLD.outbox_revision + 1 THEN
        RAISE EXCEPTION 'agent runtime session outbox revision must advance by one';
    END IF;

    IF OLD.status = 'PENDING' AND NEW.status = 'DELIVERING' THEN
        IF NEW.attempts <> OLD.attempts + 1
            OR NEW.available_epoch_second <> OLD.available_epoch_second
            OR NEW.available_nano <> OLD.available_nano
            OR NEW.last_requested_epoch_second IS NULL
            OR NEW.last_requested_nano IS NULL
            OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
            OR NEW.diagnostic_ref IS DISTINCT FROM OLD.diagnostic_ref THEN
            RAISE EXCEPTION 'pending outbox entry may only be claimed';
        END IF;
    ELSIF OLD.status = 'DELIVERING' AND NEW.status = 'DELIVERING' THEN
        IF NEW.attempts <> OLD.attempts + 1
            OR NEW.available_epoch_second <> OLD.available_epoch_second
            OR NEW.available_nano <> OLD.available_nano
            OR NEW.lease_fencing_token <= OLD.lease_fencing_token
            OR NEW.last_requested_epoch_second IS NULL
            OR NEW.last_requested_nano IS NULL
            OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
            OR NEW.diagnostic_ref IS DISTINCT FROM OLD.diagnostic_ref THEN
            RAISE EXCEPTION 'delivering outbox entry may only be reclaimed with a higher fence';
        END IF;
    ELSIF OLD.status = 'DELIVERING' AND NEW.status = 'SENT' THEN
        IF NEW.attempts <> OLD.attempts
            OR NEW.available_epoch_second <> OLD.available_epoch_second
            OR NEW.available_nano <> OLD.available_nano
            OR NEW.last_requested_epoch_second IS DISTINCT FROM OLD.last_requested_epoch_second
            OR NEW.last_requested_nano IS DISTINCT FROM OLD.last_requested_nano
            OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
            OR NEW.diagnostic_ref IS DISTINCT FROM OLD.diagnostic_ref THEN
            RAISE EXCEPTION 'delivering outbox entry may only be marked sent without fact drift';
        END IF;
    ELSIF OLD.status = 'DELIVERING' AND NEW.status = 'PENDING' THEN
        IF NEW.attempts <> OLD.attempts
            OR NEW.last_requested_epoch_second IS DISTINCT FROM OLD.last_requested_epoch_second
            OR NEW.last_requested_nano IS DISTINCT FROM OLD.last_requested_nano
            OR NEW.reason_code IS NULL OR NEW.diagnostic_ref IS NULL THEN
            RAISE EXCEPTION 'retry must preserve attempts and record its diagnostic';
        END IF;
    ELSIF OLD.status = 'DELIVERING' AND NEW.status = 'DEAD_LETTER' THEN
        IF NEW.attempts <> OLD.attempts
            OR NEW.available_epoch_second <> OLD.available_epoch_second
            OR NEW.available_nano <> OLD.available_nano
            OR NEW.last_requested_epoch_second IS DISTINCT FROM OLD.last_requested_epoch_second
            OR NEW.last_requested_nano IS DISTINCT FROM OLD.last_requested_nano
            OR NEW.reason_code IS NULL OR NEW.diagnostic_ref IS NULL THEN
            RAISE EXCEPTION 'dead letter must preserve attempts and record its diagnostic';
        END IF;
    ELSE
        RAISE EXCEPTION 'illegal agent runtime session outbox state transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_runtime_session_outbox_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.agent_runtime_session_outbox
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_outbox();

CREATE FUNCTION public.guard_agent_runtime_session_outbox_operation()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    current_revision bigint;
    current_status text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'agent runtime session outbox operation is immutable';
    END IF;

    SELECT outbox_revision, status
      INTO current_revision, current_status
     FROM public.agent_runtime_session_outbox
     WHERE tenant_id = NEW.tenant_id
       AND agent_id = NEW.agent_id
       AND session_id = NEW.session_id
       AND event_id = NEW.event_id;
    IF current_revision IS NULL
        OR NEW.output_revision > current_revision THEN
        RAISE EXCEPTION 'outbox operation output revision exceeds current entry revision';
    END IF;
    IF NEW.output_revision = current_revision
        AND NEW.output_status <> current_status THEN
        RAISE EXCEPTION 'outbox operation output status does not match current entry';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_runtime_session_outbox_operation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.nw_runtime_session_outbox_operations
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_outbox_operation();

CREATE FUNCTION public.assert_agent_runtime_session_outbox_mutation_journaled()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'DELIVERING'
        AND NEW.status IN ('PENDING', 'SENT', 'DEAD_LETTER')
        AND NOT EXISTS (
            SELECT 1
             FROM public.nw_runtime_session_outbox_operations operation
             WHERE operation.tenant_id = NEW.tenant_id
               AND operation.agent_id = NEW.agent_id
               AND operation.session_id = NEW.session_id
               AND operation.event_id = NEW.event_id
               AND operation.claim_id = OLD.lease_claim_id
               AND operation.fencing_token = OLD.lease_fencing_token
               AND operation.expected_revision = OLD.outbox_revision
               AND operation.output_revision = NEW.outbox_revision
               AND operation.output_status = NEW.status) THEN
        RAISE EXCEPTION 'outbox mutation must commit its operation journal';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_session_outbox_mutation_journal
    AFTER UPDATE ON public.agent_runtime_session_outbox
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_session_outbox_mutation_journaled();

ALTER TABLE public.agent_runtime_session_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_outbox_tenant_isolation
    ON public.agent_runtime_session_outbox
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.nw_runtime_session_outbox_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_outbox_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_outbox_operation_tenant_isolation
    ON public.nw_runtime_session_outbox_operations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON CONSTRAINT uq_agent_runtime_session_event_outbox_reference
    ON public.agent_runtime_session_events IS
    '为 Outbox 对完整 owner/sequence/event 事实的单一复合外键提供目标';
COMMENT ON SEQUENCE public.agent_runtime_session_outbox_fencing_seq IS
    '跨 Session 单调递增的 Outbox delivery fencing token；允许事务回滚留下空洞';
COMMENT ON TABLE public.agent_runtime_session_outbox IS
    'Agent Runtime SDK 用户可见 Session 事件的严格顺序投递状态；不复用旧 Session Outbox';
COMMENT ON COLUMN public.agent_runtime_session_outbox.tenant_id IS
    'Outbox 所属租户及强制 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_session_outbox.session_id IS
    'Outbox 所属 Session 标识';
COMMENT ON COLUMN public.agent_runtime_session_outbox.agent_id IS
    '由 V93 Session event 外键固定的 Agent 标识';
COMMENT ON COLUMN public.agent_runtime_session_outbox.end_user_id IS
    '由 V93 Session event 外键固定的终端用户标识';
COMMENT ON COLUMN public.agent_runtime_session_outbox.session_sequence IS
    '直接复用 V93 已提交事件的 Session sequence，不另行分配';
COMMENT ON COLUMN public.agent_runtime_session_outbox.event_id IS
    'Session 内稳定的 Outbox 幂等身份';
COMMENT ON COLUMN public.agent_runtime_session_outbox.destination IS
    '事件首次 append 后不可变的用户投递目的地';
COMMENT ON COLUMN public.agent_runtime_session_outbox.initial_available_epoch_second IS
    'append 幂等原值 availableAt 的 Unix epoch second';
COMMENT ON COLUMN public.agent_runtime_session_outbox.initial_available_nano IS
    'append 幂等原值 availableAt 的纳秒部分';
COMMENT ON COLUMN public.agent_runtime_session_outbox.available_epoch_second IS
    '当前排期 availableAt 的 Unix epoch second；只允许 retry 改写';
COMMENT ON COLUMN public.agent_runtime_session_outbox.available_nano IS
    '当前排期 availableAt 的纳秒部分；只允许 retry 改写';
COMMENT ON COLUMN public.agent_runtime_session_outbox.status IS
    'PENDING、DELIVERING、SENT 或 DEAD_LETTER 的受控投递状态';
COMMENT ON COLUMN public.agent_runtime_session_outbox.outbox_revision IS
    'claim/reclaim/mutation 每次加一；renew 保持不变的 CAS revision';
COMMENT ON COLUMN public.agent_runtime_session_outbox.attempts IS
    '首次 claim 与每次过期 reclaim 各增加一次的投递尝试数';
COMMENT ON COLUMN public.agent_runtime_session_outbox.lease_claim_id IS
    '当前 DELIVERING claim 的随机稳定标识';
COMMENT ON COLUMN public.agent_runtime_session_outbox.lease_worker_id IS
    '当前 DELIVERING claim 的 worker 标识';
COMMENT ON COLUMN public.agent_runtime_session_outbox.lease_fencing_token IS
    '当前 DELIVERING claim 的全局单调 fencing token';
COMMENT ON COLUMN public.agent_runtime_session_outbox.lease_until_epoch_second IS
    '存储时钟产生的 leaseUntil Unix epoch second';
COMMENT ON COLUMN public.agent_runtime_session_outbox.lease_until_nano IS
    '存储时钟产生的 leaseUntil 纳秒部分';
COMMENT ON COLUMN public.agent_runtime_session_outbox.last_requested_epoch_second IS
    '最近一次成功 claim/reclaim 请求携带的 requestedAt epoch second，仅作审计';
COMMENT ON COLUMN public.agent_runtime_session_outbox.last_requested_nano IS
    '最近一次成功 claim/reclaim 请求携带的 requestedAt 纳秒部分，仅作审计';
COMMENT ON COLUMN public.agent_runtime_session_outbox.reason_code IS
    '最近一次 retry 或永久 dead letter 的稳定失败原因；SENT 可保留该诊断';
COMMENT ON COLUMN public.agent_runtime_session_outbox.diagnostic_ref IS
    '与 reason_code 对应的去敏诊断事实引用，不进入公共 OutboxEntry';
COMMENT ON COLUMN public.agent_runtime_session_outbox.created_at IS
    '首次 append 的数据库时间，后续不可修改';
COMMENT ON COLUMN public.agent_runtime_session_outbox.updated_at IS
    '最近一次受控 claim、renew 或 mutation 的数据库时间';
COMMENT ON CONSTRAINT agent_runtime_session_outbox_pkey
    ON public.agent_runtime_session_outbox IS
    '同一租户 Agent Session event 只能存在一条用户 Outbox';
COMMENT ON CONSTRAINT uq_agent_runtime_session_outbox_sequence
    ON public.agent_runtime_session_outbox IS
    '同一租户 Agent 的 V93 Session sequence 只能进入一次用户 Outbox';
COMMENT ON CONSTRAINT agent_runtime_session_outbox_event_fkey
    ON public.agent_runtime_session_outbox IS
    '每条 Outbox 必须精确引用 V93 已提交的完整 owner/sequence/event 事实';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_identity
    ON public.agent_runtime_session_outbox IS
    'Outbox 的 tenant、Session、owner、sequence 与 event 身份必须有效';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_destination
    ON public.agent_runtime_session_outbox IS
    '投递 destination 必须包含非空白字符';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_status
    ON public.agent_runtime_session_outbox IS
    '投递状态只能取冻结 Outbox 状态机的四个值';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_revision_attempts
    ON public.agent_runtime_session_outbox IS
    'CAS revision 必须为正且投递尝试数不得为负';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_initial_available_epoch
    ON public.agent_runtime_session_outbox IS
    '首次 availableAt 秒/纳秒必须可无损重建 Java Instant';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_available_epoch
    ON public.agent_runtime_session_outbox IS
    '当前 availableAt 秒/纳秒必须可无损重建 Java Instant';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_lease_shape
    ON public.agent_runtime_session_outbox IS
    '只有 DELIVERING 同时持有完整 claim/worker/token/expiry lease';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_requested_at_pair
    ON public.agent_runtime_session_outbox IS
    '最近 requestedAt 必须为空或为完整且可重建的秒/纳秒对';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_diagnostic_pair
    ON public.agent_runtime_session_outbox IS
    '失败 reason 与去敏 diagnostic 引用必须同时为空或同时存在';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_dead_letter_diagnostic
    ON public.agent_runtime_session_outbox IS
    'DEAD_LETTER 必须携带永久失败原因与诊断引用';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_timestamps
    ON public.agent_runtime_session_outbox IS
    '数据库更新时间不得早于首次创建时间';
COMMENT ON INDEX public.idx_agent_runtime_session_outbox_head IS
    '按 Session sequence 定位每个 Session 的最小非终态 head';
COMMENT ON INDEX public.idx_agent_runtime_session_outbox_pending_schedule IS
    '按当前排期发现 PENDING Session head';
COMMENT ON TABLE public.nw_runtime_session_outbox_operations IS
    '不可变的 Outbox mutation 响应丢失重放 journal；不保存历史 entry 版本';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.tenant_id IS
    'operation 所属租户及强制 RLS 隔离键';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.session_id IS
    'operation 所属 Session 标识';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.agent_id IS
    'operation 所属 Agent 标识，与 Session 一同定位 Outbox entry';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.event_id IS
    'operation 指向的稳定 Outbox event 身份';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.claim_id IS
    '命令所持 lease claim 标识';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.fencing_token IS
    '命令所持 lease fencing token';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.expected_revision IS
    '命令要求的输入 Outbox revision';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.mutation_kind IS
    'MARK_SENT、RETRY 或 DEAD_LETTER';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.command_fingerprint IS
    '覆盖完整 lease、命令参数和诊断引用的 SHA-256 指纹';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.retry_available_epoch_second IS
    'RETRY 命令新 availableAt 的 Unix epoch second，其他 mutation 为空';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.retry_available_nano IS
    'RETRY 命令新 availableAt 的纳秒部分，其他 mutation 为空';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.reason_code IS
    'RETRY/DEAD_LETTER 命令的稳定原因，MARK_SENT 为空';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.diagnostic_ref IS
    'RETRY/DEAD_LETTER 命令的诊断引用，MARK_SENT 为空';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.output_revision IS
    '首次 mutation 提交的输出 revision；必须不超过 entry 当前 revision';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.output_status IS
    '首次 mutation 提交的 PENDING、SENT 或 DEAD_LETTER 输出状态';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.output_state_fingerprint IS
    '首次输出 entry 完整持久状态的 SHA-256 指纹，用于只重放仍匹配的结果';
COMMENT ON COLUMN public.nw_runtime_session_outbox_operations.created_at IS
    '首次 mutation 与 entry 变更同事务写入的数据库时间';
COMMENT ON CONSTRAINT skillforge_runtime_session_outbox_operations_pkey
    ON public.nw_runtime_session_outbox_operations IS
    '以 entry、claim、token、expected revision 与 mutation kind 唯一定位命令';
COMMENT ON CONSTRAINT agent_runtime_session_outbox_operation_entry_fkey
    ON public.nw_runtime_session_outbox_operations IS
    'journal 只引用稳定 Outbox entry，不拥有可删除的历史版本';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_operation_identity
    ON public.nw_runtime_session_outbox_operations IS
    'operation 的 entry/claim/fence/revision 身份必须有效';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_operation_kind
    ON public.nw_runtime_session_outbox_operations IS
    'journal 只记录三种会清除 lease 的 Outbox mutation';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_operation_fingerprints
    ON public.nw_runtime_session_outbox_operations IS
    '命令与输出状态必须各有非空白 SHA-256 指纹';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_operation_output_revision
    ON public.nw_runtime_session_outbox_operations IS
    'mutation 输出 revision 必须比命令 expected revision 精确增加一';
COMMENT ON CONSTRAINT ck_agent_runtime_session_outbox_operation_command_shape
    ON public.nw_runtime_session_outbox_operations IS
    '各 mutation kind 只保存其合法专用参数并对应确定输出状态';
COMMENT ON FUNCTION public.guard_agent_runtime_session_outbox() IS
    '阻止 Outbox 身份漂移、revision 跳跃、非法状态迁移及无 revision 的非 renew 更新';
COMMENT ON TRIGGER agent_runtime_session_outbox_guard
    ON public.agent_runtime_session_outbox IS
    '约束每条 Outbox 只按冻结状态机推进';
COMMENT ON FUNCTION public.guard_agent_runtime_session_outbox_operation() IS
    '拒绝 journal 改删及超出当前 entry 的 output revision/status';
COMMENT ON TRIGGER agent_runtime_session_outbox_operation_guard
    ON public.nw_runtime_session_outbox_operations IS
    'operation 只允许首次写入并引用已提交输出';
COMMENT ON FUNCTION public.assert_agent_runtime_session_outbox_mutation_journaled() IS
    '提交时拒绝缺少同事务 operation journal 的发送、重试或 dead letter 变更';
COMMENT ON TRIGGER agent_runtime_session_outbox_mutation_journal
    ON public.agent_runtime_session_outbox IS
    '延迟到事务提交时核对 mutation 与不可变 journal 原子完成';
