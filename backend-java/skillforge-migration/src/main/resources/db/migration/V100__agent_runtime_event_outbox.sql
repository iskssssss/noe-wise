-- RuntimeEvent delivery 与 Session Outbox 分离，只承担每 destination 的投递责任。
CREATE SEQUENCE public.agent_runtime_event_fencing_seq AS bigint START WITH 1;
COMMENT ON SEQUENCE public.agent_runtime_event_fencing_seq IS 'RuntimeEvent delivery 每次领取与过期接管的递增 fencing token';

CREATE TABLE public.agent_runtime_event_deliveries (
    tenant_id text NOT NULL CHECK (btrim(tenant_id) <> ''),
    event_id text NOT NULL CHECK (btrim(event_id) <> ''),
    destination_id text NOT NULL CHECK (btrim(destination_id) <> ''),
    idempotency_key text NOT NULL CHECK (btrim(idempotency_key) <> ''),
    submission_data text NOT NULL CHECK (submission_data <> ''),
    state text NOT NULL CHECK (state IN ('PENDING','DELIVERING','DELIVERED','DEAD_LETTER')),
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
    CONSTRAINT agent_runtime_event_delivery_pk PRIMARY KEY (tenant_id,event_id,destination_id),
    CONSTRAINT agent_runtime_event_delivery_idempotency UNIQUE (tenant_id,idempotency_key),
    CONSTRAINT agent_runtime_event_delivery_lease CHECK (
        (state = 'DELIVERING' AND lease_claim IS NOT NULL AND btrim(lease_claim) <> ''
            AND lease_worker IS NOT NULL AND btrim(lease_worker) <> '' AND lease_token IS NOT NULL AND lease_token > 0
            AND lease_second IS NOT NULL AND lease_nano IS NOT NULL AND lease_nano BETWEEN 0 AND 999999999)
        OR (state <> 'DELIVERING' AND lease_claim IS NULL AND lease_worker IS NULL
            AND lease_token IS NULL AND lease_second IS NULL AND lease_nano IS NULL)),
    CONSTRAINT agent_runtime_event_delivery_failure CHECK (
        (failure_code IS NULL AND failure_retry IS NULL AND failure_ref IS NULL)
        OR (failure_code IS NOT NULL AND btrim(failure_code) <> '' AND failure_ref IS NOT NULL AND btrim(failure_ref) <> ''
            AND failure_retry IS NOT NULL AND failure_retry IN ('NONE','IDEMPOTENT_BY_STABLE_KEY'))),
    CONSTRAINT agent_runtime_event_delivery_matrix CHECK (
        (attempt = 0 AND state = 'PENDING' AND failure_code IS NULL)
        OR (attempt > 0 AND (
            (state = 'PENDING' AND failure_retry IS NOT NULL AND failure_retry = 'IDEMPOTENT_BY_STABLE_KEY')
            OR (state = 'DELIVERING' AND (failure_code IS NULL OR failure_retry = 'IDEMPOTENT_BY_STABLE_KEY'))
            OR (state = 'DELIVERED' AND failure_code IS NULL)
            OR (state = 'DEAD_LETTER' AND failure_retry IS NOT NULL AND failure_retry = 'NONE'))))
);

CREATE TABLE public.nw_runtime_event_operations (
    tenant_id text NOT NULL,
    event_id text NOT NULL,
    destination_id text NOT NULL,
    operation_id text NOT NULL CHECK (btrim(operation_id) <> ''),
    command_data text NOT NULL CHECK (command_data <> ''),
    output_revision bigint NOT NULL CHECK (output_revision > 1),
    CONSTRAINT agent_runtime_event_operation_pk PRIMARY KEY (tenant_id,event_id,destination_id,operation_id),
    CONSTRAINT agent_runtime_event_operation_delivery_fk FOREIGN KEY (tenant_id,event_id,destination_id)
        REFERENCES public.agent_runtime_event_deliveries (tenant_id,event_id,destination_id) ON DELETE RESTRICT
);

-- 初次绑定可能因另一个 destination 占用幂等键而冲突，故审计不要求 delivery 外键。
CREATE TABLE public.agent_runtime_event_conflict_audits (
    tenant_id text NOT NULL CHECK (btrim(tenant_id) <> ''),
    event_id text NOT NULL CHECK (btrim(event_id) <> ''),
    destination_id text NOT NULL CHECK (btrim(destination_id) <> ''),
    command_digest text NOT NULL CHECK (command_digest ~ '^[0-9a-f]{64}$'),
    audit_ref text NOT NULL CHECK (btrim(audit_ref) <> ''),
    CONSTRAINT agent_runtime_event_audit_pk PRIMARY KEY (tenant_id,event_id,destination_id,command_digest),
    CONSTRAINT agent_runtime_event_audit_ref UNIQUE (audit_ref)
);

CREATE INDEX agent_runtime_event_ready ON public.agent_runtime_event_deliveries
    (tenant_id,available_second,available_nano,event_id,destination_id) WHERE state = 'PENDING';
