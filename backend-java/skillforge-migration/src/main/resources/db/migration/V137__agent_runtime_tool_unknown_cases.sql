-- Tool OUTCOME_UNKNOWN 的运维案件与告警投递；不授予新的 Tool permit。
CREATE TABLE public.nw_runtime_tool_unknown_cases (
    tenant_id text NOT NULL,
    case_id text NOT NULL CHECK (btrim(case_id)<>''),
    run_id text NOT NULL,
    action_id text NOT NULL CHECK (btrim(action_id)<>''),
    invocation_id text NOT NULL CHECK (btrim(invocation_id)<>''),
    unknown_fact_ref text NOT NULL CHECK (btrim(unknown_fact_ref)<>''),
    query_data text NOT NULL CHECK (btrim(query_data)<>''),
    status text NOT NULL CHECK (status IN('WAITING_EVIDENCE','INVESTIGATING','OVERDUE','CONFIRMED')),
    user_status text NOT NULL CHECK (user_status IN('PENDING_CONFIRMATION','EVIDENCE_CONFIRMED')),
    responsible_operator text NOT NULL CHECK (btrim(responsible_operator)<>''),
    deadline_second bigint NOT NULL,
    deadline_nano integer NOT NULL CHECK (deadline_nano BETWEEN 0 AND 999999999),
    case_revision bigint NOT NULL CHECK (case_revision>0),
    confirmed_adoption_id text,
    opened_second bigint NOT NULL,
    opened_nano integer NOT NULL CHECK (opened_nano BETWEEN 0 AND 999999999),
    updated_second bigint NOT NULL,
    updated_nano integer NOT NULL CHECK (updated_nano BETWEEN 0 AND 999999999),
    PRIMARY KEY(tenant_id,case_id),
    UNIQUE(tenant_id,run_id,action_id,invocation_id,unknown_fact_ref),
    CHECK ((status='CONFIRMED')=(confirmed_adoption_id IS NOT NULL)),
    CHECK ((status='CONFIRMED')=(user_status='EVIDENCE_CONFIRMED')),
    CHECK ((deadline_second,deadline_nano)>=(opened_second,opened_nano)),
    CHECK ((updated_second,updated_nano)>=(opened_second,opened_nano)),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    FOREIGN KEY(tenant_id,action_id,invocation_id,unknown_fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts(tenant_id,action_id,invocation_id,fact_ref),
    FOREIGN KEY(tenant_id,confirmed_adoption_id)
        REFERENCES public.nw_runtime_tool_unknown_adoptions(tenant_id,adoption_id)
);

CREATE TABLE public.nw_runtime_tool_unknown_case_operations (
    tenant_id text NOT NULL,
    case_id text NOT NULL,
    operation_id text NOT NULL CHECK (btrim(operation_id)<>''),
    operation_kind text NOT NULL CHECK (operation_kind IN('OPEN','TAKEOVER','OVERDUE','CONFIRM')),
    command_data text NOT NULL CHECK (btrim(command_data)<>''),
    output_revision bigint NOT NULL CHECK (output_revision>0),
    output_data text NOT NULL CHECK (btrim(output_data)<>''),
    recorded_second bigint NOT NULL,
    recorded_nano integer NOT NULL CHECK (recorded_nano BETWEEN 0 AND 999999999),
    PRIMARY KEY(tenant_id,case_id,operation_id),
    FOREIGN KEY(tenant_id,case_id)
        REFERENCES public.nw_runtime_tool_unknown_cases(tenant_id,case_id)
);

CREATE TABLE public.nw_runtime_tool_unknown_case_alerts (
    tenant_id text NOT NULL,
    alert_id text NOT NULL CHECK (btrim(alert_id)<>''),
    case_id text NOT NULL,
    case_revision bigint NOT NULL CHECK (case_revision>0),
    alert_kind text NOT NULL CHECK (alert_kind IN('OPENED','OVERDUE','CONFIRMED')),
    alert_data text NOT NULL CHECK (btrim(alert_data)<>''),
    delivery_attempt bigint NOT NULL DEFAULT 0 CHECK (delivery_attempt>=0),
    claim_id text,
    claimed_by text,
    claim_until_second bigint,
    claim_until_nano integer,
    delivered boolean NOT NULL DEFAULT false,
    delivered_claim_id text,
    created_second bigint NOT NULL,
    created_nano integer NOT NULL CHECK (created_nano BETWEEN 0 AND 999999999),
    delivered_second bigint,
    delivered_nano integer,
    PRIMARY KEY(tenant_id,alert_id),
    UNIQUE(tenant_id,case_id,case_revision,alert_kind),
    CHECK ((delivery_attempt=0 AND claim_id IS NULL AND claimed_by IS NULL
            AND claim_until_second IS NULL AND claim_until_nano IS NULL)
        OR (delivery_attempt>0 AND claim_id IS NOT NULL AND btrim(claim_id)<>''
            AND claimed_by IS NOT NULL AND btrim(claimed_by)<>''
            AND claim_until_second IS NOT NULL
            AND claim_until_nano BETWEEN 0 AND 999999999)),
    CHECK ((NOT delivered AND delivered_claim_id IS NULL
            AND delivered_second IS NULL AND delivered_nano IS NULL)
        OR (delivered AND delivered_claim_id=claim_id AND delivered_second IS NOT NULL
            AND delivered_nano BETWEEN 0 AND 999999999)),
    FOREIGN KEY(tenant_id,case_id)
        REFERENCES public.nw_runtime_tool_unknown_cases(tenant_id,case_id)
);

CREATE INDEX idx_agent_runtime_tool_unknown_case_poll
    ON public.nw_runtime_tool_unknown_cases(tenant_id,status,deadline_second,deadline_nano,case_id)
    WHERE status<>'CONFIRMED';
CREATE INDEX idx_agent_runtime_tool_unknown_alert_poll
    ON public.nw_runtime_tool_unknown_case_alerts(
        tenant_id,claim_until_second,claim_until_nano,created_second,created_nano,alert_id)
    WHERE delivered=false;

ALTER TABLE public.nw_runtime_tool_unknown_cases ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_tool_unknown_cases FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_tool_unknown_cases
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
ALTER TABLE public.nw_runtime_tool_unknown_case_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_tool_unknown_case_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_tool_unknown_case_operations
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
ALTER TABLE public.nw_runtime_tool_unknown_case_alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_tool_unknown_case_alerts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_tool_unknown_case_alerts
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));

