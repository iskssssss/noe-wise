-- Callback 注册与投递独立持久化；epoch numeric 保留完整纳秒，不回落旧 callback 表。
CREATE SEQUENCE public.agent_runtime_callback_fence_seq AS bigint START WITH 1;
COMMENT ON SEQUENCE public.agent_runtime_callback_fence_seq IS 'Callback delivery 领取及过期接管的单调 fencing token';

CREATE TABLE public.agent_runtime_callback_registrations (
    tenant_id text NOT NULL CHECK (btrim(tenant_id) <> ''),
    callback_id text NOT NULL CHECK (btrim(callback_id) <> ''),
    idempotency_key text NOT NULL CHECK (btrim(idempotency_key) <> ''),
    command_data text NOT NULL CHECK (command_data <> ''),
    status text NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','REVOKED')),
    revision bigint NOT NULL CHECK (revision > 0),
    created_at numeric(30,9) NOT NULL CHECK (created_at <> 'NaN'::numeric),
    updated_at numeric(30,9) NOT NULL CHECK (updated_at <> 'NaN'::numeric),
    CONSTRAINT agent_runtime_callback_registration_pk PRIMARY KEY (tenant_id,callback_id),
    CONSTRAINT agent_runtime_callback_registration_key UNIQUE (tenant_id,idempotency_key)
);

CREATE TABLE public.agent_runtime_callback_registration_operations (
    tenant_id text NOT NULL,
    callback_id text NOT NULL,
    operation_id text NOT NULL CHECK (btrim(operation_id) <> ''),
    command_data text NOT NULL CHECK (command_data <> ''),
    output_revision bigint NOT NULL CHECK (output_revision > 1),
    CONSTRAINT agent_runtime_callback_registration_operation_pk PRIMARY KEY (tenant_id,callback_id,operation_id),
    CONSTRAINT agent_runtime_callback_registration_operation_output UNIQUE (tenant_id,callback_id,output_revision),
    CONSTRAINT agent_runtime_callback_registration_operation_fk FOREIGN KEY (tenant_id,callback_id)
        REFERENCES public.agent_runtime_callback_registrations(tenant_id,callback_id) ON DELETE RESTRICT
);

CREATE TABLE public.agent_runtime_callback_deliveries (
    tenant_id text NOT NULL CHECK (btrim(tenant_id) <> ''),
    delivery_id text NOT NULL CHECK (btrim(delivery_id) <> ''),
    callback_id text NOT NULL CHECK (btrim(callback_id) <> ''),
    command_digest text NOT NULL CHECK (command_digest ~ '^[0-9a-f]{64}$'),
    input_id text,
    envelope_data text,
    status text NOT NULL CHECK (status IN ('READY','PROCESSING','DELIVERED','REJECTED','DEAD_LETTER')),
    revision bigint NOT NULL CHECK (revision > 0),
    attempt integer NOT NULL CHECK (attempt >= 0),
    reason_code text,
    audit_ref text,
    lease_claim text,
    lease_worker text,
    lease_token bigint,
    lease_until numeric(30,9) CHECK (lease_until <> 'NaN'::numeric),
    available_at numeric(30,9) NOT NULL CHECK (available_at <> 'NaN'::numeric),
    recorded_at numeric(30,9) NOT NULL CHECK (recorded_at <> 'NaN'::numeric),
    updated_at numeric(30,9) NOT NULL CHECK (updated_at <> 'NaN'::numeric),
    CONSTRAINT agent_runtime_callback_delivery_pk PRIMARY KEY (tenant_id,delivery_id),
    -- 未注册 callback 也需形成拒绝事实，因此不设置无条件 registration 外键。
    CONSTRAINT agent_runtime_callback_delivery_fields CHECK (
        (status = 'REJECTED' AND input_id IS NULL AND envelope_data IS NULL AND attempt = 0
            AND reason_code IS NOT NULL AND btrim(reason_code) <> '' AND audit_ref IS NOT NULL AND btrim(audit_ref) <> '')
        OR (status = 'DEAD_LETTER' AND input_id IS NOT NULL AND btrim(input_id) <> '' AND envelope_data IS NOT NULL AND envelope_data <> '' AND attempt > 0
            AND reason_code IS NOT NULL AND btrim(reason_code) <> '' AND audit_ref IS NOT NULL AND btrim(audit_ref) <> '')
        OR (status IN ('READY','PROCESSING','DELIVERED') AND input_id IS NOT NULL AND btrim(input_id) <> '' AND envelope_data IS NOT NULL AND envelope_data <> ''
            AND reason_code IS NULL AND audit_ref IS NULL AND (status = 'READY' OR attempt > 0))),
    CONSTRAINT agent_runtime_callback_delivery_lease CHECK (
        (status = 'PROCESSING' AND lease_claim IS NOT NULL AND btrim(lease_claim) <> ''
            AND lease_worker IS NOT NULL AND btrim(lease_worker) <> '' AND lease_token IS NOT NULL AND lease_token > 0
            AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_claim IS NULL AND lease_worker IS NULL AND lease_token IS NULL AND lease_until IS NULL))
);