CREATE INDEX agent_runtime_event_expired ON public.agent_runtime_event_deliveries
    (tenant_id,lease_second,lease_nano) WHERE state = 'DELIVERING';
COMMENT ON INDEX public.agent_runtime_event_ready IS '按租户扫描已到期 PENDING destination delivery';
COMMENT ON INDEX public.agent_runtime_event_expired IS '按租户扫描失效 DELIVERING lease';

CREATE FUNCTION public.guard_agent_runtime_event_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE now_value numeric; now_second bigint; now_nano integer;
BEGIN
    now_value := extract(epoch FROM clock_timestamp());
    now_second := floor(now_value)::bigint;
    now_nano := ((now_value - floor(now_value)) * 1000000000)::integer;
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'runtime_event_delivery_delete_forbidden'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'PENDING' OR NEW.revision <> 1 OR NEW.attempt <> 0 THEN
            RAISE EXCEPTION 'runtime_event_delivery_initial_state';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id,NEW.event_id,NEW.destination_id,NEW.idempotency_key,NEW.submission_data)
        IS DISTINCT FROM (OLD.tenant_id,OLD.event_id,OLD.destination_id,OLD.idempotency_key,OLD.submission_data)
        OR NEW.revision <> OLD.revision + 1 OR OLD.state IN ('DELIVERED','DEAD_LETTER') THEN
        RAISE EXCEPTION 'runtime_event_delivery_immutable_or_revision';
    END IF;
    IF NEW.state = 'DELIVERING' AND NEW.attempt = OLD.attempt + 1 THEN
        IF OLD.state NOT IN ('PENDING','DELIVERING') OR (OLD.lease_token IS NOT NULL AND NEW.lease_token <= OLD.lease_token)
            OR (NEW.lease_second,NEW.lease_nano) <= (now_second,now_nano)
            OR (OLD.state = 'PENDING' AND (OLD.available_second,OLD.available_nano) > (now_second,now_nano))
            OR (OLD.state = 'DELIVERING' AND (OLD.lease_second,OLD.lease_nano) > (now_second,now_nano))
            OR NEW.lease_claim IS NOT DISTINCT FROM OLD.lease_claim
            OR (NEW.failure_code,NEW.failure_retry,NEW.failure_ref) IS DISTINCT FROM (OLD.failure_code,OLD.failure_retry,OLD.failure_ref) THEN
            RAISE EXCEPTION 'runtime_event_delivery_claim_mismatch';
        END IF;
    ELSIF OLD.state = 'DELIVERING' AND NEW.attempt = OLD.attempt THEN
        IF (OLD.lease_second,OLD.lease_nano) <= (now_second,now_nano) THEN RAISE EXCEPTION 'runtime_event_delivery_expired'; END IF;
        IF NEW.state = 'DELIVERING' AND (
            (NEW.lease_claim,NEW.lease_worker,NEW.lease_token) IS DISTINCT FROM (OLD.lease_claim,OLD.lease_worker,OLD.lease_token)
            OR (NEW.lease_second,NEW.lease_nano) <= (OLD.lease_second,OLD.lease_nano)
            OR (NEW.failure_code,NEW.failure_retry,NEW.failure_ref) IS DISTINCT FROM (OLD.failure_code,OLD.failure_retry,OLD.failure_ref)) THEN
            RAISE EXCEPTION 'runtime_event_delivery_renew_mismatch';
        END IF;
    ELSE RAISE EXCEPTION 'runtime_event_delivery_transition';
    END IF;
    IF NEW.state <> 'PENDING' AND (NEW.available_second,NEW.available_nano) IS DISTINCT FROM (OLD.available_second,OLD.available_nano) THEN
        RAISE EXCEPTION 'runtime_event_delivery_availability_immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_runtime_event_delivery_guard BEFORE INSERT OR UPDATE OR DELETE ON public.agent_runtime_event_deliveries
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_event_delivery();
COMMENT ON FUNCTION public.guard_agent_runtime_event_delivery() IS '冻结原提交并约束领取、续期、重排及终态的单步 revision/attempt';

CREATE FUNCTION public.guard_agent_runtime_event_journal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'agent_runtime_event_conflict_audits' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'runtime_event_journal_immutable';
END $$;
CREATE TRIGGER agent_runtime_event_operation_guard BEFORE UPDATE OR DELETE ON public.nw_runtime_event_operations
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_event_journal();
CREATE TRIGGER agent_runtime_event_audit_guard BEFORE UPDATE OR DELETE ON public.agent_runtime_event_conflict_audits
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_event_journal();
COMMENT ON FUNCTION public.guard_agent_runtime_event_journal() IS '禁止改写或删除 operation 与冲突审计事实，仅允许精确审计重放';

