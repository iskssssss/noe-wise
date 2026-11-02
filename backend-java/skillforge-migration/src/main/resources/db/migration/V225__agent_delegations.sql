-- 原生委派以父 Run 的完整身份隔离；child Run 在准入前已预分配，因此不建立提前存在的外键。
CREATE TABLE public.agent_runtime_delegations (
    tenant_id varchar(160) NOT NULL,
    parent_session_id varchar(240) NOT NULL,
    parent_target_kind varchar(10) NOT NULL CHECK (parent_target_kind IN ('AGENT','TEAM')),
    parent_target_id varchar(240) NOT NULL,
    parent_end_user_id bigint NOT NULL CHECK (parent_end_user_id > 0),
    parent_agent_id varchar(240) NOT NULL,
    parent_causation_input_id varchar(240) NOT NULL,
    parent_run_id varchar(240) NOT NULL,
    parent_trace_id varchar(240) NOT NULL,
    parent_span_id varchar(240) NOT NULL,
    parent_root_input_id varchar(240) NOT NULL,
    parent_caused_by_event_id varchar(240),
    parent_causation_identity_ref varchar(240) NOT NULL,
    delegation_id varchar(240) NOT NULL,
    operation_key varchar(240) NOT NULL,
    child_run_id varchar(240) NOT NULL,
    child_input_id varchar(240) NOT NULL,
    previous_delegation_id varchar(240),
    state varchar(20) NOT NULL
        CHECK (state IN ('QUEUED','RUNNING','WAITING','COMPLETED','FAILED','CANCELLED')),
    cancel_key varchar(240),
    revision bigint NOT NULL CHECK (revision > 0),
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL CHECK (created_nano BETWEEN 0 AND 999999999),
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL CHECK (updated_nano BETWEEN 0 AND 999999999),
    PRIMARY KEY (tenant_id, parent_run_id, delegation_id),
    UNIQUE (tenant_id, parent_run_id, operation_key),
    CHECK (btrim(tenant_id) <> '' AND btrim(parent_session_id) <> ''
        AND btrim(parent_target_id) <> '' AND btrim(parent_agent_id) <> ''
        AND btrim(parent_causation_input_id) <> '' AND btrim(parent_run_id) <> ''
        AND btrim(parent_trace_id) <> '' AND btrim(parent_span_id) <> ''
        AND btrim(parent_root_input_id) <> ''
        AND btrim(parent_causation_identity_ref) <> ''
        AND btrim(delegation_id) <> '' AND btrim(operation_key) <> ''
        AND btrim(child_run_id) <> '' AND btrim(child_input_id) <> ''),
    CHECK (parent_caused_by_event_id IS NULL OR btrim(parent_caused_by_event_id) <> ''),
    CHECK (previous_delegation_id IS NULL OR btrim(previous_delegation_id) <> ''),
    CHECK (cancel_key IS NULL OR btrim(cancel_key) <> ''),
    CHECK ((updated_epoch_second, updated_nano)
        >= (created_epoch_second, created_nano)),
    CHECK (snapshot ? 'id' AND snapshot->>'id' = delegation_id
        AND snapshot ? 'operationKey' AND snapshot->>'operationKey' = operation_key
        AND snapshot ? 'state' AND snapshot->>'state' = state
        AND snapshot ? 'revision' AND (snapshot->>'revision')::bigint = revision)
);

