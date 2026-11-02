-- 一次性 Scheduler 独立状态机；finish 必须引用原冻结 envelope 的准入事实。
CREATE SEQUENCE public.agent_runtime_scheduler_fencing_seq AS bigint START WITH 1;
COMMENT ON SEQUENCE public.agent_runtime_scheduler_fencing_seq IS '一次性调度每次领取与过期接管的递增 fencing token';

CREATE TABLE public.nw_runtime_scheduler_items (
    tenant_id text NOT NULL CHECK (btrim(tenant_id) <> ''),
    schedule_id text NOT NULL CHECK (btrim(schedule_id) <> ''),
    idempotency_key text NOT NULL CHECK (btrim(idempotency_key) <> ''),
    input_id text NOT NULL CHECK (btrim(input_id) <> ''),
    submission_data text NOT NULL CHECK (submission_data <> ''),
    state text NOT NULL CHECK (state IN ('SCHEDULED','CLAIMED','DELIVERED','CANCELLED','DEAD_LETTER')),
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
    CONSTRAINT agent_runtime_scheduler_delivery_pk PRIMARY KEY (tenant_id,schedule_id),
    CONSTRAINT agent_runtime_scheduler_delivery_idempotency UNIQUE (tenant_id,idempotency_key),
    CONSTRAINT agent_runtime_scheduler_delivery_lease CHECK (
        (state = 'CLAIMED' AND lease_claim IS NOT NULL AND btrim(lease_claim) <> ''
            AND lease_worker IS NOT NULL AND btrim(lease_worker) <> '' AND lease_token IS NOT NULL AND lease_token > 0
            AND lease_second IS NOT NULL AND lease_nano IS NOT NULL AND lease_nano BETWEEN 0 AND 999999999)
        OR (state <> 'CLAIMED' AND lease_claim IS NULL AND lease_worker IS NULL
            AND lease_token IS NULL AND lease_second IS NULL AND lease_nano IS NULL)),
    CONSTRAINT agent_runtime_scheduler_delivery_failure CHECK (
        (failure_code IS NULL AND failure_retry IS NULL AND failure_ref IS NULL)
        OR (failure_code IS NOT NULL AND btrim(failure_code) <> '' AND failure_ref IS NOT NULL AND btrim(failure_ref) <> ''
            AND failure_retry IS NOT NULL AND failure_retry IN ('NONE','IDEMPOTENT_BY_STABLE_KEY'))),
    CONSTRAINT agent_runtime_scheduler_delivery_matrix CHECK (
        (state = 'CANCELLED' AND failure_code IS NULL)
        OR (attempt = 0 AND state = 'SCHEDULED' AND failure_code IS NULL)
        OR (attempt > 0 AND (
            (state = 'SCHEDULED' AND failure_retry IS NOT NULL AND failure_retry = 'IDEMPOTENT_BY_STABLE_KEY')
            OR (state = 'CLAIMED' AND (failure_code IS NULL OR failure_retry = 'IDEMPOTENT_BY_STABLE_KEY'))
            OR (state = 'DELIVERED' AND failure_code IS NULL)
            OR (state = 'DEAD_LETTER' AND failure_retry IS NOT NULL AND failure_retry = 'NONE'))))
);

CREATE TABLE public.nw_runtime_scheduler_operations (
    tenant_id text NOT NULL,
    schedule_id text NOT NULL,
    operation_id text NOT NULL CHECK (btrim(operation_id) <> ''),
    command_data text NOT NULL CHECK (command_data <> ''),
    output_revision bigint NOT NULL CHECK (output_revision > 1),
    CONSTRAINT agent_runtime_scheduler_operation_pk PRIMARY KEY (tenant_id,schedule_id,operation_id),
    CONSTRAINT agent_runtime_scheduler_operation_delivery_fk FOREIGN KEY (tenant_id,schedule_id)
        REFERENCES public.nw_runtime_scheduler_items (tenant_id,schedule_id) ON DELETE RESTRICT
);