CREATE TABLE public.agent_runtime_callback_delivery_operations (
    tenant_id text NOT NULL,
    delivery_id text NOT NULL,
    operation_id text NOT NULL CHECK (btrim(operation_id) <> ''),
    command_data text NOT NULL CHECK (command_data <> ''),
    output_revision bigint NOT NULL CHECK (output_revision > 1),
    CONSTRAINT agent_runtime_callback_delivery_operation_pk PRIMARY KEY (tenant_id,delivery_id,operation_id),
    CONSTRAINT agent_runtime_callback_delivery_operation_output UNIQUE (tenant_id,delivery_id,output_revision),
    CONSTRAINT agent_runtime_callback_delivery_operation_fk FOREIGN KEY (tenant_id,delivery_id)
        REFERENCES public.agent_runtime_callback_deliveries(tenant_id,delivery_id) ON DELETE RESTRICT
);

CREATE TABLE public.agent_runtime_callback_audits (
    tenant_id text NOT NULL CHECK (btrim(tenant_id) <> ''),
    resource_kind text NOT NULL CHECK (resource_kind IN ('registration','delivery')),
    resource_id text NOT NULL CHECK (btrim(resource_id) <> ''),
    command_digest text NOT NULL CHECK (command_digest ~ '^[0-9a-f]{64}$'),
    audit_ref text NOT NULL CHECK (btrim(audit_ref) <> ''),
    reason_code text NOT NULL CHECK (btrim(reason_code) <> ''),
    CONSTRAINT agent_runtime_callback_audit_pk PRIMARY KEY (tenant_id,resource_kind,resource_id,command_digest),
    CONSTRAINT agent_runtime_callback_audit_ref UNIQUE (audit_ref)
);

CREATE INDEX agent_runtime_callback_delivery_ready ON public.agent_runtime_callback_deliveries
    (tenant_id,available_at,delivery_id) WHERE status = 'READY';
CREATE INDEX agent_runtime_callback_delivery_expired ON public.agent_runtime_callback_deliveries
    (tenant_id,lease_until,delivery_id) WHERE status = 'PROCESSING';
CREATE INDEX agent_runtime_callback_delivery_registration ON public.agent_runtime_callback_deliveries
    (tenant_id,callback_id,recorded_at);
COMMENT ON INDEX public.agent_runtime_callback_delivery_ready IS '按租户扫描到期 READY delivery';
COMMENT ON INDEX public.agent_runtime_callback_delivery_expired IS '按租户扫描已过期 PROCESSING lease';
COMMENT ON INDEX public.agent_runtime_callback_delivery_registration IS '按注册追溯已收录或已拒绝的 delivery';

CREATE FUNCTION public.guard_agent_runtime_callback_registration() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'callback_registration_delete_forbidden'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' OR NEW.revision <> 1 THEN RAISE EXCEPTION 'callback_registration_initial_state'; END IF;
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id,NEW.callback_id,NEW.idempotency_key,NEW.command_data,NEW.created_at)
        IS DISTINCT FROM (OLD.tenant_id,OLD.callback_id,OLD.idempotency_key,OLD.command_data,OLD.created_at)
        OR NEW.revision <> OLD.revision + 1 OR OLD.status = 'REVOKED' OR NEW.status = OLD.status THEN
        RAISE EXCEPTION 'callback_registration_immutable_or_transition';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_runtime_callback_registration_guard BEFORE INSERT OR UPDATE OR DELETE
    ON public.agent_runtime_callback_registrations FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_callback_registration();
COMMENT ON FUNCTION public.guard_agent_runtime_callback_registration() IS '冻结原注册身份与命令；ACTIVE/SUSPENDED可切换或撤销，REVOKED不可恢复';