-- Wait 是父 Loop 的精确持久等待；引用的委派由适配器在同一事务按完整 parent identity 校验。
CREATE TABLE public.agent_runtime_delegation_waits (
    tenant_id varchar(160) NOT NULL,
    parent_session_id varchar(240) NOT NULL,
    parent_target_kind varchar(10) NOT NULL CHECK (parent_target_kind IN ('AGENT','TEAM')),
    parent_target_id varchar(240) NOT NULL,
    parent_end_user_id bigint NOT NULL CHECK (parent_end_user_id > 0),
    parent_agent_id varchar(240) NOT NULL,
    parent_causation_input_id varchar(240) NOT NULL,
    parent_run_id varchar(240) NOT NULL,
    parent_trace_id varchar(240) NOT NULL,
    parent_span_id varchar(240) NOT NULL,
    parent_root_input_id varchar(240) NOT NULL,
    parent_caused_by_event_id varchar(240),
    parent_causation_identity_ref varchar(240) NOT NULL,
    wait_id varchar(240) NOT NULL,
    delivered boolean NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    recovery_input_id varchar(240),
    recovery_data jsonb,
    PRIMARY KEY (tenant_id, parent_run_id, wait_id),
    UNIQUE (tenant_id, recovery_input_id),
    CHECK (btrim(tenant_id) <> '' AND btrim(parent_session_id) <> ''
        AND btrim(parent_target_id) <> '' AND btrim(parent_agent_id) <> ''
        AND btrim(parent_causation_input_id) <> '' AND btrim(parent_run_id) <> ''
        AND btrim(parent_trace_id) <> '' AND btrim(parent_span_id) <> ''
        AND btrim(parent_root_input_id) <> ''
        AND btrim(parent_causation_identity_ref) <> '' AND btrim(wait_id) <> ''),
    CHECK (parent_caused_by_event_id IS NULL OR btrim(parent_caused_by_event_id) <> ''),
    CHECK ((NOT delivered AND revision = 1) OR (delivered AND revision = 2)),
    CHECK ((recovery_input_id IS NULL) = (recovery_data IS NULL)),
    CHECK (recovery_input_id IS NULL OR btrim(recovery_input_id) <> ''),
    CHECK (recovery_data IS NULL OR jsonb_typeof(recovery_data) = 'object'),
    CHECK (snapshot ? 'id' AND snapshot->>'id' = wait_id
        AND snapshot ? 'delivered'
        AND (snapshot->>'delivered')::boolean = delivered
        AND snapshot ? 'revision' AND (snapshot->>'revision')::bigint = revision)
);

-- 固定列同时支撑精确 parent 查询、幂等冲突定位、状态恢复与 revision CAS。
CREATE INDEX agent_runtime_delegations_fixed_lookup
    ON public.agent_runtime_delegations(
        tenant_id,parent_run_id,delegation_id,operation_key,state,revision);
CREATE INDEX agent_runtime_delegations_active_scan
    ON public.agent_runtime_delegations(
        tenant_id,updated_epoch_second,updated_nano,parent_run_id,delegation_id)
    WHERE state IN ('QUEUED','RUNNING','WAITING');
CREATE INDEX agent_runtime_delegations_session_history
    ON public.agent_runtime_delegations(
        tenant_id,parent_session_id,parent_target_kind,parent_target_id,
        parent_end_user_id,created_epoch_second,created_nano,delegation_id,parent_run_id);
CREATE INDEX agent_runtime_delegation_waits_pending_scan
    ON public.agent_runtime_delegation_waits(tenant_id,parent_run_id,wait_id,revision)
    WHERE delivered = false;

COMMENT ON TABLE public.agent_runtime_delegations IS '原生 Agent 委派的冻结 Entry 快照与单调生命周期账本';
COMMENT ON COLUMN public.agent_runtime_delegations.tenant_id IS '租户隔离键，也是 parent 与 child Run 的共同租户';
COMMENT ON COLUMN public.agent_runtime_delegations.parent_session_id IS '父 Run 所属产品 Session';
COMMENT ON COLUMN public.agent_runtime_delegations.parent_target_kind IS '父 Session 冻结的服务目标类型';
COMMENT ON COLUMN public.agent_runtime_delegations.parent_target_id IS '父 Session 冻结的服务目标标识';
COMMENT ON COLUMN public.agent_runtime_delegations.parent_end_user_id IS '父 Run 继承的入口终端用户身份';
COMMENT ON COLUMN public.agent_runtime_delegations.parent_agent_id IS '执行父 Run 的实际 Agent';
COMMENT ON COLUMN public.agent_runtime_delegations.parent_causation_input_id IS '创建父 Run 的因果输入';
COMMENT ON COLUMN public.agent_runtime_delegations.parent_run_id IS '委派所属的精确父 Run';
COMMENT ON COLUMN public.agent_runtime_delegations.parent_trace_id IS '父 Run 冻结的 trace 标识';
COMMENT ON COLUMN public.agent_runtime_delegations.parent_span_id IS '父 Run 冻结的 span 标识';
COMMENT ON COLUMN public.agent_runtime_delegations.parent_root_input_id IS '父 Run trace 的根输入';
COMMENT ON COLUMN public.agent_runtime_delegations.parent_caused_by_event_id IS '父 Run trace 的可选因果事件';
COMMENT ON COLUMN public.agent_runtime_delegations.parent_causation_identity_ref IS '父 Run 原调用方验证事实引用';
COMMENT ON COLUMN public.agent_runtime_delegations.delegation_id IS '父 Run 内稳定且幂等的委派标识';
COMMENT ON COLUMN public.agent_runtime_delegations.operation_key IS '父动作的稳定操作键；同父 Run 不得换绑委派';
COMMENT ON COLUMN public.agent_runtime_delegations.child_run_id IS '完整 claim 前预分配并冻结的 child Run 标识';
COMMENT ON COLUMN public.agent_runtime_delegations.child_input_id IS '完整 claim 前预分配并冻结的 child 输入标识';
COMMENT ON COLUMN public.agent_runtime_delegations.previous_delegation_id IS '同一父 Run 中可选的前序委派标识';
COMMENT ON COLUMN public.agent_runtime_delegations.state IS 'QUEUED、RUNNING、WAITING 或不可逆终态';
COMMENT ON COLUMN public.agent_runtime_delegations.cancel_key IS '首次取消请求的稳定键；设置后不可替换或清除';
COMMENT ON COLUMN public.agent_runtime_delegations.revision IS '委派生命周期 CAS revision';
COMMENT ON COLUMN public.agent_runtime_delegations.snapshot IS 'Jackson Runtime 格式的完整 Entry 冻结快照；状态变化时整体替换';
COMMENT ON COLUMN public.agent_runtime_delegations.created_epoch_second IS 'Entry 创建 Instant 的秒部分，保持纳秒精度';
COMMENT ON COLUMN public.agent_runtime_delegations.created_nano IS 'Entry 创建 Instant 的纳秒部分';
COMMENT ON COLUMN public.agent_runtime_delegations.updated_epoch_second IS 'Entry 最近状态 Instant 的秒部分，保持纳秒精度';
COMMENT ON COLUMN public.agent_runtime_delegations.updated_nano IS 'Entry 最近状态 Instant 的纳秒部分';

