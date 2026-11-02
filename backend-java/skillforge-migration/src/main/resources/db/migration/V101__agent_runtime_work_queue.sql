-- 后台工作持久队列与 Scheduler 分离，ACK 必须引用已提交业务事实。
CREATE SEQUENCE public.agent_runtime_work_queue_fencing_seq AS bigint START WITH 1;
COMMENT ON SEQUENCE public.agent_runtime_work_queue_fencing_seq IS '后台工作每次领取与过期接管的递增 fencing token';

CREATE TABLE public.nw_runtime_work_queue_items (
    tenant_id text NOT NULL CHECK (btrim(tenant_id) <> ''),
    work_id text NOT NULL CHECK (btrim(work_id) <> ''),
    idempotency_key text NOT NULL CHECK (btrim(idempotency_key) <> ''),
    submission_data text NOT NULL CHECK (submission_data <> ''),
    state text NOT NULL CHECK (state IN ('QUEUED','CLAIMED','ACKNOWLEDGED','DEAD_LETTER')),
    revision bigint NOT NULL CHECK (revision > 0),
    attempt integer NOT NULL CHECK (attempt >= 0),
    available_second bigint NOT NULL,
    available_nano integer NOT NULL CHECK (available_nano BETWEEN 0 AND 999999999),
    lease_claim text,
    lease_worker text,
    lease_token bigint,
    lease_second bigint,
    lease_nano integer,
    failure_code text,
    failure_retry text,
    failure_ref text,
    updated_second bigint NOT NULL,
    updated_nano integer NOT NULL CHECK (updated_nano BETWEEN 0 AND 999999999),
    recorded_second bigint NOT NULL,
    recorded_nano integer NOT NULL CHECK (recorded_nano BETWEEN 0 AND 999999999),
    CONSTRAINT agent_runtime_work_queue_delivery_pk PRIMARY KEY (tenant_id,work_id),
    CONSTRAINT agent_runtime_work_queue_delivery_idempotency UNIQUE (tenant_id,idempotency_key),
    CONSTRAINT agent_runtime_work_queue_delivery_lease CHECK (
        (state = 'CLAIMED' AND lease_claim IS NOT NULL AND btrim(lease_claim) <> ''
            AND lease_worker IS NOT NULL AND btrim(lease_worker) <> '' AND lease_token IS NOT NULL AND lease_token > 0
            AND lease_second IS NOT NULL AND lease_nano IS NOT NULL AND lease_nano BETWEEN 0 AND 999999999)
        OR (state <> 'CLAIMED' AND lease_claim IS NULL AND lease_worker IS NULL
            AND lease_token IS NULL AND lease_second IS NULL AND lease_nano IS NULL)),
    CONSTRAINT agent_runtime_work_queue_delivery_failure CHECK (
        (failure_code IS NULL AND failure_retry IS NULL AND failure_ref IS NULL)
        OR (failure_code IS NOT NULL AND btrim(failure_code) <> '' AND failure_ref IS NOT NULL AND btrim(failure_ref) <> ''
            AND failure_retry IS NOT NULL AND failure_retry IN ('NONE','IDEMPOTENT_BY_STABLE_KEY'))),
    CONSTRAINT agent_runtime_work_queue_delivery_matrix CHECK (
        (attempt = 0 AND state = 'QUEUED' AND failure_code IS NULL)
        OR (attempt > 0 AND (
            (state = 'QUEUED' AND failure_retry IS NOT NULL AND failure_retry = 'IDEMPOTENT_BY_STABLE_KEY')
            OR (state = 'CLAIMED' AND (failure_code IS NULL OR failure_retry = 'IDEMPOTENT_BY_STABLE_KEY'))
            OR (state = 'ACKNOWLEDGED' AND failure_code IS NULL)
            OR (state = 'DEAD_LETTER' AND failure_retry IS NOT NULL AND failure_retry = 'NONE'))))
);

