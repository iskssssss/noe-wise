-- V98: Workflow Execution 独立权威账本、私有 mutation journal 与领域自有 durable outbox。
-- 除完整 owner/run 外键核验外，本 schema 不读取、锁定或更新 façade Run 业务事实。

CREATE SEQUENCE public.agent_runtime_execution_fencing_seq
    AS bigint START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE TABLE public.agent_runtime_executions (
    tenant_id                       text NOT NULL,
    execution_id                    varchar(160) NOT NULL,
    session_id                      text NOT NULL,
    agent_id                        text NOT NULL,
    end_user_id                     bigint NOT NULL,
    run_id                          varchar(160) NOT NULL,
    causation_input_id              varchar(160) NOT NULL,
    execution_unit_id               text NOT NULL,
    definition_version              text NOT NULL,
    execution_mode                  varchar(16) NOT NULL,
    causative_action_id             text,
    delegation_id                   text,
    state                           varchar(24) NOT NULL,
    execution_revision              bigint NOT NULL,
    checkpoint_schema_id            text NOT NULL,
    checkpoint_canonical_json       text NOT NULL,
    waiting_suspend_ref             text,
    waiting_action_key              text,
    result_ref                      text,
    reason_code                     text,
    lease_claim_id                  text,
    lease_worker_id                 text,
    lease_fencing_token             bigint,
    lease_execution_revision        bigint,
    lease_until_epoch_second        bigint,
    lease_until_nano                integer,
    last_requested_epoch_second     bigint,
    last_requested_nano             integer,
    cancel_request_id               varchar(160),
    cancel_requested_epoch_second   bigint,
    cancel_requested_nano           integer,
    record_data                     text NOT NULL,
    created_epoch_second            bigint NOT NULL,
    created_nano                    integer NOT NULL,
    updated_epoch_second            bigint NOT NULL,
    updated_nano                    integer NOT NULL,
    CONSTRAINT agent_runtime_executions_pkey
        PRIMARY KEY (tenant_id, execution_id),
    CONSTRAINT uq_agent_runtime_execution_full_owner
        UNIQUE (tenant_id, execution_id, agent_id, session_id, end_user_id, run_id),
    CONSTRAINT agent_runtime_execution_run_fkey
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id, run_id)
        REFERENCES public.agent_runtime_runs
            (tenant_id, agent_id, session_id, end_user_id, run_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_execution_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(execution_id) <> ''
        AND btrim(session_id) <> '' AND btrim(agent_id) <> ''
        AND end_user_id > 0 AND btrim(run_id) <> ''
        AND btrim(causation_input_id) <> ''
        AND btrim(execution_unit_id) <> '' AND btrim(definition_version) <> ''
        AND execution_revision > 0),
    CONSTRAINT ck_agent_runtime_execution_metadata CHECK (
        execution_mode IN ('FOREGROUND', 'BACKGROUND')
        AND (causative_action_id IS NULL OR btrim(causative_action_id) <> '')
        AND (delegation_id IS NULL OR btrim(delegation_id) <> '')),
    CONSTRAINT ck_agent_runtime_execution_state CHECK (
        state IN ('QUEUED', 'RUNNING', 'WAITING_HITL', 'WAITING_EXTERNAL', 'RESUMING',
                  'COMPLETED', 'FAILED', 'LOOP_TERMINATED', 'CANCELLED')),
    CONSTRAINT ck_agent_runtime_execution_checkpoint CHECK (
        btrim(checkpoint_schema_id) <> ''
        AND btrim(checkpoint_canonical_json) <> ''),
    CONSTRAINT ck_agent_runtime_execution_waiting CHECK (
        (state IN ('WAITING_HITL', 'WAITING_EXTERNAL')
         AND waiting_suspend_ref IS NOT NULL
         AND waiting_action_key IS NOT NULL
         AND btrim(waiting_suspend_ref) <> ''
         AND btrim(waiting_action_key) <> '')
        OR (state NOT IN ('WAITING_HITL', 'WAITING_EXTERNAL')
            AND waiting_suspend_ref IS NULL
            AND waiting_action_key IS NULL)),
    CONSTRAINT ck_agent_runtime_execution_result CHECK (
        (state IN ('WAITING_HITL', 'WAITING_EXTERNAL', 'COMPLETED', 'FAILED',
                   'LOOP_TERMINATED', 'CANCELLED')) = (result_ref IS NOT NULL)
        AND (result_ref IS NULL OR btrim(result_ref) <> '')),
    CONSTRAINT ck_agent_runtime_execution_reason CHECK (
        (state IN ('FAILED', 'LOOP_TERMINATED')) = (reason_code IS NOT NULL)
        AND (reason_code IS NULL OR btrim(reason_code) <> '')),
    CONSTRAINT ck_agent_runtime_execution_lease CHECK (
        ((lease_claim_id IS NULL AND lease_worker_id IS NULL
          AND lease_fencing_token IS NULL AND lease_execution_revision IS NULL
          AND lease_until_epoch_second IS NULL AND lease_until_nano IS NULL)
         OR (lease_claim_id IS NOT NULL AND lease_worker_id IS NOT NULL
          AND lease_fencing_token IS NOT NULL
          AND lease_execution_revision IS NOT NULL
          AND lease_until_epoch_second IS NOT NULL
          AND lease_until_nano IS NOT NULL
          AND btrim(lease_claim_id) <> '' AND btrim(lease_worker_id) <> ''
          AND lease_fencing_token > 0 AND lease_execution_revision > 0
          AND lease_execution_revision <= execution_revision
          AND lease_until_nano BETWEEN 0 AND 999999999))
        AND (state NOT IN ('COMPLETED', 'FAILED', 'LOOP_TERMINATED', 'CANCELLED')
             OR lease_claim_id IS NULL)),
    CONSTRAINT ck_agent_runtime_execution_last_requested CHECK (
        (last_requested_epoch_second IS NULL AND last_requested_nano IS NULL)
        OR (last_requested_epoch_second IS NOT NULL
            AND last_requested_nano IS NOT NULL
            AND last_requested_nano BETWEEN 0 AND 999999999)),
    CONSTRAINT ck_agent_runtime_execution_cancel CHECK (
        (cancel_request_id IS NULL AND cancel_requested_epoch_second IS NULL
         AND cancel_requested_nano IS NULL)
        OR (cancel_request_id IS NOT NULL
            AND btrim(cancel_request_id) <> ''
            AND cancel_requested_epoch_second IS NOT NULL
            AND cancel_requested_nano IS NOT NULL
            AND cancel_requested_nano BETWEEN 0 AND 999999999)),
    CONSTRAINT ck_agent_runtime_execution_codec CHECK (btrim(record_data) <> ''),
    CONSTRAINT ck_agent_runtime_execution_times CHECK (
        created_nano BETWEEN 0 AND 999999999
        AND updated_nano BETWEEN 0 AND 999999999
        AND (updated_epoch_second, updated_nano)
            >= (created_epoch_second, created_nano))
);

