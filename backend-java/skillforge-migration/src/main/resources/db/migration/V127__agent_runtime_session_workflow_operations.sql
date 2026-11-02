CREATE TABLE public.nw_runtime_session_workflow_operations (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    action_id text NOT NULL CHECK (btrim(action_id)<>''),
    invocation_id text NOT NULL CHECK (btrim(invocation_id)<>''),
    attempt_id text CHECK (attempt_id IS NULL OR btrim(attempt_id)<>''),
    attempt_revision bigint,
    retry_revision bigint,
    retry_pending_hash text CHECK (retry_pending_hash IS NULL OR retry_pending_hash~'^[0-9a-f]{64}$'),
    operation_hash text NOT NULL CHECK (operation_hash~'^[0-9a-f]{64}$'),
    key_data text NOT NULL CHECK (btrim(key_data)<>''),
    command_kind text NOT NULL CHECK (command_kind IN(
        'CLAIM','START','RESULT','RETRY_PENDING','RETRY_CONFIRMED','COMPLETE','FAIL','UNKNOWN')),
    command_data text NOT NULL CHECK (btrim(command_data)<>''),
    PRIMARY KEY(tenant_id,operation_hash),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    FOREIGN KEY(tenant_id,retry_pending_hash)
        REFERENCES public.nw_runtime_session_workflow_operations(tenant_id,operation_hash)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK ((command_kind='RESULT')=(attempt_id IS NOT NULL AND attempt_revision IS NOT NULL)),
    CHECK (attempt_revision IS NULL OR attempt_revision>0),
    CHECK ((command_kind IN('RETRY_PENDING','RETRY_CONFIRMED'))=(retry_revision IS NOT NULL)),
    CHECK (retry_revision IS NULL OR retry_revision>0),
    CHECK ((command_kind='RETRY_CONFIRMED')=(retry_pending_hash IS NOT NULL))
);
CREATE UNIQUE INDEX uq_agent_runtime_session_workflow_result_attempt
    ON public.nw_runtime_session_workflow_operations(tenant_id,run_id,action_id,invocation_id,attempt_id)
    WHERE command_kind='RESULT';
CREATE UNIQUE INDEX uq_agent_runtime_session_workflow_result_revision
    ON public.nw_runtime_session_workflow_operations(
        tenant_id,run_id,action_id,invocation_id,attempt_revision)
    WHERE command_kind='RESULT';
CREATE UNIQUE INDEX uq_agent_runtime_session_workflow_retry_generation_kind
    ON public.nw_runtime_session_workflow_operations(
        tenant_id,run_id,action_id,invocation_id,retry_revision,command_kind)
    WHERE command_kind IN('RETRY_PENDING','RETRY_CONFIRMED');
CREATE INDEX ix_agent_runtime_session_workflow_retry_latest
    ON public.nw_runtime_session_workflow_operations(
        tenant_id,run_id,action_id,invocation_id,retry_revision DESC)
    WHERE command_kind IN('RETRY_PENDING','RETRY_CONFIRMED');

CREATE TABLE public.nw_runtime_session_workflow_consumptions (
    tenant_id text NOT NULL,
    run_id text NOT NULL,
    action_id text NOT NULL CHECK (btrim(action_id)<>''),
    invocation_id text NOT NULL CHECK (btrim(invocation_id)<>''),
    consume_operation_id text NOT NULL CHECK (btrim(consume_operation_id)<>''),
    scope_data text NOT NULL CHECK (btrim(scope_data)<>''),
    proof_data text NOT NULL CHECK (btrim(proof_data)<>''),
    PRIMARY KEY(tenant_id,consume_operation_id),
    FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id),
    FOREIGN KEY(tenant_id,action_id,invocation_id)
        REFERENCES public.agent_runtime_actions(tenant_id,action_id,invocation_id)
        DEFERRABLE INITIALLY DEFERRED
);

ALTER TABLE public.nw_runtime_session_workflow_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_workflow_operations FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_workflow_consumptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_workflow_consumptions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_session_workflow_operations
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE POLICY tenant_isolation ON public.nw_runtime_session_workflow_consumptions
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));

CREATE FUNCTION public.assert_agent_runtime_session_workflow_operation_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    pending public.nw_runtime_session_workflow_operations%ROWTYPE;
BEGIN
    IF NEW.command_kind <> 'CLAIM' AND NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_actions a
         WHERE a.tenant_id=NEW.tenant_id AND a.action_id=NEW.action_id
           AND a.invocation_id=NEW.invocation_id AND a.run_id=NEW.run_id) THEN
        RAISE EXCEPTION 'session workflow operation requires exact action source';
    END IF;
    IF NEW.command_kind='RETRY_CONFIRMED' THEN
        SELECT * INTO pending
          FROM public.nw_runtime_session_workflow_operations o
         WHERE o.tenant_id=NEW.tenant_id AND o.operation_hash=NEW.retry_pending_hash;
        IF NOT FOUND OR pending.command_kind<>'RETRY_PENDING'
                OR (pending.run_id,pending.action_id,pending.invocation_id,pending.retry_revision)
                   IS DISTINCT FROM
                   (NEW.run_id,NEW.action_id,NEW.invocation_id,NEW.retry_revision) THEN
            RAISE EXCEPTION 'session workflow retry confirmation requires exact pending source';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER agent_runtime_session_workflow_operation_source
    AFTER INSERT ON public.nw_runtime_session_workflow_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_session_workflow_operation_source();