CREATE TABLE public.nw_runtime_work_queue_operations (
    tenant_id text NOT NULL,
    work_id text NOT NULL,
    operation_id text NOT NULL CHECK (btrim(operation_id) <> ''),
    command_data text NOT NULL CHECK (command_data <> ''),
    output_revision bigint NOT NULL CHECK (output_revision > 1),
    CONSTRAINT agent_runtime_work_queue_operation_pk PRIMARY KEY (tenant_id,work_id,operation_id),
    CONSTRAINT agent_runtime_work_queue_operation_delivery_fk FOREIGN KEY (tenant_id,work_id)
        REFERENCES public.nw_runtime_work_queue_items (tenant_id,work_id) ON DELETE RESTRICT
);

-- 初次绑定可能因另一个工作占用幂等键而冲突，故审计不要求工作行外键。
CREATE TABLE public.nw_runtime_work_queue_conflict_audits (
    tenant_id text NOT NULL CHECK (btrim(tenant_id) <> ''),
    work_id text NOT NULL CHECK (btrim(work_id) <> ''),
    command_digest text NOT NULL CHECK (command_digest ~ '^[0-9a-f]{64}$'),
    audit_ref text NOT NULL CHECK (btrim(audit_ref) <> ''),
    CONSTRAINT agent_runtime_work_queue_audit_pk PRIMARY KEY (tenant_id,work_id,command_digest),
    CONSTRAINT agent_runtime_work_queue_audit_ref UNIQUE (audit_ref)
);

CREATE INDEX agent_runtime_work_queue_ready ON public.nw_runtime_work_queue_items
    (tenant_id,available_second,available_nano,work_id) WHERE state = 'QUEUED';
CREATE INDEX agent_runtime_work_queue_expired ON public.nw_runtime_work_queue_items
    (tenant_id,lease_second,lease_nano) WHERE state = 'CLAIMED';
COMMENT ON INDEX public.agent_runtime_work_queue_ready IS '按租户扫描已到期 QUEUED 工作';
COMMENT ON INDEX public.agent_runtime_work_queue_expired IS '按租户扫描失效 CLAIMED lease';