CREATE INDEX idx_agent_runtime_execution_recovery
    ON public.agent_runtime_executions
        (tenant_id, state, lease_until_epoch_second, lease_until_nano,
         updated_epoch_second, updated_nano, execution_id)
    WHERE state IN ('QUEUED', 'RUNNING', 'WAITING_HITL', 'WAITING_EXTERNAL', 'RESUMING');

CREATE TABLE public.agent_runtime_execution_mutations (
    tenant_id                 text NOT NULL,
    execution_id              varchar(160) NOT NULL,
    session_id                text NOT NULL,
    agent_id                  text NOT NULL,
    end_user_id               bigint NOT NULL,
    run_id                    varchar(160) NOT NULL,
    mutation_key              text NOT NULL,
    mutation_kind             varchar(16) NOT NULL,
    expected_revision         bigint NOT NULL,
    expected_state            varchar(24),
    command_fingerprint       varchar(64) NOT NULL,
    command_data              text NOT NULL,
    output_revision           bigint NOT NULL,
    output_state              varchar(24) NOT NULL,
    output_fingerprint        varchar(64) NOT NULL,
    output_data               text NOT NULL,
    intermediate_revision     bigint,
    intermediate_state        varchar(24),
    intermediate_fingerprint  varchar(64),
    intermediate_data         text,
    created_at                timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_execution_mutations_pkey
        PRIMARY KEY (tenant_id, execution_id, mutation_key),
    CONSTRAINT agent_runtime_execution_mutation_execution_fkey
        FOREIGN KEY (tenant_id, execution_id, agent_id, session_id,
                     end_user_id, run_id)
        REFERENCES public.agent_runtime_executions
            (tenant_id, execution_id, agent_id, session_id, end_user_id, run_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_execution_mutation_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(execution_id) <> ''
        AND btrim(session_id) <> '' AND btrim(agent_id) <> ''
        AND end_user_id > 0 AND btrim(run_id) <> ''
        AND btrim(mutation_key) <> ''),
    CONSTRAINT ck_agent_runtime_execution_mutation_kind CHECK (
        mutation_kind IN ('CREATE', 'RELEASE', 'CANCEL', 'PROGRESS',
                          'TRANSITION', 'RESUME')),
    CONSTRAINT ck_agent_runtime_execution_mutation_state CHECK (
        output_state IN ('QUEUED', 'RUNNING', 'WAITING_HITL', 'WAITING_EXTERNAL', 'RESUMING',
                         'COMPLETED', 'FAILED', 'LOOP_TERMINATED', 'CANCELLED')
        AND (expected_state IS NULL OR expected_state IN
            ('QUEUED', 'RUNNING', 'WAITING_HITL', 'WAITING_EXTERNAL', 'RESUMING',
             'COMPLETED', 'FAILED', 'LOOP_TERMINATED', 'CANCELLED'))),
    CONSTRAINT ck_agent_runtime_execution_mutation_codec CHECK (
        command_fingerprint ~ '^[0-9a-f]{64}$'
        AND output_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(command_data) <> '' AND btrim(output_data) <> ''),
    CONSTRAINT ck_agent_runtime_execution_mutation_revision CHECK (
        (mutation_kind = 'CREATE' AND expected_revision = 0
         AND expected_state IS NULL AND output_revision = 1
         AND output_state = 'QUEUED')
        OR (mutation_kind = 'RELEASE' AND expected_revision > 0
            AND expected_state IS NULL
            AND output_revision >= expected_revision)
        OR (mutation_kind = 'CANCEL' AND expected_revision > 0
            AND expected_state IS NOT NULL AND output_state = expected_state
            AND output_revision = expected_revision + 1)
        OR (mutation_kind = 'PROGRESS' AND expected_revision > 0
            AND expected_state IS NOT NULL
            AND expected_state = 'RUNNING' AND output_state = 'RUNNING'
            AND output_revision = expected_revision + 1)
        OR (mutation_kind = 'TRANSITION' AND expected_revision > 0
            AND expected_state IS NOT NULL
            AND output_revision = expected_revision + 1)
        OR (mutation_kind = 'RESUME' AND expected_revision > 0
            AND expected_state IS NOT NULL
            AND expected_state IN ('WAITING_HITL', 'WAITING_EXTERNAL')
            AND output_revision = expected_revision + 2
            AND output_state = 'RUNNING')),
    CONSTRAINT ck_agent_runtime_execution_mutation_intermediate CHECK (
        (mutation_kind = 'RESUME'
         AND intermediate_revision IS NOT NULL
         AND intermediate_state IS NOT NULL
         AND intermediate_fingerprint IS NOT NULL
         AND intermediate_data IS NOT NULL
         AND intermediate_revision = expected_revision + 1
         AND intermediate_state = 'RESUMING'
         AND intermediate_fingerprint ~ '^[0-9a-f]{64}$'
         AND btrim(intermediate_data) <> '')
        OR (mutation_kind <> 'RESUME'
            AND intermediate_revision IS NULL AND intermediate_state IS NULL
            AND intermediate_fingerprint IS NULL AND intermediate_data IS NULL))
);