COMMENT ON TABLE public.agent_runtime_delegation_waits IS '父 Run 对精确委派集合的冻结 Wait 与投递确认';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.tenant_id IS '租户隔离键';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.parent_session_id IS '等待所属父 Run 的产品 Session';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.parent_target_kind IS '父 Session 冻结的服务目标类型';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.parent_target_id IS '父 Session 冻结的服务目标标识';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.parent_end_user_id IS '父 Run 继承的入口终端用户身份';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.parent_agent_id IS '执行父 Run 的实际 Agent';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.parent_causation_input_id IS '创建父 Run 的因果输入';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.parent_run_id IS '等待所属的精确父 Run';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.parent_trace_id IS '父 Run 冻结的 trace 标识';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.parent_span_id IS '父 Run 冻结的 span 标识';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.parent_root_input_id IS '父 Run trace 的根输入';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.parent_caused_by_event_id IS '父 Run trace 的可选因果事件';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.parent_causation_identity_ref IS '父 Run 原调用方验证事实引用';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.wait_id IS '父动作冻结的稳定等待标识';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.delivered IS '等待投递工作是否已收敛；父 Run 已终态时可无 RECOVERY 快照收尾';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.revision IS '等待登记与投递确认的 CAS revision';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.snapshot IS 'Jackson Runtime 格式的完整 Wait 冻结快照';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.recovery_input_id IS '首次冻结的父 Run RECOVERY input 标识；同租户只能绑定一个 Wait';
COMMENT ON COLUMN public.agent_runtime_delegation_waits.recovery_data IS 'Jackson Runtime 格式的原 RECOVERY envelope；重认证不改写此快照';