CREATE FUNCTION public.check_agent_runtime_event_operation_closure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'nw_runtime_event_operations' THEN
        IF NOT EXISTS (SELECT 1 FROM public.agent_runtime_event_deliveries d WHERE d.tenant_id = NEW.tenant_id
            AND d.event_id = NEW.event_id AND d.destination_id = NEW.destination_id AND d.revision = NEW.output_revision) THEN
            RAISE EXCEPTION 'runtime_event_operation_output_mismatch';
        END IF;
    ELSIF NEW.attempt = OLD.attempt AND NOT EXISTS (
        SELECT 1 FROM public.nw_runtime_event_operations o WHERE o.tenant_id = NEW.tenant_id
            AND o.event_id = NEW.event_id AND o.destination_id = NEW.destination_id AND o.output_revision = NEW.revision) THEN
        RAISE EXCEPTION 'runtime_event_operation_missing';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER agent_runtime_event_operation_closure AFTER INSERT ON public.nw_runtime_event_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_event_operation_closure();
CREATE CONSTRAINT TRIGGER agent_runtime_event_delivery_closure AFTER UPDATE ON public.agent_runtime_event_deliveries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_event_operation_closure();
COMMENT ON FUNCTION public.check_agent_runtime_event_operation_closure() IS '投递 mutation 与 journal 输出必须在同一提交闭合';

ALTER TABLE public.agent_runtime_event_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_event_deliveries FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_event_delivery_tenant ON public.agent_runtime_event_deliveries
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));
ALTER TABLE public.nw_runtime_event_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_event_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_event_operation_tenant ON public.nw_runtime_event_operations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));
ALTER TABLE public.agent_runtime_event_conflict_audits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_event_conflict_audits FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_event_audit_tenant ON public.agent_runtime_event_conflict_audits
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));

COMMENT ON TABLE public.agent_runtime_event_deliveries IS '每租户 event/destination 的独立投递状态，不拥有 Session sequence 或业务状态';
COMMENT ON TABLE public.nw_runtime_event_operations IS '完整规范 mutation 与其已提交输出 revision，响应未知时使用原命令收敛';
COMMENT ON TABLE public.agent_runtime_event_conflict_audits IS '双向幂等绑定或 mutation 内容漂移的稳定摘要审计';

COMMENT ON COLUMN public.agent_runtime_event_deliveries.tenant_id IS '所属租户，事务本地 RLS 身份';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.event_id IS '已提交 façade 事件的稳定身份';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.destination_id IS '原 publication 冻结的独立投递目的地';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.idempotency_key IS '租户内与 delivery 双向绑定的稳定键';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.submission_data IS '首次提交的规范投影快照，只作完整值核验，恢复仍读取原业务事实';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.state IS 'PENDING/DELIVERING/DELIVERED/DEAD_LETTER 投递状态';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.revision IS '投递领取或 mutation 的单调 revision';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.attempt IS '成功领取次数，初始为零，续期不增加';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.available_second IS '下一次可领取时间的 epoch 秒';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.available_nano IS '下一次可领取时间的纳秒分量';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.lease_claim IS '当前领取唯一身份，仅 DELIVERING 非空';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.lease_worker IS '当前领取 worker，仅 DELIVERING 非空';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.lease_token IS '当前 fencing token，仅 DELIVERING 非空';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.lease_second IS '租约到期 epoch 秒';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.lease_nano IS '租约到期纳秒分量';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.failure_code IS '上次投递失败的稳定原因码';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.failure_retry IS '失败的固定重试语义';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.failure_ref IS '失败诊断引用，不含事件原文';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.updated_second IS '存储时钟最后更新时间 epoch 秒';
COMMENT ON COLUMN public.agent_runtime_event_deliveries.updated_nano IS '存储时钟最后更新时间纳秒分量';
COMMENT ON COLUMN public.nw_runtime_event_operations.tenant_id IS '所属租户，事务本地 RLS 身份';
COMMENT ON COLUMN public.nw_runtime_event_operations.event_id IS '原 delivery 事件身份';
COMMENT ON COLUMN public.nw_runtime_event_operations.destination_id IS '原 delivery 目的地';
COMMENT ON COLUMN public.nw_runtime_event_operations.operation_id IS '单 delivery 内的稳定 mutation 身份';
COMMENT ON COLUMN public.nw_runtime_event_operations.command_data IS '包含完整 lease、操作种类与参数的规范命令快照';
COMMENT ON COLUMN public.nw_runtime_event_operations.output_revision IS '该 mutation 提交后的 delivery revision';
COMMENT ON COLUMN public.agent_runtime_event_conflict_audits.tenant_id IS '冲突请求所属租户';
COMMENT ON COLUMN public.agent_runtime_event_conflict_audits.event_id IS '冲突请求的事件身份';
COMMENT ON COLUMN public.agent_runtime_event_conflict_audits.destination_id IS '冲突请求的目的地';
COMMENT ON COLUMN public.agent_runtime_event_conflict_audits.command_digest IS '域内规范命令 SHA-256 摘要，不保存原载荷';
COMMENT ON COLUMN public.agent_runtime_event_conflict_audits.audit_ref IS '首次冲突生成且精确重放稳定的审计引用';