CREATE TABLE public.agent_runtime_execution_state_publications (
    tenant_id                    text NOT NULL,
    source_kind                  varchar(16) NOT NULL,
    source_id                    varchar(160) NOT NULL,
    event_id                     varchar(160) NOT NULL,
    source_revision              bigint NOT NULL,
    publication_revision         bigint NOT NULL,
    available_epoch_second       bigint NOT NULL,
    available_nano               integer NOT NULL,
    status                       varchar(16) NOT NULL,
    attempts                     integer NOT NULL,
    lease_claim_id               text,
    lease_worker_id              text,
    lease_fencing_token          bigint,
    lease_until_epoch_second     bigint,
    lease_until_nano             integer,
    projection_ref               text,
    failure_data                 text,
    publication_data             text NOT NULL,
    created_epoch_second         bigint NOT NULL,
    created_nano                 integer NOT NULL,
    updated_epoch_second         bigint NOT NULL,
    updated_nano                 integer NOT NULL,
    CONSTRAINT agent_runtime_execution_state_publications_pkey
        PRIMARY KEY (tenant_id, source_id, event_id),
    CONSTRAINT uq_agent_runtime_execution_state_publication_revision
        UNIQUE (tenant_id, source_id, source_revision),
    CONSTRAINT agent_runtime_execution_state_publication_execution_fkey
        FOREIGN KEY (tenant_id, source_id)
        REFERENCES public.agent_runtime_executions (tenant_id, execution_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_execution_publication_identity CHECK (
        source_kind = 'execution' AND btrim(source_id) <> ''
        AND btrim(event_id) <> '' AND source_revision > 0
        AND publication_revision > 0 AND available_nano BETWEEN 0 AND 999999999
        AND attempts >= 0),
    CONSTRAINT ck_agent_runtime_execution_publication_status CHECK (
        status IN ('PENDING', 'CLAIMED', 'ACKNOWLEDGED')),
    CONSTRAINT ck_agent_runtime_execution_publication_lease CHECK (
        (status = 'CLAIMED'
         AND lease_claim_id IS NOT NULL AND lease_worker_id IS NOT NULL
         AND lease_fencing_token IS NOT NULL
         AND lease_until_epoch_second IS NOT NULL
         AND lease_until_nano IS NOT NULL
         AND btrim(lease_claim_id) <> '' AND btrim(lease_worker_id) <> ''
         AND lease_fencing_token > 0
         AND lease_until_nano BETWEEN 0 AND 999999999)
        OR (status <> 'CLAIMED'
            AND lease_claim_id IS NULL AND lease_worker_id IS NULL
            AND lease_fencing_token IS NULL
            AND lease_until_epoch_second IS NULL AND lease_until_nano IS NULL)),
    CONSTRAINT ck_agent_runtime_execution_publication_projection CHECK (
        (status = 'ACKNOWLEDGED') = (projection_ref IS NOT NULL)
        AND (projection_ref IS NULL OR btrim(projection_ref) <> '')),
    CONSTRAINT ck_agent_runtime_execution_publication_codec CHECK (
        btrim(publication_data) <> ''),
    CONSTRAINT ck_agent_runtime_execution_publication_times CHECK (
        created_nano BETWEEN 0 AND 999999999
        AND updated_nano BETWEEN 0 AND 999999999
        AND (updated_epoch_second, updated_nano)
            >= (created_epoch_second, created_nano))
);

CREATE INDEX idx_agent_runtime_execution_publication_claim
    ON public.agent_runtime_execution_state_publications
        (tenant_id, status, available_epoch_second, available_nano,
         lease_until_epoch_second, lease_until_nano, source_id, source_revision);

CREATE TABLE public.agent_runtime_execution_publication_mutations (
    tenant_id            text NOT NULL,
    source_id            varchar(160) NOT NULL,
    event_id             varchar(160) NOT NULL,
    mutation_key         text NOT NULL,
    mutation_kind        varchar(16) NOT NULL,
    command_fingerprint  varchar(64) NOT NULL,
    command_data         text NOT NULL,
    output_revision      bigint NOT NULL,
    output_status        varchar(16) NOT NULL,
    output_fingerprint   varchar(64) NOT NULL,
    output_data          text NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_runtime_execution_publication_mutations_pkey
        PRIMARY KEY (tenant_id, source_id, event_id, mutation_key),
    CONSTRAINT agent_runtime_execution_publication_mutation_publication_fkey
        FOREIGN KEY (tenant_id, source_id, event_id)
        REFERENCES public.agent_runtime_execution_state_publications
            (tenant_id, source_id, event_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_execution_publication_mutation_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(source_id) <> ''
        AND btrim(event_id) <> '' AND btrim(mutation_key) <> ''),
    CONSTRAINT ck_agent_runtime_execution_publication_mutation_kind CHECK (
        mutation_kind IN ('ACKNOWLEDGE', 'RETRY')),
    CONSTRAINT ck_agent_runtime_execution_publication_mutation_codec CHECK (
        command_fingerprint ~ '^[0-9a-f]{64}$'
        AND output_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(command_data) <> '' AND btrim(output_data) <> ''),
    CONSTRAINT ck_agent_runtime_execution_publication_mutation_output CHECK (
        output_revision > 1
        AND ((mutation_kind = 'ACKNOWLEDGE'
              AND output_status = 'ACKNOWLEDGED')
             OR (mutation_kind = 'RETRY' AND output_status = 'PENDING')))
);

CREATE FUNCTION public.guard_agent_runtime_execution()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime execution is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.execution_id, NEW.session_id, NEW.agent_id,
        NEW.end_user_id, NEW.run_id, NEW.causation_input_id,
        NEW.execution_unit_id, NEW.definition_version, NEW.execution_mode,
        NEW.causative_action_id, NEW.delegation_id,
        NEW.created_epoch_second, NEW.created_nano)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.execution_id, OLD.session_id, OLD.agent_id,
        OLD.end_user_id, OLD.run_id, OLD.causation_input_id,
        OLD.execution_unit_id, OLD.definition_version, OLD.execution_mode,
        OLD.causative_action_id, OLD.delegation_id,
        OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'agent runtime execution immutable identity changed';
    END IF;
    IF NEW.execution_revision = OLD.execution_revision THEN
        IF (NEW.state, NEW.checkpoint_schema_id, NEW.checkpoint_canonical_json,
            NEW.waiting_suspend_ref, NEW.waiting_action_key, NEW.result_ref,
            NEW.reason_code, NEW.cancel_request_id,
            NEW.cancel_requested_epoch_second, NEW.cancel_requested_nano)
           IS DISTINCT FROM
           (OLD.state, OLD.checkpoint_schema_id, OLD.checkpoint_canonical_json,
            OLD.waiting_suspend_ref, OLD.waiting_action_key, OLD.result_ref,
            OLD.reason_code, OLD.cancel_request_id,
            OLD.cancel_requested_epoch_second, OLD.cancel_requested_nano) THEN
            RAISE EXCEPTION 'lease mutation changed execution domain state';
        END IF;
        IF OLD.lease_claim_id IS NULL AND NEW.lease_claim_id IS NULL THEN
            RAISE EXCEPTION 'execution revision may stay unchanged only for lease mutation';
        ELSIF OLD.lease_claim_id IS NULL THEN
            IF NEW.lease_execution_revision <> OLD.execution_revision THEN
                RAISE EXCEPTION 'execution acquisition must bind current revision';
            END IF;
        ELSIF NEW.lease_claim_id IS NULL THEN
            IF (NEW.last_requested_epoch_second, NEW.last_requested_nano)
               IS DISTINCT FROM
               (OLD.last_requested_epoch_second, OLD.last_requested_nano) THEN
                RAISE EXCEPTION 'execution release must preserve last lease request';
            END IF;
        ELSIF (NEW.lease_claim_id, NEW.lease_worker_id,
               NEW.lease_fencing_token)
              IS NOT DISTINCT FROM
              (OLD.lease_claim_id, OLD.lease_worker_id,
               OLD.lease_fencing_token) THEN
            IF NEW.lease_execution_revision
               IS DISTINCT FROM OLD.lease_execution_revision THEN
                RAISE EXCEPTION 'execution renewal changed acquisition revision';
            END IF;
            IF (NEW.lease_until_epoch_second, NEW.lease_until_nano)
               <= (OLD.lease_until_epoch_second, OLD.lease_until_nano) THEN
                RAISE EXCEPTION 'execution renewal must extend lease';
            END IF;
            IF (NEW.last_requested_epoch_second, NEW.last_requested_nano)
               IS DISTINCT FROM
               (OLD.last_requested_epoch_second, OLD.last_requested_nano) THEN
                RAISE EXCEPTION 'execution renewal must preserve last lease request';
            END IF;
        ELSE
            IF NEW.lease_fencing_token <= OLD.lease_fencing_token THEN
                RAISE EXCEPTION 'execution takeover must advance fence';
            END IF;
            IF NEW.lease_execution_revision <> OLD.execution_revision THEN
                RAISE EXCEPTION 'execution takeover must bind current revision';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.execution_revision <> OLD.execution_revision + 1 THEN
        RAISE EXCEPTION 'agent runtime execution revision must advance by one';
    END IF;
    IF (NEW.last_requested_epoch_second, NEW.last_requested_nano)
       IS DISTINCT FROM
       (OLD.last_requested_epoch_second, OLD.last_requested_nano) THEN
        RAISE EXCEPTION 'execution domain mutation must preserve last lease request';
    END IF;
    IF OLD.cancel_request_id IS NULL AND NEW.cancel_request_id IS NOT NULL THEN
        IF OLD.state IN ('COMPLETED', 'FAILED', 'LOOP_TERMINATED', 'CANCELLED')
           OR NEW.state <> OLD.state
           OR (NEW.checkpoint_schema_id, NEW.checkpoint_canonical_json,
               NEW.waiting_suspend_ref, NEW.waiting_action_key, NEW.result_ref,
               NEW.reason_code)
              IS DISTINCT FROM
              (OLD.checkpoint_schema_id, OLD.checkpoint_canonical_json,
               OLD.waiting_suspend_ref, OLD.waiting_action_key, OLD.result_ref,
               OLD.reason_code)
           OR (NEW.lease_claim_id, NEW.lease_worker_id,
               NEW.lease_fencing_token, NEW.lease_execution_revision,
               NEW.lease_until_epoch_second, NEW.lease_until_nano)
              IS DISTINCT FROM
              (OLD.lease_claim_id, OLD.lease_worker_id,
               OLD.lease_fencing_token, OLD.lease_execution_revision,
               OLD.lease_until_epoch_second, OLD.lease_until_nano) THEN
            RAISE EXCEPTION 'execution cancel request changed non-cancel state';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.cancel_request_id, NEW.cancel_requested_epoch_second,
        NEW.cancel_requested_nano)
       IS DISTINCT FROM
       (OLD.cancel_request_id, OLD.cancel_requested_epoch_second,
        OLD.cancel_requested_nano) THEN
        RAISE EXCEPTION 'execution cancel intent is immutable';
    END IF;
    IF OLD.lease_claim_id IS NULL THEN
        RAISE EXCEPTION 'execution domain mutation requires current lease';
    END IF;
    IF OLD.state = NEW.state THEN
        IF OLD.state <> 'RUNNING' THEN
            RAISE EXCEPTION 'same-state execution revision requires progress';
        END IF;
    ELSIF NOT (
        (OLD.state = 'QUEUED' AND NEW.state IN ('RUNNING', 'CANCELLED'))
        OR (OLD.state = 'RUNNING' AND NEW.state IN
            ('WAITING_HITL', 'WAITING_EXTERNAL', 'COMPLETED', 'FAILED',
             'LOOP_TERMINATED', 'CANCELLED'))
        OR (OLD.state IN ('WAITING_HITL', 'WAITING_EXTERNAL') AND NEW.state IN
            ('RESUMING', 'CANCELLED'))
        OR (OLD.state = 'RESUMING' AND NEW.state = 'RUNNING')) THEN
        RAISE EXCEPTION 'illegal agent runtime execution state transition';
    END IF;
    IF NEW.state IN ('COMPLETED', 'FAILED', 'LOOP_TERMINATED', 'CANCELLED') THEN
        IF NEW.lease_claim_id IS NOT NULL THEN
            RAISE EXCEPTION 'terminal execution must clear current lease';
        END IF;
    ELSIF (NEW.lease_claim_id, NEW.lease_worker_id,
           NEW.lease_fencing_token, NEW.lease_execution_revision,
           NEW.lease_until_epoch_second, NEW.lease_until_nano)
          IS DISTINCT FROM
          (OLD.lease_claim_id, OLD.lease_worker_id,
           OLD.lease_fencing_token, OLD.lease_execution_revision,
           OLD.lease_until_epoch_second, OLD.lease_until_nano) THEN
        RAISE EXCEPTION 'non-terminal execution mutation changed current lease';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_runtime_execution_guard
    BEFORE UPDATE OR DELETE ON public.agent_runtime_executions
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_execution();