CREATE FUNCTION public.agent_runtime_tool_unknown_case_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'tool_unknown_case_history_immutable'; END;
$$;
CREATE TRIGGER tool_unknown_case_operations_immutable BEFORE UPDATE OR DELETE
    ON public.nw_runtime_tool_unknown_case_operations FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_tool_unknown_case_append_only();
CREATE TRIGGER tool_unknown_case_alerts_no_delete BEFORE DELETE
    ON public.nw_runtime_tool_unknown_case_alerts FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_tool_unknown_case_append_only();

COMMENT ON TABLE public.nw_runtime_tool_unknown_cases IS 'Tool OUTCOME_UNKNOWN 运维案件；无可信adoption时用户状态固定为PENDING_CONFIRMATION';
COMMENT ON TABLE public.nw_runtime_tool_unknown_case_operations IS '案件打开、接管、超时和可信确认的不可变操作历史';
COMMENT ON TABLE public.nw_runtime_tool_unknown_case_alerts IS '案件状态迁移的持久告警投递记录；租约领取并在出口成功后ACK';
COMMENT ON COLUMN public.nw_runtime_tool_unknown_cases.responsible_operator IS '当前责任人；Starter初值为worker-id，可由CAS接管';
COMMENT ON COLUMN public.nw_runtime_tool_unknown_cases.deadline_second IS '当前责任截止时间；Starter初值使用callback-recovery-window';
COMMENT ON COLUMN public.nw_runtime_tool_unknown_cases.user_status IS '无证据时只能PENDING_CONFIRMATION；不得投影为成功';
