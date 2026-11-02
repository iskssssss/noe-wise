CREATE SEQUENCE public.agent_runtime_session_persistence_fencing_seq
    AS bigint START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE TABLE public.agent_runtime_session_input_streams (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    next_sequence bigint NOT NULL,
    CONSTRAINT agent_runtime_session_input_streams_pkey
        PRIMARY KEY (tenant_id, agent_id, session_id),
    CONSTRAINT uq_agent_runtime_session_input_stream_owner
        UNIQUE (tenant_id, agent_id, session_id, end_user_id),
    CONSTRAINT agent_runtime_session_input_stream_owner_root_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_session_owners
            (tenant_id, agent_id, session_id, end_user_id),
    CONSTRAINT ck_agent_runtime_session_input_stream_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(session_id) <> ''
        AND btrim(agent_id) <> '' AND next_sequence > 0)
);

CREATE TABLE public.agent_runtime_input_ledger (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    input_id varchar(160) NOT NULL,
    request_fingerprint text NOT NULL,
    verification_ref text NOT NULL,
    causation_identity_ref text NOT NULL,
    decision_kind varchar(32) NOT NULL,
    run_id varchar(160),
    receipt_status varchar(16) NOT NULL,
    ledger_revision bigint NOT NULL,
    command_data text NOT NULL,
    receipt_data text NOT NULL,
    recorded_epoch_second bigint NOT NULL,
    recorded_nano integer NOT NULL,
    expires_epoch_second bigint NOT NULL,
    expires_nano integer NOT NULL,
    CONSTRAINT agent_runtime_input_ledger_pkey
        PRIMARY KEY (tenant_id, agent_id, session_id, input_id),
    CONSTRAINT uq_agent_runtime_input_ledger_owner
        UNIQUE (tenant_id, agent_id, session_id, end_user_id, input_id),
    CONSTRAINT agent_runtime_input_ledger_owner_root_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_session_owners
            (tenant_id, agent_id, session_id, end_user_id),
    CONSTRAINT ck_agent_runtime_input_ledger_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(session_id) <> ''
        AND btrim(agent_id) <> '' AND btrim(input_id) <> ''
        AND btrim(request_fingerprint) <> '' AND btrim(verification_ref) <> ''
        AND btrim(causation_identity_ref) <> ''),
    CONSTRAINT ck_agent_runtime_input_ledger_decision CHECK (
        decision_kind IN ('ACCEPTED_NEW_RUN', 'ACCEPTED_EXISTING_RUN', 'REJECTED')
        AND receipt_status IN ('ACCEPTED', 'REJECTED')
        AND ledger_revision = 1
        AND ((receipt_status = 'ACCEPTED'
                AND decision_kind IN ('ACCEPTED_NEW_RUN', 'ACCEPTED_EXISTING_RUN')
                AND run_id IS NOT NULL AND btrim(run_id) <> '')
             OR (receipt_status = 'REJECTED' AND decision_kind = 'REJECTED'))),
    CONSTRAINT ck_agent_runtime_input_ledger_codec CHECK (
        btrim(command_data) <> '' AND btrim(receipt_data) <> ''),
    CONSTRAINT ck_agent_runtime_input_ledger_times CHECK (
        recorded_nano BETWEEN 0 AND 999999999
        AND expires_nano BETWEEN 0 AND 999999999
        AND (expires_epoch_second, expires_nano)
            >= (recorded_epoch_second, recorded_nano))
);