-- 初次绑定可能因另一个调度占用幂等键而冲突，故审计不要求调度行外键。
CREATE TABLE public.nw_runtime_scheduler_conflict_audits (
    tenant_id text NOT NULL CHECK (btrim(tenant_id) <> ''),
    schedule_id text NOT NULL CHECK (btrim(schedule_id) <> ''),
    command_digest text NOT NULL CHECK (command_digest ~ '^[0-9a-f]{64}$'),
    audit_ref text NOT NULL CHECK (btrim(audit_ref) <> ''),
    CONSTRAINT agent_runtime_scheduler_audit_pk PRIMARY KEY (tenant_id,schedule_id,command_digest),
    CONSTRAINT agent_runtime_scheduler_audit_ref UNIQUE (audit_ref)
);

CREATE INDEX agent_runtime_scheduler_ready ON public.nw_runtime_scheduler_items
    (tenant_id,available_second,available_nano,schedule_id) WHERE state = 'SCHEDULED';
CREATE INDEX agent_runtime_scheduler_expired ON public.nw_runtime_scheduler_items
    (tenant_id,lease_second,lease_nano) WHERE state = 'CLAIMED';
COMMENT ON INDEX public.agent_runtime_scheduler_ready IS '按租户扫描已到期 SCHEDULED 调度';
COMMENT ON INDEX public.agent_runtime_scheduler_expired IS '按租户扫描失效 CLAIMED lease';

CREATE FUNCTION public.guard_agent_runtime_scheduler_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE now_value numeric; now_second bigint; now_nano integer;
BEGIN
    now_value := extract(epoch FROM clock_timestamp());
    now_second := floor(now_value)::bigint;
    now_nano := ((now_value - floor(now_value)) * 1000000000)::integer;
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'scheduler_delivery_delete_forbidden'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'SCHEDULED' OR NEW.revision <> 1 OR NEW.attempt <> 0 THEN
            RAISE EXCEPTION 'scheduler_delivery_initial_state';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id,NEW.schedule_id,NEW.idempotency_key,NEW.input_id,NEW.submission_data,NEW.recorded_second,NEW.recorded_nano)
        IS DISTINCT FROM (OLD.tenant_id,OLD.schedule_id,OLD.idempotency_key,OLD.input_id,OLD.submission_data,OLD.recorded_second,OLD.recorded_nano)
        OR NEW.revision <> OLD.revision + 1 OR OLD.state IN ('DELIVERED','CANCELLED','DEAD_LETTER') THEN
        RAISE EXCEPTION 'scheduler_delivery_immutable_or_revision';
    END IF;
    IF NEW.state = 'CLAIMED' AND NEW.attempt = OLD.attempt + 1 THEN
        IF OLD.state NOT IN ('SCHEDULED','CLAIMED') OR (OLD.lease_token IS NOT NULL AND NEW.lease_token <= OLD.lease_token)
            OR (NEW.lease_second,NEW.lease_nano) <= (now_second,now_nano)
            OR (OLD.state = 'SCHEDULED' AND (OLD.available_second,OLD.available_nano) > (now_second,now_nano))
            OR (OLD.state = 'CLAIMED' AND (OLD.lease_second,OLD.lease_nano) > (now_second,now_nano))
            OR NEW.lease_claim IS NOT DISTINCT FROM OLD.lease_claim
            OR (NEW.failure_code,NEW.failure_retry,NEW.failure_ref) IS DISTINCT FROM (OLD.failure_code,OLD.failure_retry,OLD.failure_ref) THEN
            RAISE EXCEPTION 'scheduler_delivery_claim_mismatch';
        END IF;
    ELSIF NEW.state = 'CANCELLED' AND NEW.attempt = OLD.attempt THEN
        -- 控制面取消按 revision 授权，不消费 worker lease；可取消未领取或已过期领取。
        IF OLD.state NOT IN ('SCHEDULED','CLAIMED') THEN RAISE EXCEPTION 'scheduler_cancel_transition'; END IF;
    ELSIF OLD.state = 'CLAIMED' AND NEW.attempt = OLD.attempt THEN
        IF (OLD.lease_second,OLD.lease_nano) <= (now_second,now_nano) THEN RAISE EXCEPTION 'scheduler_delivery_expired'; END IF;
        IF NEW.state = 'CLAIMED' AND (
            (NEW.lease_claim,NEW.lease_worker,NEW.lease_token) IS DISTINCT FROM (OLD.lease_claim,OLD.lease_worker,OLD.lease_token)
            OR (NEW.lease_second,NEW.lease_nano) <= (OLD.lease_second,OLD.lease_nano)
            OR (NEW.failure_code,NEW.failure_retry,NEW.failure_ref) IS DISTINCT FROM (OLD.failure_code,OLD.failure_retry,OLD.failure_ref)) THEN
            RAISE EXCEPTION 'scheduler_delivery_renew_mismatch';
        END IF;
    ELSE RAISE EXCEPTION 'scheduler_delivery_transition';
    END IF;
    IF NEW.state <> 'SCHEDULED' AND (NEW.available_second,NEW.available_nano) IS DISTINCT FROM (OLD.available_second,OLD.available_nano) THEN
        RAISE EXCEPTION 'scheduler_delivery_availability_immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_runtime_scheduler_delivery_guard BEFORE INSERT OR UPDATE OR DELETE ON public.nw_runtime_scheduler_items
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_scheduler_delivery();
COMMENT ON FUNCTION public.guard_agent_runtime_scheduler_delivery() IS '冻结原提交并约束领取、续期、重排及终态的单步 revision/attempt';