CREATE FUNCTION public.guard_agent_runtime_execution_publication()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime execution publication is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.source_kind, NEW.source_id, NEW.event_id,
        NEW.source_revision, NEW.created_epoch_second, NEW.created_nano)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.source_kind, OLD.source_id, OLD.event_id,
        OLD.source_revision, OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'execution publication immutable identity changed';
    END IF;
    IF OLD.status = 'ACKNOWLEDGED' THEN
        RAISE EXCEPTION 'acknowledged execution publication is immutable';
    END IF;
    IF NEW.publication_revision <> OLD.publication_revision + 1 THEN
        RAISE EXCEPTION 'execution publication revision must advance by one';
    END IF;
    IF NEW.status = 'CLAIMED' THEN
        IF OLD.status NOT IN ('PENDING', 'CLAIMED')
           OR NEW.attempts <> OLD.attempts + 1
           OR (OLD.status = 'CLAIMED'
               AND NEW.lease_fencing_token <= OLD.lease_fencing_token) THEN
            RAISE EXCEPTION 'invalid execution publication claim';
        END IF;
    ELSIF OLD.status = 'CLAIMED'
          AND NEW.status IN ('PENDING', 'ACKNOWLEDGED') THEN
        IF NEW.attempts <> OLD.attempts THEN
            RAISE EXCEPTION 'execution publication mutation changed attempts';
        END IF;
    ELSE
        RAISE EXCEPTION 'illegal execution publication state transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_runtime_execution_publication_guard
    BEFORE UPDATE OR DELETE
    ON public.agent_runtime_execution_state_publications
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_execution_publication();