CREATE TABLE public.agent_runtime_session_inbox (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    input_id varchar(160) NOT NULL,
    inbox_sequence bigint NOT NULL,
    status varchar(16) NOT NULL,
    inbox_revision bigint NOT NULL,
    attempts integer NOT NULL,
    available_epoch_second bigint NOT NULL,
    available_nano integer NOT NULL,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    last_requested_epoch_second bigint,
    last_requested_nano integer,
    result_ref text,
    reason_code text,
    entry_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    CONSTRAINT agent_runtime_session_inbox_pkey
        PRIMARY KEY (tenant_id, agent_id, session_id, input_id),
    CONSTRAINT uq_agent_runtime_session_inbox_sequence
        UNIQUE (tenant_id, agent_id, session_id, inbox_sequence),
    CONSTRAINT agent_runtime_session_inbox_stream_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id)
        REFERENCES public.agent_runtime_session_input_streams
            (tenant_id, agent_id, session_id, end_user_id),
    CONSTRAINT agent_runtime_session_inbox_input_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id, input_id)
        REFERENCES public.agent_runtime_input_ledger
            (tenant_id, agent_id, session_id, end_user_id, input_id),
    CONSTRAINT ck_agent_runtime_session_inbox_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(session_id) <> ''
        AND btrim(agent_id) <> '' AND btrim(input_id) <> ''
        AND inbox_sequence > 0 AND inbox_revision > 0 AND attempts >= 0),
    CONSTRAINT ck_agent_runtime_session_inbox_status CHECK (
        status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_agent_runtime_session_inbox_available CHECK (
        available_nano BETWEEN 0 AND 999999999),
    CONSTRAINT ck_agent_runtime_session_inbox_last_requested CHECK (
        (last_requested_epoch_second IS NULL AND last_requested_nano IS NULL)
        OR (last_requested_epoch_second IS NOT NULL
            AND last_requested_nano BETWEEN 0 AND 999999999)),
    CONSTRAINT ck_agent_runtime_session_inbox_lease CHECK (
        ((status = 'PROCESSING') =
            (lease_claim_id IS NOT NULL AND lease_worker_id IS NOT NULL
             AND lease_fencing_token IS NOT NULL
             AND lease_until_epoch_second IS NOT NULL
             AND lease_until_nano IS NOT NULL))
        AND (lease_claim_id IS NULL OR
            (btrim(lease_claim_id) <> '' AND btrim(lease_worker_id) <> ''
             AND lease_fencing_token > 0
             AND lease_until_nano BETWEEN 0 AND 999999999))),
    CONSTRAINT ck_agent_runtime_session_inbox_result CHECK (
        (reason_code IS NULL OR result_ref IS NOT NULL)
        AND (status NOT IN ('COMPLETED', 'FAILED') OR result_ref IS NOT NULL)
        AND (status <> 'FAILED' OR reason_code IS NOT NULL)),
    CONSTRAINT ck_agent_runtime_session_inbox_codec CHECK (btrim(entry_data) <> ''),
    CONSTRAINT ck_agent_runtime_session_inbox_times CHECK (
        created_nano BETWEEN 0 AND 999999999
        AND updated_nano BETWEEN 0 AND 999999999
        AND (updated_epoch_second, updated_nano)
            >= (created_epoch_second, created_nano))
);

CREATE INDEX idx_agent_runtime_session_inbox_head
    ON public.agent_runtime_session_inbox
        (tenant_id, agent_id, session_id, inbox_sequence)
    WHERE status IN ('QUEUED', 'PROCESSING');
CREATE INDEX idx_agent_runtime_session_inbox_schedule
    ON public.agent_runtime_session_inbox
        (tenant_id, available_epoch_second, available_nano, session_id)
    WHERE status = 'QUEUED';

CREATE TABLE public.agent_runtime_inbox_mutations (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    input_id varchar(160) NOT NULL,
    mutation_key text NOT NULL,
    mutation_kind varchar(16) NOT NULL,
    command_fingerprint varchar(64) NOT NULL,
    output_revision bigint NOT NULL,
    output_status varchar(16) NOT NULL,
    output_fingerprint varchar(64) NOT NULL,
    output_data text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_inbox_mutations_pkey
        PRIMARY KEY (tenant_id, agent_id, session_id, input_id, mutation_key),
    CONSTRAINT agent_runtime_inbox_mutation_entry_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, input_id)
        REFERENCES public.agent_runtime_session_inbox
            (tenant_id, agent_id, session_id, input_id),
    CONSTRAINT ck_agent_runtime_inbox_mutation_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(agent_id) <> ''
        AND btrim(session_id) <> '' AND btrim(input_id) <> ''
        AND btrim(mutation_key) <> ''
        AND mutation_kind IN ('COMPLETE', 'RETRY', 'FAIL')
        AND command_fingerprint ~ '^[0-9a-f]{64}$'
        AND output_fingerprint ~ '^[0-9a-f]{64}$'
        AND output_revision > 1
        AND output_status IN ('QUEUED', 'COMPLETED', 'FAILED')
        AND btrim(output_data) <> '')
);