CREATE FUNCTION public.guard_agent_runtime_work_queue_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE now_value numeric; now_second bigint; now_nano integer;
BEGIN
    now_value := extract(epoch FROM clock_timestamp());
    now_second := floor(now_value)::bigint;
    now_nano := ((now_value - floor(now_value)) * 1000000000)::integer;
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'work_queue_delivery_delete_forbidden'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'QUEUED' OR NEW.revision <> 1 OR NEW.attempt <> 0 THEN
            RAISE EXCEPTION 'work_queue_delivery_initial_state';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id,NEW.work_id,NEW.idempotency_key,NEW.submission_data,NEW.recorded_second,NEW.recorded_nano)
        IS DISTINCT FROM (OLD.tenant_id,OLD.work_id,OLD.idempotency_key,OLD.submission_data,OLD.recorded_second,OLD.recorded_nano)
        OR NEW.revision <> OLD.revision + 1 OR OLD.state IN ('ACKNOWLEDGED','DEAD_LETTER') THEN
        RAISE EXCEPTION 'work_queue_delivery_immutable_or_revision';
    END IF;
    IF NEW.state = 'CLAIMED' AND NEW.attempt = OLD.attempt + 1 THEN
        IF OLD.state NOT IN ('QUEUED','CLAIMED') OR (OLD.lease_token IS NOT NULL AND NEW.lease_token <= OLD.lease_token)
            OR (NEW.lease_second,NEW.lease_nano) <= (now_second,now_nano)
            OR (OLD.state = 'QUEUED' AND (OLD.available_second,OLD.available_nano) > (now_second,now_nano))
            OR (OLD.state = 'CLAIMED' AND (OLD.lease_second,OLD.lease_nano) > (now_second,now_nano))
            OR NEW.lease_claim IS NOT DISTINCT FROM OLD.lease_claim
            OR (NEW.failure_code,NEW.failure_retry,NEW.failure_ref) IS DISTINCT FROM (OLD.failure_code,OLD.failure_retry,OLD.failure_ref) THEN
            RAISE EXCEPTION 'work_queue_delivery_claim_mismatch';
        END IF;
    ELSIF OLD.state = 'CLAIMED' AND NEW.attempt = OLD.attempt THEN
        IF (OLD.lease_second,OLD.lease_nano) <= (now_second,now_nano) THEN RAISE EXCEPTION 'work_queue_delivery_expired'; END IF;
        IF NEW.state = 'CLAIMED' AND (
            (NEW.lease_claim,NEW.lease_worker,NEW.lease_token) IS DISTINCT FROM (OLD.lease_claim,OLD.lease_worker,OLD.lease_token)
            OR (NEW.lease_second,NEW.lease_nano) <= (OLD.lease_second,OLD.lease_nano)
            OR (NEW.failure_code,NEW.failure_retry,NEW.failure_ref) IS DISTINCT FROM (OLD.failure_code,OLD.failure_retry,OLD.failure_ref)) THEN
            RAISE EXCEPTION 'work_queue_delivery_renew_mismatch';
        END IF;
    ELSE RAISE EXCEPTION 'work_queue_delivery_transition';
    END IF;
    IF NEW.state <> 'QUEUED' AND (NEW.available_second,NEW.available_nano) IS DISTINCT FROM (OLD.available_second,OLD.available_nano) THEN
        RAISE EXCEPTION 'work_queue_delivery_availability_immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_runtime_work_queue_delivery_guard BEFORE INSERT OR UPDATE OR DELETE ON public.nw_runtime_work_queue_items
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_work_queue_delivery();
COMMENT ON FUNCTION public.guard_agent_runtime_work_queue_delivery() IS '冻结原提交并约束领取、续期、重排及终态的单步 revision/attempt';

CREATE FUNCTION public.guard_agent_runtime_work_queue_journal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'nw_runtime_work_queue_conflict_audits' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'work_queue_journal_immutable';
END $$;
CREATE TRIGGER agent_runtime_work_queue_operation_guard BEFORE UPDATE OR DELETE ON public.nw_runtime_work_queue_operations
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_work_queue_journal();
CREATE TRIGGER agent_runtime_work_queue_audit_guard BEFORE UPDATE OR DELETE ON public.nw_runtime_work_queue_conflict_audits
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_work_queue_journal();
COMMENT ON FUNCTION public.guard_agent_runtime_work_queue_journal() IS '禁止改写或删除 operation 与冲突审计事实，仅允许精确审计重放';

CREATE FUNCTION public.check_agent_runtime_work_queue_operation_closure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'nw_runtime_work_queue_operations' THEN
        IF NOT EXISTS (SELECT 1 FROM public.nw_runtime_work_queue_items d WHERE d.tenant_id = NEW.tenant_id
            AND d.work_id = NEW.work_id AND d.revision = NEW.output_revision) THEN
            RAISE EXCEPTION 'work_queue_operation_output_mismatch';
        END IF;
    ELSIF NEW.attempt = OLD.attempt AND NOT EXISTS (
        SELECT 1 FROM public.nw_runtime_work_queue_operations o WHERE o.tenant_id = NEW.tenant_id
            AND o.work_id = NEW.work_id AND o.output_revision = NEW.revision) THEN
        RAISE EXCEPTION 'work_queue_operation_missing';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER agent_runtime_work_queue_operation_closure AFTER INSERT ON public.nw_runtime_work_queue_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_work_queue_operation_closure();