CREATE FUNCTION public.guard_agent_runtime_scheduler_journal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'nw_runtime_scheduler_conflict_audits' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'scheduler_journal_immutable';
END $$;
CREATE TRIGGER agent_runtime_scheduler_operation_guard BEFORE UPDATE OR DELETE ON public.nw_runtime_scheduler_operations
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_scheduler_journal();
CREATE TRIGGER agent_runtime_scheduler_audit_guard BEFORE UPDATE OR DELETE ON public.nw_runtime_scheduler_conflict_audits
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_scheduler_journal();
COMMENT ON FUNCTION public.guard_agent_runtime_scheduler_journal() IS '禁止改写或删除 operation 与冲突审计事实，仅允许精确审计重放';

CREATE FUNCTION public.check_agent_runtime_scheduler_operation_closure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'nw_runtime_scheduler_operations' THEN
        IF NOT EXISTS (SELECT 1 FROM public.nw_runtime_scheduler_items d WHERE d.tenant_id = NEW.tenant_id
            AND d.schedule_id = NEW.schedule_id AND d.revision = NEW.output_revision) THEN
            RAISE EXCEPTION 'scheduler_operation_output_mismatch';
        END IF;
    ELSIF NEW.attempt = OLD.attempt AND NOT EXISTS (
        SELECT 1 FROM public.nw_runtime_scheduler_operations o WHERE o.tenant_id = NEW.tenant_id
            AND o.schedule_id = NEW.schedule_id AND o.output_revision = NEW.revision) THEN
        RAISE EXCEPTION 'scheduler_operation_missing';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER agent_runtime_scheduler_operation_closure AFTER INSERT ON public.nw_runtime_scheduler_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_scheduler_operation_closure();
CREATE CONSTRAINT TRIGGER agent_runtime_scheduler_delivery_closure AFTER UPDATE ON public.nw_runtime_scheduler_items
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_scheduler_operation_closure();
COMMENT ON FUNCTION public.check_agent_runtime_scheduler_operation_closure() IS '调度 mutation 与 journal 输出必须在同一提交闭合';