CREATE FUNCTION public.reject_agent_runtime_execution_journal_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime execution journal is append-only';
    RETURN NULL;
END;
$$;

CREATE TRIGGER agent_runtime_execution_mutation_immutable
    BEFORE UPDATE OR DELETE ON public.agent_runtime_execution_mutations
    FOR EACH ROW
    EXECUTE FUNCTION public.reject_agent_runtime_execution_journal_mutation();
CREATE TRIGGER agent_runtime_execution_publication_mutation_immutable
    BEFORE UPDATE OR DELETE
    ON public.agent_runtime_execution_publication_mutations
    FOR EACH ROW
    EXECUTE FUNCTION public.reject_agent_runtime_execution_journal_mutation();

CREATE FUNCTION public.assert_agent_runtime_execution_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation public.agent_runtime_execution_mutations%ROWTYPE;
    expected_kind varchar(16);
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO operation
          FROM public.agent_runtime_execution_mutations m
         WHERE m.tenant_id = NEW.tenant_id
           AND m.execution_id = NEW.execution_id
           AND m.mutation_kind = 'CREATE'
           AND m.output_revision = NEW.execution_revision
           AND m.output_state = NEW.state
           AND m.output_data = NEW.record_data;
    ELSIF NEW.execution_revision = OLD.execution_revision
          AND OLD.lease_claim_id IS NOT NULL AND NEW.lease_claim_id IS NULL THEN
        SELECT * INTO operation
          FROM public.agent_runtime_execution_mutations m
         WHERE m.tenant_id = NEW.tenant_id
           AND m.execution_id = NEW.execution_id
           AND m.mutation_kind = 'RELEASE'
           AND m.output_revision = NEW.execution_revision
           AND m.output_state = NEW.state
           AND m.output_data = NEW.record_data;
    ELSIF NEW.execution_revision = OLD.execution_revision THEN
        RETURN NULL;
    ELSE
        IF OLD.cancel_request_id IS NULL AND NEW.cancel_request_id IS NOT NULL THEN
            expected_kind := 'CANCEL';
        ELSIF OLD.state = NEW.state THEN
            expected_kind := 'PROGRESS';
        ELSIF (OLD.state IN ('WAITING_HITL', 'WAITING_EXTERNAL') AND NEW.state = 'RESUMING')
              OR (OLD.state = 'RESUMING' AND NEW.state = 'RUNNING') THEN
            expected_kind := 'RESUME';
        ELSE
            expected_kind := 'TRANSITION';
        END IF;
        SELECT * INTO operation
          FROM public.agent_runtime_execution_mutations m
         WHERE m.tenant_id = NEW.tenant_id
           AND m.execution_id = NEW.execution_id
           AND m.mutation_kind = expected_kind
           AND ((m.output_revision = NEW.execution_revision
                 AND m.output_state = NEW.state
                 AND m.output_data = NEW.record_data)
             OR (m.mutation_kind = 'RESUME'
                 AND m.intermediate_revision = NEW.execution_revision
                 AND m.intermediate_state = NEW.state
                 AND m.intermediate_data = NEW.record_data));
    END IF;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'execution state mutation must commit its journal';
    END IF;
    IF operation.mutation_kind = 'RELEASE' THEN
        RETURN NULL;
    END IF;
    IF operation.mutation_kind = 'RESUME' THEN
        IF NOT EXISTS (
            SELECT 1
              FROM public.agent_runtime_execution_state_publications p
             WHERE p.tenant_id = NEW.tenant_id
               AND p.source_id = NEW.execution_id
               AND p.source_revision = operation.intermediate_revision)
           OR NOT EXISTS (
            SELECT 1
              FROM public.agent_runtime_execution_state_publications p
             WHERE p.tenant_id = NEW.tenant_id
               AND p.source_id = NEW.execution_id
               AND p.source_revision = operation.output_revision) THEN
            RAISE EXCEPTION 'execution resume must commit two publications';
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_execution_state_publications p
         WHERE p.tenant_id = NEW.tenant_id
           AND p.source_id = NEW.execution_id
           AND p.source_revision = operation.output_revision) THEN
        RAISE EXCEPTION 'execution mutation must commit its publication';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_execution_closed
    AFTER INSERT OR UPDATE ON public.agent_runtime_executions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_execution_closed();