CREATE CONSTRAINT TRIGGER agent_runtime_work_queue_delivery_closure AFTER UPDATE ON public.nw_runtime_work_queue_items
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_work_queue_operation_closure();
COMMENT ON FUNCTION public.check_agent_runtime_work_queue_operation_closure() IS '工作 mutation 与 journal 输出必须在同一提交闭合';

ALTER TABLE public.nw_runtime_work_queue_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_work_queue_items FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_work_queue_delivery_tenant ON public.nw_runtime_work_queue_items
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));
ALTER TABLE public.nw_runtime_work_queue_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_work_queue_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_work_queue_operation_tenant ON public.nw_runtime_work_queue_operations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));
ALTER TABLE public.nw_runtime_work_queue_conflict_audits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_work_queue_conflict_audits FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_work_queue_audit_tenant ON public.nw_runtime_work_queue_conflict_audits
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));

COMMENT ON TABLE public.nw_runtime_work_queue_items IS '每租户 work 的独立工作状态，不拥有 Session sequence 或业务状态';
COMMENT ON TABLE public.nw_runtime_work_queue_operations IS '完整规范 mutation 与其已提交输出 revision，响应未知时使用原命令收敛';
COMMENT ON TABLE public.nw_runtime_work_queue_conflict_audits IS '双向幂等绑定或 mutation 内容漂移的稳定摘要审计';



COMMENT ON COLUMN public.nw_runtime_work_queue_items.tenant_id IS '所属租户，事务本地 RLS 身份';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.work_id IS '租户内后台工作的稳定身份';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.idempotency_key IS '与 workRef 双向绑定的租户内稳定键';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.submission_data IS '完整冻结 WorkSubmission 规范快照，含原身份、handler、命令与首次时间';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.state IS 'QUEUED/CLAIMED/ACKNOWLEDGED/DEAD_LETTER 工作状态';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.revision IS '工作领取与 mutation 单调 revision';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.attempt IS '成功领取次数，初始为零';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.available_second IS '下一次可领取时间 epoch 秒';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.available_nano IS '下一次可领取时间纳秒分量';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.lease_claim IS '当前 claim 唯一身份';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.lease_worker IS '当前 worker 身份';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.lease_token IS '单调 fencing token';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.lease_second IS '当前租约到期 epoch 秒';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.lease_nano IS '当前租约到期纳秒分量';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.failure_code IS '上次处理失败的稳定原因';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.failure_retry IS '固定重试语义，重排为稳定键重试，dead-letter为NONE';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.failure_ref IS '失败诊断引用';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.updated_second IS '数据库最后更新时间 epoch 秒';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.updated_nano IS '数据库最后更新时间纳秒分量';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.recorded_second IS '首次入队的存储时间 epoch 秒，后续 receipt 重放不改变';
COMMENT ON COLUMN public.nw_runtime_work_queue_items.recorded_nano IS '首次入队的存储时间纳秒分量';
COMMENT ON COLUMN public.nw_runtime_work_queue_operations.tenant_id IS '原工作租户';
COMMENT ON COLUMN public.nw_runtime_work_queue_operations.work_id IS '原工作身份';
COMMENT ON COLUMN public.nw_runtime_work_queue_operations.operation_id IS '稳定mutation身份';
COMMENT ON COLUMN public.nw_runtime_work_queue_operations.command_data IS '包括完整lease和ACK事实引用的原命令规范快照';
COMMENT ON COLUMN public.nw_runtime_work_queue_operations.output_revision IS '本次mutation输出revision';
COMMENT ON COLUMN public.nw_runtime_work_queue_conflict_audits.tenant_id IS '冲突请求租户';
COMMENT ON COLUMN public.nw_runtime_work_queue_conflict_audits.work_id IS '冲突工作身份';
COMMENT ON COLUMN public.nw_runtime_work_queue_conflict_audits.command_digest IS '完整规范命令摘要，不保存原载荷';
COMMENT ON COLUMN public.nw_runtime_work_queue_conflict_audits.audit_ref IS '稳定冲突审计引用';
