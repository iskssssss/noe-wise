-- Callback registration 可精确绑定持有执行租约的 Tool Action。
ALTER TABLE public.agent_runtime_callback_registrations
    ADD COLUMN target_kind text NOT NULL DEFAULT 'OTHER'
        CHECK (target_kind IN ('OTHER', 'ACTION')),
    ADD COLUMN action_agent_id text,
    ADD COLUMN action_session_id text,
    ADD COLUMN action_end_user_id bigint,
    ADD COLUMN action_run_id text,
    ADD COLUMN action_id text,
    ADD COLUMN action_invocation_id text,
    ADD COLUMN registered_action_revision bigint,
    ADD CONSTRAINT ck_agent_runtime_callback_action_binding CHECK (
        (action_agent_id IS NULL AND action_session_id IS NULL
         AND action_end_user_id IS NULL AND action_run_id IS NULL
         AND action_id IS NULL AND action_invocation_id IS NULL
         AND registered_action_revision IS NULL)
        OR (btrim(action_agent_id) <> '' AND btrim(action_session_id) <> ''
            AND action_end_user_id > 0 AND btrim(action_run_id) <> ''
            AND btrim(action_id) <> '' AND btrim(action_invocation_id) <> ''
            AND registered_action_revision > 0)),
    ADD CONSTRAINT fk_agent_runtime_callback_action_binding_run
        FOREIGN KEY (tenant_id, action_agent_id, action_session_id,
                     action_end_user_id, action_run_id)
        REFERENCES public.agent_runtime_runs
            (tenant_id, agent_id, session_id, end_user_id, run_id),
    ADD CONSTRAINT fk_agent_runtime_callback_action_binding_action
        FOREIGN KEY (tenant_id, action_id, action_invocation_id)
        REFERENCES public.agent_runtime_actions
            (tenant_id, action_id, invocation_id),
    ADD CONSTRAINT uq_agent_runtime_callback_action_binding
        UNIQUE (tenant_id, callback_id, action_run_id, action_id,
                action_invocation_id),
    ADD CONSTRAINT ck_agent_runtime_callback_action_target_kind CHECK (
        (target_kind = 'ACTION' AND action_id IS NOT NULL)
        OR (target_kind = 'OTHER' AND action_id IS NULL));

CREATE FUNCTION public.assert_agent_runtime_callback_action_binding()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.target_kind = 'ACTION' AND NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_actions a
          JOIN public.agent_runtime_runs r
            ON r.tenant_id = a.tenant_id AND r.agent_id = a.agent_id
           AND r.session_id = a.session_id AND r.end_user_id = a.end_user_id
           AND r.run_id = a.run_id
         WHERE a.tenant_id = NEW.tenant_id
           AND a.agent_id = NEW.action_agent_id
           AND a.session_id = NEW.action_session_id
           AND a.end_user_id = NEW.action_end_user_id
           AND a.run_id = NEW.action_run_id
           AND a.action_id = NEW.action_id
           AND a.invocation_id = NEW.action_invocation_id
           AND a.execute_call_kind = 'TOOL_INVOKE'
           AND a.state = 'CLAIMED'
           AND a."authorization" = 'EXECUTE'
           AND a.action_revision = NEW.registered_action_revision
           AND a.lease_action_revision = a.action_revision
           AND a.lease_until_epoch_second IS NOT NULL
           AND (a.lease_until_epoch_second::numeric
                + a.lease_until_nano::numeric / 1000000000)
               > extract(epoch FROM clock_timestamp())
           AND r.cancel_request_id IS NULL
           AND r.status NOT IN
               ('COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED')) THEN
        RAISE EXCEPTION 'callback action binding source mismatch';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_callback_action_binding_sources
    AFTER INSERT ON public.agent_runtime_callback_registrations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_callback_action_binding();

CREATE FUNCTION public.guard_agent_runtime_callback_action_binding()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.target_kind, NEW.action_agent_id, NEW.action_session_id,
        NEW.action_end_user_id, NEW.action_run_id, NEW.action_id,
        NEW.action_invocation_id, NEW.registered_action_revision)
       IS DISTINCT FROM
       (OLD.target_kind, OLD.action_agent_id, OLD.action_session_id,
        OLD.action_end_user_id, OLD.action_run_id, OLD.action_id,
        OLD.action_invocation_id, OLD.registered_action_revision) THEN
        RAISE EXCEPTION 'callback action binding is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_runtime_callback_action_binding_immutable
    BEFORE UPDATE ON public.agent_runtime_callback_registrations
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_callback_action_binding();