CREATE FUNCTION public.assert_agent_runtime_execution_mutation_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_executions e
         WHERE e.tenant_id = NEW.tenant_id
           AND e.execution_id = NEW.execution_id
           AND e.execution_revision = NEW.output_revision
           AND e.state = NEW.output_state
           AND e.record_data = NEW.output_data) THEN
        RAISE EXCEPTION 'execution journal must match current output';
    END IF;
    IF NEW.mutation_kind = 'RELEASE' THEN
        RETURN NULL;
    END IF;
    IF NEW.mutation_kind = 'RESUME' THEN
        IF NOT EXISTS (
            SELECT 1 FROM public.agent_runtime_execution_state_publications p
             WHERE p.tenant_id = NEW.tenant_id
               AND p.source_id = NEW.execution_id
               AND p.source_revision = NEW.intermediate_revision)
           OR NOT EXISTS (
            SELECT 1 FROM public.agent_runtime_execution_state_publications p
             WHERE p.tenant_id = NEW.tenant_id
               AND p.source_id = NEW.execution_id
               AND p.source_revision = NEW.output_revision) THEN
            RAISE EXCEPTION 'execution resume journal requires two publications';
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_execution_state_publications p
         WHERE p.tenant_id = NEW.tenant_id
           AND p.source_id = NEW.execution_id
           AND p.source_revision = NEW.output_revision) THEN
        RAISE EXCEPTION 'execution journal requires matching publication';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_execution_mutation_closed
    AFTER INSERT ON public.agent_runtime_execution_mutations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_execution_mutation_closed();

CREATE FUNCTION public.assert_agent_runtime_execution_publication_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_execution_mutations m
         WHERE m.tenant_id = NEW.tenant_id
           AND m.execution_id = NEW.source_id
           AND (m.output_revision = NEW.source_revision
                OR (m.mutation_kind = 'RESUME'
                    AND m.intermediate_revision = NEW.source_revision))) THEN
        RAISE EXCEPTION 'execution publication must match a journaled revision';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_execution_publication_closed
    AFTER INSERT ON public.agent_runtime_execution_state_publications
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_execution_publication_closed();

CREATE FUNCTION public.assert_agent_runtime_execution_publication_mutation_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_execution_publication_mutations m
         WHERE m.tenant_id = NEW.tenant_id
           AND m.source_id = NEW.source_id AND m.event_id = NEW.event_id
           AND m.output_revision = NEW.publication_revision
           AND m.output_status = NEW.status
           AND m.output_data = NEW.publication_data) THEN
        RAISE EXCEPTION 'execution publication mutation must commit its journal';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_execution_publication_mutation_closed
    AFTER UPDATE ON public.agent_runtime_execution_state_publications
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    WHEN (OLD.status = 'CLAIMED' AND NEW.status IN ('PENDING', 'ACKNOWLEDGED'))
    EXECUTE FUNCTION public.assert_agent_runtime_execution_publication_mutation_closed();

CREATE FUNCTION public.assert_agent_runtime_execution_publication_journal_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_execution_state_publications p
         WHERE p.tenant_id = NEW.tenant_id
           AND p.source_id = NEW.source_id AND p.event_id = NEW.event_id
           AND p.publication_revision = NEW.output_revision
           AND p.status = NEW.output_status
           AND p.publication_data = NEW.output_data) THEN
        RAISE EXCEPTION 'execution publication journal must match current output';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_execution_publication_journal_closed
    AFTER INSERT ON public.agent_runtime_execution_publication_mutations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_execution_publication_journal_closed();