CREATE FUNCTION public.guard_agent_runtime_callback_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE storage_now numeric;
BEGIN
    storage_now := extract(epoch FROM clock_timestamp());
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'callback_delivery_delete_forbidden'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status NOT IN ('READY','REJECTED') OR NEW.revision <> 1 OR NEW.attempt <> 0 THEN
            RAISE EXCEPTION 'callback_delivery_initial_state';
        END IF;
        IF NEW.status = 'READY' AND NOT EXISTS (
            SELECT 1 FROM public.agent_runtime_callback_registrations r
            WHERE r.tenant_id = NEW.tenant_id AND r.callback_id = NEW.callback_id AND r.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'callback_delivery_registration_missing';
        END IF;
        IF NEW.status = 'REJECTED' AND NOT EXISTS (
            SELECT 1 FROM public.agent_runtime_callback_audits a WHERE a.tenant_id = NEW.tenant_id
                AND a.resource_kind = 'delivery' AND a.resource_id = NEW.delivery_id AND a.audit_ref = NEW.audit_ref) THEN
            RAISE EXCEPTION 'callback_delivery_rejection_audit_missing';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id,NEW.delivery_id,NEW.callback_id,NEW.command_digest,NEW.input_id,NEW.envelope_data,NEW.recorded_at)
        IS DISTINCT FROM (OLD.tenant_id,OLD.delivery_id,OLD.callback_id,OLD.command_digest,OLD.input_id,OLD.envelope_data,OLD.recorded_at)
        OR NEW.revision <> OLD.revision + 1 OR OLD.status IN ('DELIVERED','REJECTED','DEAD_LETTER') THEN
        RAISE EXCEPTION 'callback_delivery_immutable_or_revision';
    END IF;
    IF NEW.status = 'PROCESSING' AND NEW.attempt = OLD.attempt + 1 THEN
        IF OLD.status NOT IN ('READY','PROCESSING')
            OR (OLD.status = 'READY' AND OLD.available_at > storage_now)
            OR (OLD.status = 'PROCESSING' AND OLD.lease_until > storage_now)
            OR NEW.lease_until <= storage_now OR (OLD.lease_token IS NOT NULL AND NEW.lease_token <= OLD.lease_token)
            OR NEW.lease_claim IS NOT DISTINCT FROM OLD.lease_claim THEN
            RAISE EXCEPTION 'callback_delivery_claim_mismatch';
        END IF;
    ELSIF OLD.status = 'PROCESSING' AND NEW.attempt = OLD.attempt THEN
        IF OLD.lease_until <= storage_now THEN RAISE EXCEPTION 'callback_delivery_expired'; END IF;
        IF NEW.status NOT IN ('PROCESSING','READY','DELIVERED','DEAD_LETTER') THEN
            RAISE EXCEPTION 'callback_delivery_mutation_state';
        END IF;
        IF NEW.status = 'PROCESSING' AND (
            (NEW.lease_claim,NEW.lease_worker,NEW.lease_token) IS DISTINCT FROM (OLD.lease_claim,OLD.lease_worker,OLD.lease_token)
            OR NEW.lease_until <= OLD.lease_until) THEN RAISE EXCEPTION 'callback_delivery_renew_mismatch'; END IF;
    ELSE RAISE EXCEPTION 'callback_delivery_transition';
    END IF;
    IF NEW.status <> 'READY' AND NEW.available_at IS DISTINCT FROM OLD.available_at THEN
        RAISE EXCEPTION 'callback_delivery_availability_immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_runtime_callback_delivery_guard BEFORE INSERT OR UPDATE OR DELETE
    ON public.agent_runtime_callback_deliveries FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_callback_delivery();
COMMENT ON FUNCTION public.guard_agent_runtime_callback_delivery() IS '冻结原delivery与envelope；领取/接管递增attempt和fence，mutation只消费当前live lease';

CREATE FUNCTION public.guard_agent_runtime_callback_journal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'agent_runtime_callback_audits' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'callback_journal_immutable';
END $$;
CREATE TRIGGER agent_runtime_callback_registration_operation_guard BEFORE UPDATE OR DELETE
    ON public.agent_runtime_callback_registration_operations FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_callback_journal();
CREATE TRIGGER agent_runtime_callback_delivery_operation_guard BEFORE UPDATE OR DELETE
    ON public.agent_runtime_callback_delivery_operations FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_callback_journal();
CREATE TRIGGER agent_runtime_callback_audit_guard BEFORE UPDATE OR DELETE
    ON public.agent_runtime_callback_audits FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_callback_journal();
COMMENT ON FUNCTION public.guard_agent_runtime_callback_journal() IS '禁止改写或删除operation及审计，只允许审计完全相同的幂等更新';