CREATE TABLE public.agent_runtime_runs (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    run_id varchar(160) NOT NULL,
    causation_input_id varchar(160) NOT NULL,
    status varchar(16) NOT NULL,
    run_revision bigint NOT NULL,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    last_requested_epoch_second bigint,
    last_requested_nano integer,
    cancel_request_id varchar(160),
    cancel_requested_epoch_second bigint,
    cancel_requested_nano integer,
    record_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    CONSTRAINT agent_runtime_runs_pkey PRIMARY KEY (tenant_id, run_id),
    CONSTRAINT uq_agent_runtime_run_owner
        UNIQUE (tenant_id, session_id, run_id),
    CONSTRAINT agent_runtime_run_input_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id,
                     causation_input_id)
        REFERENCES public.agent_runtime_input_ledger
            (tenant_id, agent_id, session_id, end_user_id, input_id),
    CONSTRAINT ck_agent_runtime_run_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(session_id) <> ''
        AND btrim(agent_id) <> '' AND btrim(run_id) <> ''
        AND btrim(causation_input_id) <> '' AND run_revision > 0),
    CONSTRAINT ck_agent_runtime_run_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUSPENDED', 'RESUMING',
                   'COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_agent_runtime_run_lease CHECK (
        ((lease_claim_id IS NULL AND lease_worker_id IS NULL
          AND lease_fencing_token IS NULL AND lease_until_epoch_second IS NULL
          AND lease_until_nano IS NULL)
         OR (btrim(lease_claim_id) <> '' AND btrim(lease_worker_id) <> ''
          AND lease_fencing_token > 0 AND lease_until_epoch_second IS NOT NULL
          AND lease_until_nano BETWEEN 0 AND 999999999))
        AND (status NOT IN ('COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED')
             OR lease_claim_id IS NULL)),
    CONSTRAINT ck_agent_runtime_run_last_requested CHECK (
        (last_requested_epoch_second IS NULL AND last_requested_nano IS NULL)
        OR (last_requested_epoch_second IS NOT NULL
            AND last_requested_nano BETWEEN 0 AND 999999999)),
    CONSTRAINT ck_agent_runtime_run_cancel CHECK (
        (cancel_request_id IS NULL
         AND cancel_requested_epoch_second IS NULL
         AND cancel_requested_nano IS NULL)
        OR (btrim(cancel_request_id) <> ''
            AND cancel_requested_epoch_second IS NOT NULL
            AND cancel_requested_nano BETWEEN 0 AND 999999999)),
    CONSTRAINT ck_agent_runtime_run_codec CHECK (btrim(record_data) <> ''),
    CONSTRAINT ck_agent_runtime_run_times CHECK (
        created_nano BETWEEN 0 AND 999999999
        AND updated_nano BETWEEN 0 AND 999999999
        AND (updated_epoch_second, updated_nano)
            >= (created_epoch_second, created_nano))
);

CREATE INDEX idx_agent_runtime_run_recovery
    ON public.agent_runtime_runs
        (tenant_id, status, lease_until_epoch_second, lease_until_nano, run_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'SUSPENDED', 'RESUMING');

CREATE TABLE public.agent_runtime_run_mutations (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    run_id varchar(160) NOT NULL,
    mutation_key text NOT NULL,
    mutation_kind varchar(16) NOT NULL,
    command_fingerprint varchar(64) NOT NULL,
    output_revision bigint NOT NULL,
    output_status varchar(16) NOT NULL,
    output_fingerprint varchar(64) NOT NULL,
    output_data text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_run_mutations_pkey
        PRIMARY KEY (tenant_id, run_id, mutation_key),
    CONSTRAINT agent_runtime_run_mutation_run_fkey
        FOREIGN KEY (tenant_id, session_id, run_id)
        REFERENCES public.agent_runtime_runs (tenant_id, session_id, run_id),
    CONSTRAINT ck_agent_runtime_run_mutation_identity CHECK (
        btrim(mutation_key) <> ''
        AND mutation_kind IN ('RELEASE', 'CANCEL', 'PROGRESS', 'TRANSITION')
        AND command_fingerprint ~ '^[0-9a-f]{64}$'
        AND output_fingerprint ~ '^[0-9a-f]{64}$'
        AND output_revision > 0
        AND output_status IN ('QUEUED', 'RUNNING', 'SUSPENDED', 'RESUMING',
                              'COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED')
        AND btrim(output_data) <> '')
);