ALTER TABLE public.agent_runtime_executions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_executions FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_execution_tenant_isolation
    ON public.agent_runtime_executions
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_execution_mutations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_execution_mutations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_execution_mutation_tenant_isolation
    ON public.agent_runtime_execution_mutations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_execution_state_publications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_execution_state_publications FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_execution_publication_tenant_isolation
    ON public.agent_runtime_execution_state_publications
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_execution_publication_mutations
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_execution_publication_mutations
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_execution_publication_mutation_tenant_isolation
    ON public.agent_runtime_execution_publication_mutations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON SEQUENCE public.agent_runtime_execution_fencing_seq IS
    'Execution worker lease 与 execution-owned publication 共用的跨进程单调 fencing token；回滚允许留空洞';
COMMENT ON TABLE public.agent_runtime_executions IS
    'Workflow Execution 的独立权威状态、checkpoint、lease 与稳定 cancel intent；不兼任 façade Run';
COMMENT ON TABLE public.agent_runtime_execution_mutations IS
    'Execution create/release/domain mutation 的 append-only 原命令与精确输出 journal';
COMMENT ON TABLE public.agent_runtime_execution_state_publications IS
    'Execution 领域自有的 Session state event durable outbox；按 execution revision 严格顺序投影';
COMMENT ON TABLE public.agent_runtime_execution_publication_mutations IS
    'Execution publication ACK/retry 的 append-only fenced operation journal';

COMMENT ON COLUMN public.agent_runtime_executions.tenant_id IS 'Execution 租户与 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_executions.execution_id IS '租户内稳定 Workflow Execution 标识';
COMMENT ON COLUMN public.agent_runtime_executions.session_id IS '冻结 Run owner 的 Session 标识';
COMMENT ON COLUMN public.agent_runtime_executions.agent_id IS '冻结 Run owner 的 Agent 标识';
COMMENT ON COLUMN public.agent_runtime_executions.end_user_id IS '冻结 Run owner 的终端用户标识';
COMMENT ON COLUMN public.agent_runtime_executions.run_id IS '只读复合外键核验的 façade Run 标识';
COMMENT ON COLUMN public.agent_runtime_executions.causation_input_id IS 'RunIdentity 冻结的因果 Input 标识';
COMMENT ON COLUMN public.agent_runtime_executions.execution_unit_id IS '不可变 Workflow execution unit 标识';
COMMENT ON COLUMN public.agent_runtime_executions.definition_version IS '不可变 Workflow 定义版本';
COMMENT ON COLUMN public.agent_runtime_executions.execution_mode IS 'FOREGROUND 或 BACKGROUND 执行模式';
COMMENT ON COLUMN public.agent_runtime_executions.causative_action_id IS '可选原始 Action 引用的租户内标识';
COMMENT ON COLUMN public.agent_runtime_executions.delegation_id IS '可选原始 Delegation 引用的租户内标识';
COMMENT ON COLUMN public.agent_runtime_executions.state IS '封闭 WorkflowExecutionState 当前值';
COMMENT ON COLUMN public.agent_runtime_executions.execution_revision IS '领域写入单调 revision；lease 写入不递增';
COMMENT ON COLUMN public.agent_runtime_executions.checkpoint_schema_id IS '当前 checkpoint 的注册 schema 标识';
COMMENT ON COLUMN public.agent_runtime_executions.checkpoint_canonical_json IS '当前 checkpoint 的规范 JSON';
COMMENT ON COLUMN public.agent_runtime_executions.waiting_suspend_ref IS 'WAITING_HITL 唯一 suspend 身份';
COMMENT ON COLUMN public.agent_runtime_executions.waiting_action_key IS 'WAITING_HITL 唯一 action 路由键';
COMMENT ON COLUMN public.agent_runtime_executions.result_ref IS '等待态或终态要求的稳定结果引用';
COMMENT ON COLUMN public.agent_runtime_executions.reason_code IS 'FAILED/LOOP_TERMINATED 要求的稳定原因码';
COMMENT ON COLUMN public.agent_runtime_executions.lease_claim_id IS '当前 worker lease 的稳定 claim 标识';
COMMENT ON COLUMN public.agent_runtime_executions.lease_worker_id IS '当前 worker lease 持有者标识';
COMMENT ON COLUMN public.agent_runtime_executions.lease_fencing_token IS '当前 worker lease 的单调 fencing token';
COMMENT ON COLUMN public.agent_runtime_executions.lease_execution_revision IS '当前 worker lease 领取或 takeover 时冻结的 Execution revision；所有后续 lease 校验必须精确匹配';
COMMENT ON COLUMN public.agent_runtime_executions.lease_until_epoch_second IS '存储时钟 lease 到期秒';
COMMENT ON COLUMN public.agent_runtime_executions.lease_until_nano IS '存储时钟 lease 到期纳秒';
COMMENT ON COLUMN public.agent_runtime_executions.last_requested_epoch_second IS '最近一次 acquire/takeover 调用方审计时间秒';
COMMENT ON COLUMN public.agent_runtime_executions.last_requested_nano IS '最近一次 acquire/takeover 调用方审计时间纳秒';
COMMENT ON COLUMN public.agent_runtime_executions.cancel_request_id IS '与 worker lease 正交、首次写入后不可变的稳定 cancel intent 标识';
COMMENT ON COLUMN public.agent_runtime_executions.cancel_requested_epoch_second IS 'cancel intent 数据库提交时间秒';
COMMENT ON COLUMN public.agent_runtime_executions.cancel_requested_nano IS 'cancel intent 数据库提交时间纳秒';
COMMENT ON COLUMN public.agent_runtime_executions.record_data IS '完整 ExecutionRecord 的 canonical 私有 codec';
COMMENT ON COLUMN public.agent_runtime_executions.created_epoch_second IS 'Execution 数据库创建时间秒';
COMMENT ON COLUMN public.agent_runtime_executions.created_nano IS 'Execution 数据库创建时间纳秒';
COMMENT ON COLUMN public.agent_runtime_executions.updated_epoch_second IS '最近一次状态或 lease 修改的数据库时间秒';
COMMENT ON COLUMN public.agent_runtime_executions.updated_nano IS '最近一次状态或 lease 修改的数据库时间纳秒';