CREATE FUNCTION public.check_agent_runtime_callback_registration_closure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'agent_runtime_callback_registration_operations' THEN
        IF NOT EXISTS (SELECT 1 FROM public.agent_runtime_callback_registrations r
            WHERE r.tenant_id = NEW.tenant_id AND r.callback_id = NEW.callback_id AND r.revision = NEW.output_revision) THEN
            RAISE EXCEPTION 'callback_registration_operation_output_mismatch';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM public.agent_runtime_callback_registration_operations o
        WHERE o.tenant_id = NEW.tenant_id AND o.callback_id = NEW.callback_id AND o.output_revision = NEW.revision) THEN
        RAISE EXCEPTION 'callback_registration_operation_missing';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER agent_runtime_callback_registration_operation_closure AFTER INSERT
    ON public.agent_runtime_callback_registration_operations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_callback_registration_closure();
CREATE CONSTRAINT TRIGGER agent_runtime_callback_registration_closure AFTER UPDATE
    ON public.agent_runtime_callback_registrations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_callback_registration_closure();
COMMENT ON FUNCTION public.check_agent_runtime_callback_registration_closure() IS '注册状态mutation与唯一operation输出同事务闭合';

CREATE FUNCTION public.check_agent_runtime_callback_delivery_closure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'agent_runtime_callback_delivery_operations' THEN
        IF NOT EXISTS (SELECT 1 FROM public.agent_runtime_callback_deliveries d
            WHERE d.tenant_id = NEW.tenant_id AND d.delivery_id = NEW.delivery_id AND d.revision = NEW.output_revision) THEN
            RAISE EXCEPTION 'callback_delivery_operation_output_mismatch';
        END IF;
    ELSIF NEW.attempt = OLD.attempt AND NOT EXISTS (SELECT 1 FROM public.agent_runtime_callback_delivery_operations o
        WHERE o.tenant_id = NEW.tenant_id AND o.delivery_id = NEW.delivery_id AND o.output_revision = NEW.revision) THEN
        RAISE EXCEPTION 'callback_delivery_operation_missing';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER agent_runtime_callback_delivery_operation_closure AFTER INSERT
    ON public.agent_runtime_callback_delivery_operations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_callback_delivery_closure();
CREATE CONSTRAINT TRIGGER agent_runtime_callback_delivery_closure AFTER UPDATE
    ON public.agent_runtime_callback_deliveries DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.check_agent_runtime_callback_delivery_closure();
COMMENT ON FUNCTION public.check_agent_runtime_callback_delivery_closure() IS '投递mutation必须与journal闭合，attempt递增的claim不要求journal';

ALTER TABLE public.agent_runtime_callback_registrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_callback_registrations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_callback_registration_tenant ON public.agent_runtime_callback_registrations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),'')) WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));
ALTER TABLE public.agent_runtime_callback_registration_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_callback_registration_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_callback_registration_operation_tenant ON public.agent_runtime_callback_registration_operations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),'')) WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));
ALTER TABLE public.agent_runtime_callback_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_callback_deliveries FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_callback_delivery_tenant ON public.agent_runtime_callback_deliveries
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),'')) WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));
ALTER TABLE public.agent_runtime_callback_delivery_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_callback_delivery_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_callback_delivery_operation_tenant ON public.agent_runtime_callback_delivery_operations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),'')) WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));
ALTER TABLE public.agent_runtime_callback_audits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_callback_audits FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_callback_audit_tenant ON public.agent_runtime_callback_audits
    USING (tenant_id = COALESCE(current_setting('app.tenant_id',true),'')) WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id',true),''));