ALTER TABLE public.nw_runtime_scheduler_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_scheduler_items FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_scheduler_delivery_tenant ON public.nw_runtime_scheduler_items
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));
ALTER TABLE public.nw_runtime_scheduler_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_scheduler_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_scheduler_operation_tenant ON public.nw_runtime_scheduler_operations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));
ALTER TABLE public.nw_runtime_scheduler_conflict_audits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_scheduler_conflict_audits FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_scheduler_audit_tenant ON public.nw_runtime_scheduler_conflict_audits
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));

COMMENT ON TABLE public.nw_runtime_scheduler_items IS '每租户 schedule 的独立调度状态，不拥有 Session sequence 或业务状态';
COMMENT ON TABLE public.nw_runtime_scheduler_operations IS '完整规范 mutation 与其已提交输出 revision，响应未知时使用原命令收敛';
COMMENT ON TABLE public.nw_runtime_scheduler_conflict_audits IS '双向幂等绑定或 mutation 内容漂移的稳定摘要审计';



COMMENT ON COLUMN public.nw_runtime_scheduler_items.tenant_id IS '所属租户，事务本地 RLS 身份';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.schedule_id IS '租户内一次性调度的稳定身份';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.idempotency_key IS '与 scheduleRef 双向绑定的租户内稳定键';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.input_id IS '原冻结 SCHEDULED envelope 的 InputId，用于精确准入来源查询';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.submission_data IS '完整冻结 ScheduleSubmission 规范快照，含原envelope、因果身份与首次dueAt';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.state IS 'SCHEDULED/CLAIMED/DELIVERED/CANCELLED/DEAD_LETTER 调度状态';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.revision IS '调度领取与 mutation 单调 revision';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.attempt IS '成功领取次数，初始为零';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.available_second IS '下一次可领取时间 epoch 秒';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.available_nano IS '下一次可领取时间纳秒分量';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.lease_claim IS '当前 claim 唯一身份';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.lease_worker IS '当前 worker 身份';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.lease_token IS '单调 fencing token';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.lease_second IS '当前租约到期 epoch 秒';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.lease_nano IS '当前租约到期纳秒分量';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.failure_code IS '上次处理失败的稳定原因';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.failure_retry IS '固定重试语义，重排为稳定键重试，dead-letter为NONE';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.failure_ref IS '失败诊断引用';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.updated_second IS '数据库最后更新时间 epoch 秒';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.updated_nano IS '数据库最后更新时间纳秒分量';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.recorded_second IS '首次提交的存储时间 epoch 秒，后续 receipt 重放不改变';
COMMENT ON COLUMN public.nw_runtime_scheduler_items.recorded_nano IS '首次提交的存储时间纳秒分量';
COMMENT ON COLUMN public.nw_runtime_scheduler_operations.tenant_id IS '原调度租户';
COMMENT ON COLUMN public.nw_runtime_scheduler_operations.schedule_id IS '原调度身份';
COMMENT ON COLUMN public.nw_runtime_scheduler_operations.operation_id IS '稳定mutation身份';
COMMENT ON COLUMN public.nw_runtime_scheduler_operations.command_data IS '包括完整lease、准入receipt或控制面身份的原命令规范快照';
COMMENT ON COLUMN public.nw_runtime_scheduler_operations.output_revision IS '本次mutation输出revision';
COMMENT ON COLUMN public.nw_runtime_scheduler_conflict_audits.tenant_id IS '冲突请求租户';
COMMENT ON COLUMN public.nw_runtime_scheduler_conflict_audits.schedule_id IS '冲突调度身份';
COMMENT ON COLUMN public.nw_runtime_scheduler_conflict_audits.command_digest IS '完整规范命令摘要，不保存原载荷';
COMMENT ON COLUMN public.nw_runtime_scheduler_conflict_audits.audit_ref IS '稳定冲突审计引用';