CREATE TABLE public.agent_runtime_run_state_publications (
    tenant_id text NOT NULL,
    source_kind varchar(16) NOT NULL,
    source_id varchar(160) NOT NULL,
    event_id varchar(160) NOT NULL,
    source_revision bigint NOT NULL,
    publication_revision bigint NOT NULL,
    available_epoch_second bigint NOT NULL,
    available_nano integer NOT NULL,
    status varchar(16) NOT NULL,
    attempts integer NOT NULL,
    lease_claim_id text,
    lease_worker_id text,
    lease_fencing_token bigint,
    lease_until_epoch_second bigint,
    lease_until_nano integer,
    projection_ref text,
    failure_data text,
    publication_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    updated_epoch_second bigint NOT NULL,
    updated_nano integer NOT NULL,
    CONSTRAINT agent_runtime_run_state_publications_pkey
        PRIMARY KEY (tenant_id, source_id, event_id),
    CONSTRAINT uq_agent_runtime_run_state_publication_revision
        UNIQUE (tenant_id, source_id, source_revision),
    CONSTRAINT agent_runtime_run_state_publication_run_fkey
        FOREIGN KEY (tenant_id, source_id)
        REFERENCES public.agent_runtime_runs (tenant_id, run_id),
    CONSTRAINT ck_agent_runtime_run_state_publication_identity CHECK (
        source_kind = 'run' AND btrim(source_id) <> '' AND btrim(event_id) <> ''
        AND source_revision > 0 AND publication_revision > 0
        AND available_nano BETWEEN 0 AND 999999999 AND attempts >= 0),
    CONSTRAINT ck_agent_runtime_run_state_publication_status CHECK (
        status IN ('PENDING', 'CLAIMED', 'ACKNOWLEDGED')),
    CONSTRAINT ck_agent_runtime_run_state_publication_lease CHECK (
        ((status = 'CLAIMED') =
            (lease_claim_id IS NOT NULL AND lease_worker_id IS NOT NULL
             AND lease_fencing_token IS NOT NULL
             AND lease_until_epoch_second IS NOT NULL
             AND lease_until_nano IS NOT NULL))
        AND (lease_claim_id IS NULL OR
            (btrim(lease_claim_id) <> '' AND btrim(lease_worker_id) <> ''
             AND lease_fencing_token > 0
             AND lease_until_nano BETWEEN 0 AND 999999999))),
    CONSTRAINT ck_agent_runtime_run_state_publication_projection CHECK (
        (status = 'ACKNOWLEDGED') = (projection_ref IS NOT NULL)
        AND (projection_ref IS NULL OR btrim(projection_ref) <> '')),
    CONSTRAINT ck_agent_runtime_run_state_publication_codec CHECK (
        btrim(publication_data) <> ''),
    CONSTRAINT ck_agent_runtime_run_state_publication_times CHECK (
        created_nano BETWEEN 0 AND 999999999
        AND updated_nano BETWEEN 0 AND 999999999
        AND (updated_epoch_second, updated_nano)
            >= (created_epoch_second, created_nano))
);

CREATE INDEX idx_agent_runtime_run_state_publication_claim
    ON public.agent_runtime_run_state_publications
        (tenant_id, status, available_epoch_second, available_nano,
         lease_until_epoch_second, lease_until_nano, source_id, source_revision);

CREATE TABLE public.agent_runtime_run_publication_mutations (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    source_id varchar(160) NOT NULL,
    event_id varchar(160) NOT NULL,
    mutation_key text NOT NULL,
    mutation_kind varchar(16) NOT NULL,
    command_fingerprint varchar(64) NOT NULL,
    output_revision bigint NOT NULL,
    output_status varchar(16) NOT NULL,
    output_fingerprint varchar(64) NOT NULL,
    output_data text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_run_publication_mutations_pkey
        PRIMARY KEY (tenant_id, source_id, event_id, mutation_key),
    CONSTRAINT agent_runtime_run_publication_mutation_publication_fkey
        FOREIGN KEY (tenant_id, source_id, event_id)
        REFERENCES public.agent_runtime_run_state_publications
            (tenant_id, source_id, event_id),
    CONSTRAINT ck_agent_runtime_run_publication_mutation_identity CHECK (
        btrim(session_id) <> '' AND btrim(mutation_key) <> ''
        AND mutation_kind IN ('ACKNOWLEDGE', 'RETRY')
        AND command_fingerprint ~ '^[0-9a-f]{64}$'
        AND output_fingerprint ~ '^[0-9a-f]{64}$'
        AND output_revision > 1
        AND output_status IN ('PENDING', 'ACKNOWLEDGED')
        AND btrim(output_data) <> '')
);