-- 只允许适配器定义的 revision+1 状态迁移；任务、目标、child 身份和 parent 身份不可变。
CREATE FUNCTION public.guard_agent_runtime_delegation_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id
       OR NEW.parent_session_id <> OLD.parent_session_id
       OR NEW.parent_target_kind <> OLD.parent_target_kind
       OR NEW.parent_target_id <> OLD.parent_target_id
       OR NEW.parent_end_user_id <> OLD.parent_end_user_id
       OR NEW.parent_agent_id <> OLD.parent_agent_id
       OR NEW.parent_causation_input_id <> OLD.parent_causation_input_id
       OR NEW.parent_run_id <> OLD.parent_run_id
       OR NEW.parent_trace_id <> OLD.parent_trace_id
       OR NEW.parent_span_id <> OLD.parent_span_id
       OR NEW.parent_root_input_id <> OLD.parent_root_input_id
       OR NEW.parent_caused_by_event_id IS DISTINCT FROM OLD.parent_caused_by_event_id
       OR NEW.parent_causation_identity_ref <> OLD.parent_causation_identity_ref
       OR NEW.delegation_id <> OLD.delegation_id
       OR NEW.operation_key <> OLD.operation_key
       OR NEW.child_run_id <> OLD.child_run_id
       OR NEW.child_input_id <> OLD.child_input_id
       OR NEW.previous_delegation_id IS DISTINCT FROM OLD.previous_delegation_id
       OR NEW.created_epoch_second <> OLD.created_epoch_second
       OR NEW.created_nano <> OLD.created_nano
       OR (NEW.snapshot - ARRAY['state','result','cancelKey','revision','updatedAt'])
          <> (OLD.snapshot - ARRAY['state','result','cancelKey','revision','updatedAt']) THEN
        RAISE EXCEPTION 'agent runtime delegation identity is immutable';
    END IF;
    IF NEW.revision <> OLD.revision + 1
       OR (NEW.updated_epoch_second,NEW.updated_nano)
          < (OLD.updated_epoch_second,OLD.updated_nano)
       OR (OLD.cancel_key IS NOT NULL
           AND NEW.cancel_key IS DISTINCT FROM OLD.cancel_key) THEN
        RAISE EXCEPTION 'agent runtime delegation CAS is invalid';
    END IF;
    IF OLD.state IN ('COMPLETED','FAILED','CANCELLED') THEN
        RAISE EXCEPTION 'agent runtime delegation state transition is invalid';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guard_agent_runtime_delegation_update
BEFORE UPDATE ON public.agent_runtime_delegations
FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_delegation_mutation();

CREATE FUNCTION public.guard_agent_runtime_delegation_wait_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id
       OR NEW.parent_session_id <> OLD.parent_session_id
       OR NEW.parent_target_kind <> OLD.parent_target_kind
       OR NEW.parent_target_id <> OLD.parent_target_id
       OR NEW.parent_end_user_id <> OLD.parent_end_user_id
       OR NEW.parent_agent_id <> OLD.parent_agent_id
       OR NEW.parent_causation_input_id <> OLD.parent_causation_input_id
       OR NEW.parent_run_id <> OLD.parent_run_id
       OR NEW.parent_trace_id <> OLD.parent_trace_id
       OR NEW.parent_span_id <> OLD.parent_span_id
       OR NEW.parent_root_input_id <> OLD.parent_root_input_id
       OR NEW.parent_caused_by_event_id IS DISTINCT FROM OLD.parent_caused_by_event_id
       OR NEW.parent_causation_identity_ref <> OLD.parent_causation_identity_ref
       OR NEW.wait_id <> OLD.wait_id
       OR (NEW.snapshot - ARRAY['delivered','revision'])
          <> (OLD.snapshot - ARRAY['delivered','revision']) THEN
        RAISE EXCEPTION 'agent runtime delegation wait mutation is invalid';
    END IF;
    IF OLD.recovery_data IS NULL AND NEW.recovery_data IS NOT NULL THEN
        IF OLD.delivered OR NEW.delivered <> OLD.delivered
           OR NEW.revision <> OLD.revision OR NEW.snapshot <> OLD.snapshot
           OR NEW.recovery_input_id IS NULL THEN
            RAISE EXCEPTION 'agent runtime delegation recovery freeze is invalid';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.recovery_data IS DISTINCT FROM NEW.recovery_data
       OR OLD.recovery_input_id IS DISTINCT FROM NEW.recovery_input_id
       OR OLD.delivered OR NOT NEW.delivered
       OR NEW.revision <> OLD.revision + 1 THEN
        RAISE EXCEPTION 'agent runtime delegation wait delivery is invalid';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guard_agent_runtime_delegation_wait_update
BEFORE UPDATE ON public.agent_runtime_delegation_waits
FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_delegation_wait_mutation();

CREATE FUNCTION public.reject_agent_runtime_delegation_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime delegation facts are immutable';
END;
$$;

CREATE TRIGGER reject_agent_runtime_delegation_delete
BEFORE DELETE ON public.agent_runtime_delegations
FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_delegation_delete();
CREATE TRIGGER reject_agent_runtime_delegation_wait_delete
BEFORE DELETE ON public.agent_runtime_delegation_waits
FOR EACH ROW EXECUTE FUNCTION public.reject_agent_runtime_delegation_delete();

ALTER TABLE public.agent_runtime_delegations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_delegations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_delegations
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE public.agent_runtime_delegation_waits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_delegation_waits FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_delegation_waits
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