COMMENT ON TABLE public.agent_runtime_callback_registrations IS '冻结callback注册与当前三态，不复制原签名密钥';
COMMENT ON COLUMN public.agent_runtime_callback_registrations.tenant_id IS '原注册租户，RLS隔离键';
COMMENT ON COLUMN public.agent_runtime_callback_registrations.callback_id IS '租户内callback稳定身份';
COMMENT ON COLUMN public.agent_runtime_callback_registrations.idempotency_key IS '与callbackRef双向绑定的稳定注册键';
COMMENT ON COLUMN public.agent_runtime_callback_registrations.command_data IS '完整RegisterCallback规范快照，包括原owner/target/credentialRef/因果身份';
COMMENT ON COLUMN public.agent_runtime_callback_registrations.status IS 'ACTIVE/SUSPENDED/REVOKED注册三态';
COMMENT ON COLUMN public.agent_runtime_callback_registrations.revision IS '初始为1且每次状态mutation递增的revision';
COMMENT ON COLUMN public.agent_runtime_callback_registrations.created_at IS '首次创建时间，epoch秒含9位纳秒且永久冻结';
COMMENT ON COLUMN public.agent_runtime_callback_registrations.updated_at IS '最近状态mutation时间，epoch秒含9位纳秒';
COMMENT ON TABLE public.agent_runtime_callback_registration_operations IS '原注册状态命令与唯一输出revision';
COMMENT ON COLUMN public.agent_runtime_callback_registration_operations.tenant_id IS '原注册租户';
COMMENT ON COLUMN public.agent_runtime_callback_registration_operations.callback_id IS '原注册callback身份';
COMMENT ON COLUMN public.agent_runtime_callback_registration_operations.operation_id IS '状态命令稳定操作身份';
COMMENT ON COLUMN public.agent_runtime_callback_registration_operations.command_data IS '完整状态命令快照，含原requester与expectedRevision';
COMMENT ON COLUMN public.agent_runtime_callback_registration_operations.output_revision IS '同事务提交的注册输出revision';
COMMENT ON TABLE public.agent_runtime_callback_deliveries IS '已验证投递责任；拒绝只存摘要和审计，不保留原envelope';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.tenant_id IS '原投递租户，RLS隔离键';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.delivery_id IS '租户内稳定投递身份';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.callback_id IS '原注册身份；未知注册拒绝允许没有对应注册行';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.command_digest IS '完整VerifiedCallbackDelivery规范命令的域分离SHA256';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.envelope_data IS '已接受原CALLBACK envelope规范快照；REJECTED必须为空，后续不得变更';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.input_id IS '已接受原 CALLBACK envelope 的 InputId；REJECTED 必须为空';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.status IS 'READY/PROCESSING/DELIVERED/REJECTED/DEAD_LETTER投递五态';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.revision IS '领取及mutation单调revision';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.attempt IS '初始为0，每次领取及过期接管递增';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.reason_code IS '仅REJECTED或DEAD_LETTER保存的安全稳定原因';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.audit_ref IS '拒绝持久审计引用或Runtime原dead-letter诊断引用';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.lease_claim IS '当前PROCESSING领取身份，其余状态为空';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.lease_worker IS '当前PROCESSING worker身份，其余状态为空';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.lease_token IS '当前领取的单调正fencing token';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.lease_until IS '当前租约到期epoch秒含9位纳秒，仅PROCESSING可有值';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.available_at IS '原首次或Runtime显式retry的可领取epoch秒含9位纳秒';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.recorded_at IS '首次接受或拒绝epoch秒含9位纳秒，永久冻结';
COMMENT ON COLUMN public.agent_runtime_callback_deliveries.updated_at IS '最近领取或mutation的数据库epoch秒含9位纳秒';
COMMENT ON TABLE public.agent_runtime_callback_delivery_operations IS '投递mutation的完整命令与输出revision，claim不写journal';
COMMENT ON COLUMN public.agent_runtime_callback_delivery_operations.tenant_id IS '原投递租户';
COMMENT ON COLUMN public.agent_runtime_callback_delivery_operations.delivery_id IS '原投递身份';
COMMENT ON COLUMN public.agent_runtime_callback_delivery_operations.operation_id IS '稳定投递mutation身份';
COMMENT ON COLUMN public.agent_runtime_callback_delivery_operations.command_data IS '完整lease及finish/retry/renew/dead-letter命令快照';
COMMENT ON COLUMN public.agent_runtime_callback_delivery_operations.output_revision IS '同事务提交的投递输出revision';
COMMENT ON TABLE public.agent_runtime_callback_audits IS 'callback冲突或入口拒绝的安全摘要审计；当前身份策略审计归独立IAM边界';
COMMENT ON COLUMN public.agent_runtime_callback_audits.tenant_id IS '已进入callback边界的原租户';
COMMENT ON COLUMN public.agent_runtime_callback_audits.resource_kind IS 'registration或delivery审计资源域';
COMMENT ON COLUMN public.agent_runtime_callback_audits.resource_id IS '对应callback或delivery稳定身份';
COMMENT ON COLUMN public.agent_runtime_callback_audits.command_digest IS '规范请求摘要，不保存被拒绝payload';
COMMENT ON COLUMN public.agent_runtime_callback_audits.audit_ref IS '本域实际持久化的唯一安全审计引用';
COMMENT ON COLUMN public.agent_runtime_callback_audits.reason_code IS '稳定安全原因码，不包含原请求或异常文本';