CREATE FUNCTION public.guard_agent_runtime_session_input_stream()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime input stream is not deletable';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.tenant_id, NEW.session_id, NEW.agent_id, NEW.end_user_id)
             <> (OLD.tenant_id, OLD.session_id, OLD.agent_id, OLD.end_user_id)
            OR NEW.next_sequence <> OLD.next_sequence + 1 THEN
            RAISE EXCEPTION 'agent runtime input stream may only advance by one';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_runtime_session_input_stream_guard
    BEFORE UPDATE OR DELETE ON public.agent_runtime_session_input_streams
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_input_stream();

CREATE FUNCTION public.guard_agent_runtime_input_ledger()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime input ledger is immutable';
END;
$$;
CREATE TRIGGER agent_runtime_input_ledger_guard
    BEFORE UPDATE OR DELETE ON public.agent_runtime_input_ledger
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_input_ledger();

CREATE FUNCTION public.guard_agent_runtime_session_inbox()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime inbox entry is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.session_id, NEW.agent_id, NEW.end_user_id,
        NEW.input_id, NEW.inbox_sequence, NEW.created_epoch_second, NEW.created_nano)
       <> (OLD.tenant_id, OLD.session_id, OLD.agent_id, OLD.end_user_id,
        OLD.input_id, OLD.inbox_sequence, OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'agent runtime inbox immutable identity changed';
    END IF;
    IF OLD.status IN ('COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'terminal agent runtime inbox entry is immutable';
    END IF;
    IF NEW.inbox_revision = OLD.inbox_revision THEN
        IF OLD.status <> 'PROCESSING' OR NEW.status <> 'PROCESSING'
           OR NEW.attempts <> OLD.attempts
           OR (NEW.available_epoch_second, NEW.available_nano)
                IS DISTINCT FROM (OLD.available_epoch_second, OLD.available_nano)
           OR NEW.result_ref IS DISTINCT FROM OLD.result_ref
           OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
           OR (NEW.last_requested_epoch_second, NEW.last_requested_nano)
                IS DISTINCT FROM (OLD.last_requested_epoch_second, OLD.last_requested_nano)
           OR NEW.lease_claim_id IS DISTINCT FROM OLD.lease_claim_id
           OR NEW.lease_worker_id IS DISTINCT FROM OLD.lease_worker_id
           OR NEW.lease_fencing_token IS DISTINCT FROM OLD.lease_fencing_token
           OR (NEW.lease_until_epoch_second, NEW.lease_until_nano)
                <= (OLD.lease_until_epoch_second, OLD.lease_until_nano) THEN
            RAISE EXCEPTION 'inbox revision may stay unchanged only for renewal';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.inbox_revision <> OLD.inbox_revision + 1 THEN
        RAISE EXCEPTION 'agent runtime inbox revision must advance by one';
    END IF;
    IF NEW.status = 'PROCESSING' THEN
        IF OLD.status NOT IN ('QUEUED', 'PROCESSING')
           OR NEW.attempts <> OLD.attempts + 1
           OR NEW.last_requested_epoch_second IS NULL
           OR (NEW.available_epoch_second, NEW.available_nano)
                IS DISTINCT FROM (OLD.available_epoch_second, OLD.available_nano)
           OR NEW.result_ref IS DISTINCT FROM OLD.result_ref
           OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
           OR (OLD.status = 'PROCESSING'
               AND NEW.lease_fencing_token <= OLD.lease_fencing_token) THEN
            RAISE EXCEPTION 'invalid agent runtime inbox claim';
        END IF;
    ELSIF OLD.status = 'PROCESSING'
          AND NEW.status IN ('QUEUED', 'COMPLETED', 'FAILED') THEN
        IF NEW.attempts <> OLD.attempts
           OR (NEW.last_requested_epoch_second, NEW.last_requested_nano)
                IS DISTINCT FROM (OLD.last_requested_epoch_second, OLD.last_requested_nano) THEN
            RAISE EXCEPTION 'inbox mutation must preserve attempts';
        END IF;
    ELSE
        RAISE EXCEPTION 'illegal agent runtime inbox state transition';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_runtime_session_inbox_guard
    BEFORE UPDATE OR DELETE ON public.agent_runtime_session_inbox
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_inbox();

CREATE FUNCTION public.guard_agent_runtime_run()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime run is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.session_id, NEW.agent_id, NEW.end_user_id,
        NEW.run_id, NEW.causation_input_id, NEW.created_epoch_second, NEW.created_nano)
       <> (OLD.tenant_id, OLD.session_id, OLD.agent_id, OLD.end_user_id,
        OLD.run_id, OLD.causation_input_id, OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'agent runtime run immutable identity changed';
    END IF;
    IF OLD.status IN ('COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED') THEN
        RAISE EXCEPTION 'terminal agent runtime run is immutable';
    END IF;
    IF NEW.run_revision = OLD.run_revision THEN
        IF NEW.status <> OLD.status
           OR NEW.cancel_request_id IS DISTINCT FROM OLD.cancel_request_id
           OR NEW.cancel_requested_epoch_second IS DISTINCT FROM OLD.cancel_requested_epoch_second
           OR NEW.cancel_requested_nano IS DISTINCT FROM OLD.cancel_requested_nano
           OR NOT (
               (OLD.lease_claim_id IS NULL AND NEW.lease_claim_id IS NOT NULL
                AND NEW.last_requested_epoch_second IS NOT NULL)
               OR (OLD.lease_claim_id IS NOT NULL AND NEW.lease_claim_id IS NULL
                   AND (NEW.last_requested_epoch_second, NEW.last_requested_nano)
                       IS NOT DISTINCT FROM
                           (OLD.last_requested_epoch_second, OLD.last_requested_nano))
               OR (OLD.lease_claim_id IS NOT NULL AND NEW.lease_claim_id IS NOT NULL
                   AND ((NEW.lease_claim_id, NEW.lease_worker_id,
                         NEW.lease_fencing_token)
                            IS NOT DISTINCT FROM
                                (OLD.lease_claim_id, OLD.lease_worker_id,
                                 OLD.lease_fencing_token)
                        AND (NEW.lease_until_epoch_second, NEW.lease_until_nano)
                            > (OLD.lease_until_epoch_second, OLD.lease_until_nano)
                        AND (NEW.last_requested_epoch_second, NEW.last_requested_nano)
                            IS NOT DISTINCT FROM
                                (OLD.last_requested_epoch_second,
                                 OLD.last_requested_nano))
                       OR (NEW.lease_fencing_token > OLD.lease_fencing_token
                           AND NEW.last_requested_epoch_second IS NOT NULL))) THEN
            RAISE EXCEPTION 'run revision may stay unchanged only for lease mutation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.run_revision <> OLD.run_revision + 1 THEN
        RAISE EXCEPTION 'agent runtime run revision must advance by one';
    END IF;
    IF (NEW.last_requested_epoch_second, NEW.last_requested_nano)
        IS DISTINCT FROM (OLD.last_requested_epoch_second, OLD.last_requested_nano) THEN
        RAISE EXCEPTION 'run domain mutation must preserve last lease request';
    END IF;
    IF OLD.status = NEW.status THEN
        IF NEW.cancel_request_id IS NOT DISTINCT FROM OLD.cancel_request_id
           AND OLD.status <> 'RUNNING' THEN
            RAISE EXCEPTION 'same-state run revision requires progress or cancel';
        END IF;
    ELSIF NOT (
        (OLD.status = 'QUEUED' AND NEW.status IN ('RUNNING', 'FAILED', 'CANCELLED'))
        OR (OLD.status = 'RUNNING' AND NEW.status IN
            ('SUSPENDED', 'COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED'))
        OR (OLD.status = 'SUSPENDED' AND NEW.status IN
            ('RESUMING', 'FAILED', 'CANCELLED'))
        OR (OLD.status = 'RESUMING' AND NEW.status IN
            ('RUNNING', 'SUSPENDED', 'COMPLETED', 'TERMINATED', 'FAILED', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'illegal agent runtime run state transition';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_runtime_run_guard
    BEFORE UPDATE OR DELETE ON public.agent_runtime_runs
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_run();

CREATE FUNCTION public.guard_agent_runtime_run_state_publication()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime run publication is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.source_kind, NEW.source_id, NEW.event_id,
        NEW.source_revision, NEW.created_epoch_second, NEW.created_nano)
       <> (OLD.tenant_id, OLD.source_kind, OLD.source_id, OLD.event_id,
        OLD.source_revision, OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'agent runtime run publication immutable identity changed';
    END IF;
    IF OLD.status = 'ACKNOWLEDGED' THEN
        RAISE EXCEPTION 'acknowledged run publication is immutable';
    END IF;
    IF NEW.publication_revision <> OLD.publication_revision + 1 THEN
        RAISE EXCEPTION 'run publication revision must advance by one';
    END IF;
    IF NEW.status = 'CLAIMED' THEN
        IF OLD.status NOT IN ('PENDING', 'CLAIMED')
           OR NEW.attempts <> OLD.attempts + 1
           OR (OLD.status = 'CLAIMED'
               AND NEW.lease_fencing_token <= OLD.lease_fencing_token) THEN
            RAISE EXCEPTION 'invalid run publication claim';
        END IF;
    ELSIF OLD.status = 'CLAIMED'
          AND NEW.status IN ('PENDING', 'ACKNOWLEDGED') THEN
        IF NEW.attempts <> OLD.attempts THEN
            RAISE EXCEPTION 'run publication mutation must preserve attempts';
        END IF;
    ELSE
        RAISE EXCEPTION 'illegal run publication state transition';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_runtime_run_state_publication_guard
    BEFORE UPDATE OR DELETE ON public.agent_runtime_run_state_publications
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_run_state_publication();

CREATE FUNCTION public.guard_agent_runtime_session_mutation_journal()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'agent runtime session mutation journal is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_runtime_inbox_mutation_guard
    BEFORE UPDATE OR DELETE ON public.agent_runtime_inbox_mutations
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_mutation_journal();
CREATE TRIGGER agent_runtime_run_mutation_guard
    BEFORE UPDATE OR DELETE ON public.agent_runtime_run_mutations
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_mutation_journal();
CREATE TRIGGER agent_runtime_run_publication_mutation_guard
    BEFORE UPDATE OR DELETE ON public.agent_runtime_run_publication_mutations
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_session_mutation_journal();

CREATE FUNCTION public.assert_agent_runtime_inbox_mutation_journaled()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'PROCESSING' AND NEW.status IN ('QUEUED', 'COMPLETED', 'FAILED')
       AND NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_inbox_mutations operation
         WHERE operation.tenant_id = NEW.tenant_id
           AND operation.agent_id = NEW.agent_id
           AND operation.session_id = NEW.session_id
           AND operation.input_id = NEW.input_id
           AND operation.output_revision = NEW.inbox_revision
           AND operation.output_status = NEW.status
           AND operation.output_data = NEW.entry_data) THEN
        RAISE EXCEPTION 'inbox mutation must commit its journal';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER agent_runtime_inbox_mutation_journal
    AFTER UPDATE ON public.agent_runtime_session_inbox
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_inbox_mutation_journaled();

CREATE FUNCTION public.assert_agent_runtime_run_mutation_journaled()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.run_revision = OLD.run_revision + 1
        OR (NEW.run_revision = OLD.run_revision
            AND OLD.lease_claim_id IS NOT NULL AND NEW.lease_claim_id IS NULL))
       AND NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_run_mutations operation
         WHERE operation.tenant_id = NEW.tenant_id
           AND operation.run_id = NEW.run_id
           AND operation.output_revision = NEW.run_revision
           AND operation.output_status = NEW.status
           AND operation.output_data = NEW.record_data) THEN
        RAISE EXCEPTION 'run mutation must commit its journal';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER agent_runtime_run_mutation_journal
    AFTER UPDATE ON public.agent_runtime_runs
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_run_mutation_journaled();

CREATE FUNCTION public.assert_agent_runtime_run_publication_mutation_journaled()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'CLAIMED' AND NEW.status IN ('PENDING', 'ACKNOWLEDGED')
       AND NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_run_publication_mutations operation
         WHERE operation.tenant_id = NEW.tenant_id
           AND operation.source_id = NEW.source_id
           AND operation.event_id = NEW.event_id
           AND operation.output_revision = NEW.publication_revision
           AND operation.output_status = NEW.status
           AND operation.output_data = NEW.publication_data) THEN
        RAISE EXCEPTION 'run publication mutation must commit its journal';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER agent_runtime_run_publication_mutation_journal
    AFTER UPDATE ON public.agent_runtime_run_state_publications
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_run_publication_mutation_journaled();

ALTER TABLE public.agent_runtime_session_input_streams ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_input_streams FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_input_stream_tenant_isolation
    ON public.agent_runtime_session_input_streams
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_input_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_input_ledger FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_input_ledger_tenant_isolation
    ON public.agent_runtime_input_ledger
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_session_inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_session_inbox_tenant_isolation
    ON public.agent_runtime_session_inbox
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_inbox_mutations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_inbox_mutations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_inbox_mutation_tenant_isolation
    ON public.agent_runtime_inbox_mutations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_run_tenant_isolation
    ON public.agent_runtime_runs
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_run_mutations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_run_mutations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_run_mutation_tenant_isolation
    ON public.agent_runtime_run_mutations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_run_state_publications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_run_state_publications FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_run_state_publication_tenant_isolation
    ON public.agent_runtime_run_state_publications
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_run_publication_mutations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_run_publication_mutations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_run_publication_mutation_tenant_isolation
    ON public.agent_runtime_run_publication_mutations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON SEQUENCE public.agent_runtime_session_persistence_fencing_seq IS
    'Inbox、Run 与 Run publication 共用的跨进程单调 fencing token；回滚可留空洞';
COMMENT ON TABLE public.agent_runtime_session_input_streams IS
    '每个 Session 的固定 owner 与下一条私有 Inbox sequence';
COMMENT ON CONSTRAINT agent_runtime_session_input_stream_owner_root_fkey
    ON public.agent_runtime_session_input_streams IS
    'Input stream 必须引用三个 Session 持久域共用的完整 owner root';
COMMENT ON TABLE public.agent_runtime_input_ledger IS
    'InputId 首次准入的永久幂等事实；expiry 仅供未来独立清理策略，不允许复用 InputId';
COMMENT ON CONSTRAINT agent_runtime_input_ledger_owner_root_fkey
    ON public.agent_runtime_input_ledger IS
    '包括 REJECTED 在内的 Input receipt 必须引用共用 Session owner root';
COMMENT ON TABLE public.agent_runtime_session_inbox IS
    '由输入准入事务原子创建、按 Session 严格顺序处理的 Inbox 事实';
COMMENT ON TABLE public.agent_runtime_inbox_mutations IS
    'Inbox complete/retry/fail 的不可变响应丢失重放 journal';
COMMENT ON TABLE public.agent_runtime_runs IS
    'AgentRun 状态、revision、checkpoint/outcome codec、取消与当前 fencing lease 的权威记录';
COMMENT ON TABLE public.agent_runtime_run_mutations IS
    'Run release/cancel/progress/transition 的不可变响应丢失重放 journal';
COMMENT ON TABLE public.agent_runtime_run_state_publications IS
    '与 Run 领域状态同事务冻结的 Session/Runtime state publication';
COMMENT ON TABLE public.agent_runtime_run_publication_mutations IS
    'Run state publication acknowledge/retry 的不可变响应丢失重放 journal';

COMMENT ON COLUMN public.agent_runtime_input_ledger.expires_epoch_second IS
    '独立 input ledger retention 截止秒；SDK-3C 不执行清理且过期后仍禁止复用';
COMMENT ON COLUMN public.agent_runtime_input_ledger.command_data IS
    '首次 InputAcceptance 的 adapter-private canonical codec，用于恢复原事实并执行语义重放判定';
COMMENT ON COLUMN public.agent_runtime_session_inbox.inbox_sequence IS
    '与 inbox_revision 分离的每 Session 连续严格顺序';
COMMENT ON COLUMN public.agent_runtime_session_inbox.last_requested_epoch_second IS
    '最近一次成功 claim 的调用方 requestedAt 秒；与数据库权威时钟分离，仅作审计';
COMMENT ON COLUMN public.agent_runtime_session_inbox.entry_data IS
    '完整 InboxEntry 的 adapter-private canonical codec，并与查询列交叉核对';
COMMENT ON COLUMN public.agent_runtime_runs.record_data IS
    '完整 RunRecord 的 adapter-private canonical codec，并与 owner/status/revision/lease/time 列交叉核对';
COMMENT ON COLUMN public.agent_runtime_runs.last_requested_epoch_second IS
    '最近一次成功 acquireLease 的调用方 requestedAt 秒；与数据库权威时钟分离，仅作审计';
COMMENT ON COLUMN public.agent_runtime_run_state_publications.publication_data IS
    '完整 StateEventPublication，包含首次冻结的 target、event、destination 与当前 publication revision';
COMMENT ON COLUMN public.agent_runtime_run_state_publications.failure_data IS
    '最近一次 retry 的完整 PortFailure 私有 codec；不进入公共 publication';
COMMENT ON COLUMN public.agent_runtime_run_state_publications.projection_ref IS
    'ACKNOWLEDGED publication 的稳定 Session/Runtime 投影事实引用';