CREATE FUNCTION public.assert_agent_runtime_session_workflow_consumption_source()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_actions a
         WHERE a.tenant_id=NEW.tenant_id AND a.action_id=NEW.action_id
           AND a.invocation_id=NEW.invocation_id AND a.run_id=NEW.run_id) THEN
        RAISE EXCEPTION 'session workflow consumption requires exact action source';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER agent_runtime_session_workflow_consumption_source
    AFTER INSERT ON public.nw_runtime_session_workflow_consumptions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_session_workflow_consumption_source();

CREATE FUNCTION public.agent_runtime_session_workflow_operation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_workflow_operation_immutable'; END;
$$;
CREATE TRIGGER session_workflow_operation_immutable BEFORE UPDATE OR DELETE
    ON public.nw_runtime_session_workflow_operations FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_session_workflow_operation_immutable();
CREATE TRIGGER session_workflow_consumption_immutable BEFORE UPDATE OR DELETE
    ON public.nw_runtime_session_workflow_consumptions FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_session_workflow_operation_immutable();

COMMENT ON TABLE public.nw_runtime_session_workflow_operations IS 'Workflow claim、start、分attempt结果、分代重试与terminal原命令journal';
COMMENT ON TABLE public.nw_runtime_session_workflow_consumptions IS '每次Workflow permit消费尝试的无bearer证明登记';
COMMENT ON COLUMN public.nw_runtime_session_workflow_operations.tenant_id IS '原Run租户及RLS分区';
COMMENT ON COLUMN public.nw_runtime_session_workflow_operations.run_id IS '原已提交Session Run';
COMMENT ON COLUMN public.nw_runtime_session_workflow_operations.action_id IS 'Workflow逻辑Action；claim journal允许早于V99 Action行';
COMMENT ON COLUMN public.nw_runtime_session_workflow_operations.invocation_id IS 'Workflow逻辑调用身份';
COMMENT ON COLUMN public.nw_runtime_session_workflow_operations.attempt_id IS 'RESULT所属V99 attempt；非RESULT为空';
COMMENT ON COLUMN public.nw_runtime_session_workflow_operations.attempt_revision IS 'RESULT所属permit签发时的Action revision及恢复顺序；非RESULT为空';
COMMENT ON COLUMN public.nw_runtime_session_workflow_operations.retry_revision IS '重试代开始前的Action revision；非重试命令为空';
COMMENT ON COLUMN public.nw_runtime_session_workflow_operations.retry_pending_hash IS 'RETRY_CONFIRMED严格引用的同作用域同代RETRY_PENDING；其他命令为空';
COMMENT ON COLUMN public.nw_runtime_session_workflow_operations.operation_hash IS '完整强类型operation key的SHA-256';
COMMENT ON COLUMN public.nw_runtime_session_workflow_operations.key_data IS '作用域、命令类别和分代key的规范编码';
COMMENT ON COLUMN public.nw_runtime_session_workflow_operations.command_kind IS '原claim、start、result、retry或终态命令类别';
COMMENT ON COLUMN public.nw_runtime_session_workflow_operations.command_data IS '强类型原命令规范编码；不含bearer原值';
COMMENT ON INDEX public.uq_agent_runtime_session_workflow_result_attempt IS '每个Workflow attempt只冻结一个Provider结果';
COMMENT ON INDEX public.uq_agent_runtime_session_workflow_result_revision IS '每个Workflow Action revision只冻结一个Provider结果并确定恢复顺序';
COMMENT ON INDEX public.uq_agent_runtime_session_workflow_retry_generation_kind IS '每个Workflow重试代分别只冻结一条pending与confirmed';
COMMENT ON INDEX public.ix_agent_runtime_session_workflow_retry_latest IS '按Action revision定位最新Workflow重试代';
COMMENT ON COLUMN public.nw_runtime_session_workflow_consumptions.tenant_id IS '原Run租户及RLS分区';
COMMENT ON COLUMN public.nw_runtime_session_workflow_consumptions.run_id IS 'permit所属的原已提交Session Run';
COMMENT ON COLUMN public.nw_runtime_session_workflow_consumptions.action_id IS 'permit所属的V99 Action';
COMMENT ON COLUMN public.nw_runtime_session_workflow_consumptions.invocation_id IS 'permit所属的V99 invocation';
COMMENT ON COLUMN public.nw_runtime_session_workflow_consumptions.consume_operation_id IS '每次消费尝试的新操作身份';
COMMENT ON COLUMN public.nw_runtime_session_workflow_consumptions.scope_data IS 'Run、Action和invocation作用域的规范编码';
COMMENT ON COLUMN public.nw_runtime_session_workflow_consumptions.proof_data IS 'intent、STARTED事实与不可逆bearer摘要，不含bearer原值';
COMMENT ON FUNCTION public.agent_runtime_session_workflow_operation_immutable() IS '禁止覆写或删除Workflow原命令与消费登记';
COMMENT ON FUNCTION public.assert_agent_runtime_session_workflow_operation_source() IS '要求非claim命令具有精确V99 Action来源，并闭合retry pending到confirmed关系';
COMMENT ON FUNCTION public.assert_agent_runtime_session_workflow_consumption_source() IS '要求permit消费登记与原Run、Action和invocation严格一致';