COMMENT ON COLUMN public.agent_runtime_execution_mutations.tenant_id IS 'Execution mutation 的租户与 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.execution_id IS 'mutation 所属 Workflow Execution 标识';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.session_id IS '冻结并反校验的 Session owner 标识';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.agent_id IS '冻结并反校验的 Agent owner 标识';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.end_user_id IS '冻结并反校验的终端用户 owner 标识';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.run_id IS '冻结并反校验的 façade Run 标识';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.mutation_key IS '资源内 operation identity 的 domain-separated SHA-256 key';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.mutation_kind IS 'CREATE/RELEASE/CANCEL/PROGRESS/TRANSITION/RESUME';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.expected_revision IS '原命令 expected revision；CREATE 固定 0';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.expected_state IS '原命令 expected state；CREATE/RELEASE 为空';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.command_fingerprint IS '完整原命令 canonical codec 的 lowercase SHA-256';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.command_data IS '完整原命令 canonical 私有 codec';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.output_revision IS '首次提交输出 Execution revision';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.output_state IS '首次提交输出 Execution state';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.output_fingerprint IS '首次输出 ExecutionRecord 的 lowercase SHA-256';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.output_data IS '首次输出 ExecutionRecord canonical codec';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.intermediate_revision IS 'RESUME 的 RESUMING 中间 revision；其他 operation 为空';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.intermediate_state IS 'RESUME 固定 RESUMING；其他 operation 为空';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.intermediate_fingerprint IS 'RESUME 中间 ExecutionRecord 指纹';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.intermediate_data IS 'RESUME 中间 ExecutionRecord canonical codec';
COMMENT ON COLUMN public.agent_runtime_execution_mutations.created_at IS '首次持久化 immutable mutation journal 的数据库时间';

COMMENT ON COLUMN public.agent_runtime_execution_state_publications.tenant_id IS 'Execution publication 的租户与 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.source_kind IS '固定 execution 领域来源';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.source_id IS 'publication 所属 Workflow Execution 标识';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.event_id IS 'Execution 内唯一 Session event 标识';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.source_revision IS '对应唯一 Execution 领域 revision';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.publication_revision IS 'claim/ACK/retry 每次递增的 publication CAS revision';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.available_epoch_second IS '待领取或 retry 后可用数据库时间秒';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.available_nano IS '待领取或 retry 后可用数据库时间纳秒';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.status IS 'PENDING/CLAIMED/ACKNOWLEDGED publication 状态';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.attempts IS '成功 claim/takeover 次数';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.lease_claim_id IS '当前 projector claim 标识；仅 CLAIMED 存在';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.lease_worker_id IS '当前 projector worker 标识；仅 CLAIMED 存在';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.lease_fencing_token IS '当前 projector claim 的单调 fencing token';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.lease_until_epoch_second IS 'projector claim 数据库到期时间秒';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.lease_until_nano IS 'projector claim 数据库到期时间纳秒';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.projection_ref IS 'ACK 后持久 Session 投影引用';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.failure_data IS '最近 retry 的稳定 PortFailure 私有 codec';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.publication_data IS '完整 StateEventPublication canonical codec';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.created_epoch_second IS 'publication 首次持久化数据库时间秒';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.created_nano IS 'publication 首次持久化数据库时间纳秒';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.updated_epoch_second IS 'publication 最近一次 claim/ACK/retry 数据库时间秒';
COMMENT ON COLUMN public.agent_runtime_execution_state_publications.updated_nano IS 'publication 最近一次 claim/ACK/retry 数据库时间纳秒';

COMMENT ON COLUMN public.agent_runtime_execution_publication_mutations.tenant_id IS 'publication mutation 的租户与 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_execution_publication_mutations.source_id IS 'mutation 所属 Workflow Execution 标识';
COMMENT ON COLUMN public.agent_runtime_execution_publication_mutations.event_id IS 'mutation 所属 Session event 标识';
COMMENT ON COLUMN public.agent_runtime_execution_publication_mutations.mutation_key IS 'ACK/retry 的 fence 与 expected revision operation identity';
COMMENT ON COLUMN public.agent_runtime_execution_publication_mutations.mutation_kind IS 'ACKNOWLEDGE 或 RETRY';
COMMENT ON COLUMN public.agent_runtime_execution_publication_mutations.command_fingerprint IS '完整 ACK/retry 命令 canonical codec 指纹';
COMMENT ON COLUMN public.agent_runtime_execution_publication_mutations.command_data IS '完整 ACK/retry 命令 canonical 私有 codec';
COMMENT ON COLUMN public.agent_runtime_execution_publication_mutations.output_revision IS '首次 mutation 后 publication revision';
COMMENT ON COLUMN public.agent_runtime_execution_publication_mutations.output_status IS '首次 mutation 后 PENDING 或 ACKNOWLEDGED';
COMMENT ON COLUMN public.agent_runtime_execution_publication_mutations.output_fingerprint IS '含存储状态的完整 publication 输出指纹';
COMMENT ON COLUMN public.agent_runtime_execution_publication_mutations.output_data IS '首次输出 StateEventPublication canonical codec';
COMMENT ON COLUMN public.agent_runtime_execution_publication_mutations.created_at IS '首次持久化 immutable ACK/retry journal 的数据库时间';
