-- V99: Action 独立权威账本、attempt 证据、一次性许可、resolved index 与领域自有 durable outbox。
-- 本迁移不引用、迁移或双写旧账本；所有事实只在以下八张新表内闭合。

CREATE SEQUENCE public.agent_runtime_action_fencing_seq
    AS bigint START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE TABLE public.agent_runtime_actions (
    tenant_id                         text NOT NULL,
    action_id                         varchar(160) NOT NULL,
    session_id                        text NOT NULL,
    agent_id                          text NOT NULL,
    end_user_id                       bigint NOT NULL,
    run_id                            varchar(160) NOT NULL,
    causation_input_id                varchar(160) NOT NULL,
    causation_identity_ref            text NOT NULL,
    execution_id                      varchar(160),
    delegation_id                     varchar(160),
    action_kind                       text NOT NULL,
    execute_call_kind                 varchar(32) NOT NULL,
    execute_target_handle             text NOT NULL,
    execute_request_fingerprint       varchar(64) NOT NULL,
    execute_invocation_snapshot_ref   text NOT NULL,
    execute_call_data                 text NOT NULL,
    reconcile_call_kind               varchar(32),
    reconcile_target_handle           text,
    reconcile_request_fingerprint     varchar(64),
    reconcile_invocation_snapshot_ref text,
    reconcile_call_data               text,
    idempotency_key                   text NOT NULL,
    retry_mode                        varchar(48) NOT NULL,
    invocation_id                     text NOT NULL,
    state                             varchar(24) NOT NULL,
    "authorization"                     varchar(24) NOT NULL,
    action_revision                   bigint NOT NULL,
    attempt                           integer NOT NULL,
    attempt_id                        text NOT NULL,
    attempt_call_kind                 varchar(32) NOT NULL,
    attempt_target_handle             text NOT NULL,
    attempt_request_fingerprint       varchar(64) NOT NULL,
    attempt_invocation_snapshot_ref   text NOT NULL,
    attempt_call_data                 text NOT NULL,
    reconciles_execute_attempt_id     text,
    canonical_observation_data        text,
    outcome_fact_ref                  text,
    reason_code                       text,
    lease_claim_id                    text,
    lease_worker_id                   text,
    lease_fencing_token               bigint,
    lease_action_revision             bigint,
    lease_until_epoch_second          bigint,
    lease_until_nano                  integer,
    initial_claim_data                text NOT NULL,
    record_data                       text NOT NULL,
    created_epoch_second              bigint NOT NULL,
    created_nano                      integer NOT NULL,
    updated_epoch_second              bigint NOT NULL,
    updated_nano                      integer NOT NULL,
    CONSTRAINT agent_runtime_actions_pkey
        PRIMARY KEY (tenant_id, action_id),
    CONSTRAINT uq_agent_runtime_action_idempotency
        UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_agent_runtime_action_invocation
        UNIQUE (tenant_id, action_id, invocation_id),
    CONSTRAINT ck_agent_runtime_action_owner CHECK (
        btrim(tenant_id) <> '' AND btrim(action_id) <> ''
        AND btrim(session_id) <> '' AND btrim(agent_id) <> ''
        AND end_user_id > 0 AND btrim(run_id) <> ''
        AND btrim(causation_input_id) <> ''
        AND btrim(causation_identity_ref) <> ''
        AND (execution_id IS NULL OR btrim(execution_id) <> '')
        AND (delegation_id IS NULL OR btrim(delegation_id) <> '')),
    CONSTRAINT ck_agent_runtime_action_identity CHECK (
        btrim(action_kind) <> '' AND btrim(idempotency_key) <> ''
        AND btrim(invocation_id) <> '' AND btrim(attempt_id) <> ''
        AND attempt_id <> invocation_id
        AND action_revision > 0 AND attempt > 0),
    CONSTRAINT ck_agent_runtime_action_state CHECK (
        state IN ('CLAIMED', 'SUCCEEDED', 'FAILED', 'OUTCOME_UNKNOWN')
        AND "authorization" IN
            ('EXECUTE', 'RECONCILE', 'FINALIZE_KNOWN', 'FINALIZE_UNKNOWN')),
    CONSTRAINT ck_agent_runtime_action_execute_call CHECK (
        execute_call_kind IN
            ('MODEL_GENERATE', 'MODEL_STREAM', 'TOOL_INVOKE',
             'WORKFLOW_DISPATCH', 'WORKFLOW_RESUME', 'WORKFLOW_CANCEL',
             'KNOWLEDGE_EXECUTE')
        AND btrim(execute_target_handle) <> ''
        AND execute_request_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(execute_invocation_snapshot_ref) <> ''
        AND btrim(execute_call_data) <> ''),
    CONSTRAINT ck_agent_runtime_action_reconcile_call CHECK (
        (retry_mode = 'RESULT_QUERYABLE'
         AND reconcile_call_kind = 'WORKFLOW_RECOVER_RESULT'
         AND reconcile_target_handle IS NOT NULL
         AND reconcile_request_fingerprint IS NOT NULL
         AND reconcile_invocation_snapshot_ref IS NOT NULL
         AND reconcile_call_data IS NOT NULL
         AND btrim(reconcile_target_handle) <> ''
         AND reconcile_request_fingerprint ~ '^[0-9a-f]{64}$'
         AND btrim(reconcile_invocation_snapshot_ref) <> ''
         AND btrim(reconcile_call_data) <> '')
        OR (retry_mode <> 'RESULT_QUERYABLE'
            AND reconcile_call_kind IS NULL
            AND reconcile_target_handle IS NULL
            AND reconcile_request_fingerprint IS NULL
            AND reconcile_invocation_snapshot_ref IS NULL
            AND reconcile_call_data IS NULL)),
    CONSTRAINT ck_agent_runtime_action_retry_contract CHECK (
        (execute_call_kind IN ('MODEL_GENERATE', 'MODEL_STREAM',
                               'KNOWLEDGE_EXECUTE')
         AND retry_mode = 'NO_AUTOMATIC_RETRY')
        OR (execute_call_kind = 'TOOL_INVOKE'
            AND retry_mode IN
                ('NO_AUTOMATIC_RETRY',
                 'PROVIDER_IDEMPOTENT_BY_ACTION_ID'))
        OR (execute_call_kind IN
                ('WORKFLOW_DISPATCH', 'WORKFLOW_RESUME', 'WORKFLOW_CANCEL')
            AND retry_mode IN
                ('NO_AUTOMATIC_RETRY', 'RESULT_QUERYABLE'))),
    CONSTRAINT ck_agent_runtime_action_attempt_call CHECK (
        attempt_call_kind IN
            ('MODEL_GENERATE', 'MODEL_STREAM', 'TOOL_INVOKE',
             'WORKFLOW_DISPATCH', 'WORKFLOW_RESUME',
             'WORKFLOW_RECOVER_RESULT', 'WORKFLOW_CANCEL',
             'KNOWLEDGE_EXECUTE')
        AND btrim(attempt_target_handle) <> ''
        AND attempt_request_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(attempt_invocation_snapshot_ref) <> ''
        AND btrim(attempt_call_data) <> ''
        AND (("authorization" = 'EXECUTE'
              AND reconciles_execute_attempt_id IS NULL
              AND (attempt_call_kind, attempt_target_handle,
                   attempt_request_fingerprint,
                   attempt_invocation_snapshot_ref, attempt_call_data)
                  = (execute_call_kind, execute_target_handle,
                     execute_request_fingerprint,
                     execute_invocation_snapshot_ref, execute_call_data))
             OR ("authorization" = 'RECONCILE'
                 AND attempt_call_kind = 'WORKFLOW_RECOVER_RESULT'
                 AND reconciles_execute_attempt_id IS NOT NULL
                 AND btrim(reconciles_execute_attempt_id) <> ''
                 AND attempt_target_handle = reconcile_target_handle)
             OR ("authorization" IN ('FINALIZE_KNOWN', 'FINALIZE_UNKNOWN')
                 AND ((attempt_call_kind = 'WORKFLOW_RECOVER_RESULT'
                       AND reconciles_execute_attempt_id IS NOT NULL
                       AND btrim(reconciles_execute_attempt_id) <> '')
                      OR (attempt_call_kind <> 'WORKFLOW_RECOVER_RESULT'
                          AND reconciles_execute_attempt_id IS NULL))))),
    CONSTRAINT ck_agent_runtime_action_terminal_fields CHECK (
        (state = 'CLAIMED'
         AND canonical_observation_data IS NULL
         AND outcome_fact_ref IS NULL AND reason_code IS NULL
         AND lease_claim_id IS NOT NULL AND lease_worker_id IS NOT NULL
         AND lease_fencing_token IS NOT NULL
         AND lease_action_revision = action_revision
         AND lease_until_epoch_second IS NOT NULL
         AND lease_until_nano IS NOT NULL
         AND btrim(lease_claim_id) <> '' AND btrim(lease_worker_id) <> ''
         AND lease_fencing_token > 0
         AND lease_until_nano BETWEEN 0 AND 999999999)
        OR (state = 'SUCCEEDED'
            AND outcome_fact_ref IS NOT NULL
            AND btrim(outcome_fact_ref) <> '' AND reason_code IS NULL
            AND lease_claim_id IS NULL AND lease_worker_id IS NULL
            AND lease_fencing_token IS NULL
            AND lease_action_revision IS NULL
            AND lease_until_epoch_second IS NULL AND lease_until_nano IS NULL)
        OR (state IN ('FAILED', 'OUTCOME_UNKNOWN')
            AND canonical_observation_data IS NULL
            AND outcome_fact_ref IS NOT NULL AND reason_code IS NOT NULL
            AND btrim(outcome_fact_ref) <> '' AND btrim(reason_code) <> ''
            AND lease_claim_id IS NULL AND lease_worker_id IS NULL
            AND lease_fencing_token IS NULL
            AND lease_action_revision IS NULL
            AND lease_until_epoch_second IS NULL AND lease_until_nano IS NULL)),
    CONSTRAINT ck_agent_runtime_action_codec CHECK (
        btrim(initial_claim_data) <> '' AND btrim(record_data) <> ''
        AND (canonical_observation_data IS NULL
             OR btrim(canonical_observation_data) <> '')),
    CONSTRAINT ck_agent_runtime_action_times CHECK (
        created_nano BETWEEN 0 AND 999999999
        AND updated_nano BETWEEN 0 AND 999999999
        AND (updated_epoch_second, updated_nano)
            >= (created_epoch_second, created_nano))
);

CREATE INDEX idx_agent_runtime_action_recovery
    ON public.agent_runtime_actions
        (tenant_id, state, lease_until_epoch_second, lease_until_nano,
         updated_epoch_second, updated_nano, action_id)
    WHERE state = 'CLAIMED';

CREATE TABLE public.agent_runtime_action_attempt_facts (
    tenant_id                       text NOT NULL,
    action_id                       varchar(160) NOT NULL,
    invocation_id                   text NOT NULL,
    attempt_id                      text NOT NULL,
    start_operation_id              text NOT NULL,
    evidence_revision               bigint NOT NULL,
    reconciles_execute_attempt_id   text,
    "authorization"                   varchar(24) NOT NULL,
    call_kind                       varchar(32) NOT NULL,
    status                          varchar(24) NOT NULL,
    outcome_resolution              varchar(24),
    outcome_type                    text,
    outcome_schema_id               text,
    outcome_canonical_json          text,
    resolution_key                  text,
    fact_ref                        text NOT NULL,
    fact_data                       text NOT NULL,
    started_epoch_second            bigint NOT NULL,
    started_nano                    integer NOT NULL,
    updated_epoch_second            bigint NOT NULL,
    updated_nano                    integer NOT NULL,
    CONSTRAINT agent_runtime_action_attempt_facts_pkey
        PRIMARY KEY (tenant_id, action_id, attempt_id, evidence_revision),
    CONSTRAINT uq_agent_runtime_action_attempt_fact_ref
        UNIQUE (tenant_id, fact_ref),
    CONSTRAINT uq_agent_runtime_action_attempt_fact_identity
        UNIQUE (tenant_id, action_id, invocation_id, fact_ref),
    CONSTRAINT uq_agent_runtime_action_attempt_fact_attempt_ref
        UNIQUE (tenant_id, action_id, attempt_id, fact_ref),
    CONSTRAINT agent_runtime_action_attempt_fact_action_fkey
        FOREIGN KEY (tenant_id, action_id, invocation_id)
        REFERENCES public.agent_runtime_actions
            (tenant_id, action_id, invocation_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_action_attempt_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(action_id) <> ''
        AND btrim(invocation_id) <> '' AND btrim(attempt_id) <> ''
        AND attempt_id <> invocation_id
        AND btrim(start_operation_id) <> ''
        AND evidence_revision > 0
        AND btrim(fact_ref) <> '' AND btrim(fact_data) <> ''),
    CONSTRAINT ck_agent_runtime_action_attempt_authorization CHECK (
        call_kind IN
            ('MODEL_GENERATE', 'MODEL_STREAM', 'TOOL_INVOKE',
             'WORKFLOW_DISPATCH', 'WORKFLOW_RESUME',
             'WORKFLOW_RECOVER_RESULT', 'WORKFLOW_CANCEL',
             'KNOWLEDGE_EXECUTE')
        AND (("authorization" = 'EXECUTE'
         AND reconciles_execute_attempt_id IS NULL
         AND call_kind <> 'WORKFLOW_RECOVER_RESULT')
        OR ("authorization" = 'RECONCILE'
            AND reconciles_execute_attempt_id IS NOT NULL
            AND btrim(reconciles_execute_attempt_id) <> ''
            AND call_kind = 'WORKFLOW_RECOVER_RESULT'))),
    CONSTRAINT ck_agent_runtime_action_attempt_outcome CHECK (
        status IN
            ('STARTED', 'NOT_DISPATCHED', 'SUCCEEDED', 'FAILED', 'UNRESOLVED')
        AND ((status IN ('STARTED', 'NOT_DISPATCHED')
              AND outcome_resolution IS NULL AND outcome_type IS NULL
              AND outcome_schema_id IS NULL
              AND outcome_canonical_json IS NULL
              AND resolution_key IS NULL)
             OR (status = 'SUCCEEDED'
                 AND outcome_resolution = 'KNOWN_RESULT'
                 AND outcome_type IS NOT NULL AND btrim(outcome_type) <> ''
                 AND outcome_schema_id IS NOT NULL
                 AND btrim(outcome_schema_id) <> ''
                 AND outcome_canonical_json IS NOT NULL
                 AND btrim(outcome_canonical_json) <> '')
             OR (status = 'FAILED'
                 AND outcome_resolution = 'KNOWN_FAILURE'
                 AND outcome_type IS NOT NULL AND btrim(outcome_type) <> ''
                 AND outcome_schema_id IS NOT NULL
                 AND btrim(outcome_schema_id) <> ''
                 AND outcome_canonical_json IS NOT NULL
                 AND btrim(outcome_canonical_json) <> '')
             OR (status = 'UNRESOLVED'
                 AND outcome_resolution = 'OUTCOME_UNKNOWN'
                 AND outcome_type IS NOT NULL AND btrim(outcome_type) <> ''
                 AND outcome_schema_id IS NOT NULL
                 AND btrim(outcome_schema_id) <> ''
                 AND outcome_canonical_json IS NOT NULL
                 AND btrim(outcome_canonical_json) <> ''
                 AND resolution_key IS NULL))
        AND (resolution_key IS NULL OR btrim(resolution_key) <> '')),
    CONSTRAINT ck_agent_runtime_action_attempt_times CHECK (
        started_nano BETWEEN 0 AND 999999999
        AND updated_nano BETWEEN 0 AND 999999999
        AND (updated_epoch_second, updated_nano)
            >= (started_epoch_second, started_nano))
);

CREATE INDEX idx_agent_runtime_action_attempt_latest
    ON public.agent_runtime_action_attempt_facts
        (tenant_id, action_id, attempt_id, evidence_revision DESC);

CREATE INDEX idx_agent_runtime_action_attempt_resolution
    ON public.agent_runtime_action_attempt_facts
        (tenant_id, action_id, invocation_id, resolution_key)
    WHERE resolution_key IS NOT NULL;

ALTER TABLE public.agent_runtime_actions
    ADD CONSTRAINT agent_runtime_action_outcome_fact_fkey
    FOREIGN KEY (tenant_id, action_id, invocation_id, outcome_fact_ref)
    REFERENCES public.agent_runtime_action_attempt_facts
        (tenant_id, action_id, invocation_id, fact_ref)
    ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE public.nw_runtime_action_resolutions (
    tenant_id                    text NOT NULL,
    action_id                    varchar(160) NOT NULL,
    invocation_id                text NOT NULL,
    winner_attempt_id            text,
    winner_evidence_revision     bigint,
    resolved_epoch_second        bigint,
    resolved_nano                integer,
    winner_fact_ref              text,
    winner_fact_data             text,
    resolution_key               text,
    created_epoch_second         bigint NOT NULL,
    created_nano                 integer NOT NULL,
    updated_epoch_second         bigint NOT NULL,
    updated_nano                 integer NOT NULL,
    CONSTRAINT skillforge_runtime_action_resolutions_pkey
        PRIMARY KEY (tenant_id, action_id, invocation_id),
    CONSTRAINT agent_runtime_action_resolution_action_fkey
        FOREIGN KEY (tenant_id, action_id, invocation_id)
        REFERENCES public.agent_runtime_actions
            (tenant_id, action_id, invocation_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT agent_runtime_action_resolution_winner_fkey
        FOREIGN KEY (tenant_id, action_id, invocation_id, winner_fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts
            (tenant_id, action_id, invocation_id, fact_ref)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_action_resolution_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(action_id) <> ''
        AND btrim(invocation_id) <> ''
        AND created_nano BETWEEN 0 AND 999999999
        AND updated_nano BETWEEN 0 AND 999999999
        AND (updated_epoch_second, updated_nano)
            >= (created_epoch_second, created_nano)),
    CONSTRAINT ck_agent_runtime_action_resolution_winner CHECK (
        (winner_fact_ref IS NULL AND winner_fact_data IS NULL
         AND winner_attempt_id IS NULL AND winner_evidence_revision IS NULL
         AND resolved_epoch_second IS NULL AND resolved_nano IS NULL
         AND resolution_key IS NULL
         AND updated_epoch_second = created_epoch_second
         AND updated_nano = created_nano)
        OR (winner_fact_ref IS NOT NULL AND winner_fact_data IS NOT NULL
            AND winner_attempt_id IS NOT NULL AND btrim(winner_attempt_id) <> ''
            AND winner_evidence_revision IS NOT NULL AND winner_evidence_revision > 0
            AND resolved_epoch_second = updated_epoch_second
            AND resolved_nano = updated_nano
            AND resolution_key IS NOT NULL
            AND btrim(winner_fact_ref) <> ''
            AND btrim(winner_fact_data) <> ''
            AND btrim(resolution_key) <> ''))
);

CREATE TABLE public.nw_runtime_action_permit_consumptions (
    tenant_id                    text NOT NULL,
    action_id                    varchar(160) NOT NULL,
    invocation_id                text NOT NULL,
    attempt_id                   text NOT NULL,
    start_operation_id           text NOT NULL,
    bearer_fingerprint           varchar(64) NOT NULL,
    status                       varchar(16) NOT NULL,
    consume_operation_id         text,
    intent_data                  text NOT NULL,
    started_fact_ref             text NOT NULL,
    started_fact_data            text NOT NULL,
    issued_epoch_second          bigint NOT NULL,
    issued_nano                  integer NOT NULL,
    consumed_epoch_second        bigint,
    consumed_nano                integer,
    updated_epoch_second         bigint NOT NULL,
    updated_nano                 integer NOT NULL,
    CONSTRAINT skillforge_runtime_action_permit_consumptions_pkey
        PRIMARY KEY (tenant_id, action_id, attempt_id),
    CONSTRAINT uq_agent_runtime_action_permit_bearer
        UNIQUE (tenant_id, bearer_fingerprint),
    CONSTRAINT agent_runtime_action_permit_action_fkey
        FOREIGN KEY (tenant_id, action_id, invocation_id)
        REFERENCES public.agent_runtime_actions
            (tenant_id, action_id, invocation_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_action_permit_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(action_id) <> ''
        AND btrim(invocation_id) <> '' AND btrim(attempt_id) <> ''
        AND btrim(start_operation_id) <> ''
        AND bearer_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(intent_data) <> '' AND btrim(started_fact_data) <> ''),
    CONSTRAINT ck_agent_runtime_action_permit_status CHECK (
        (status = 'ISSUED'
         AND consume_operation_id IS NULL
         AND consumed_epoch_second IS NULL AND consumed_nano IS NULL)
        OR (status = 'CONSUMED'
            AND consume_operation_id IS NOT NULL
            AND btrim(consume_operation_id) <> ''
            AND consumed_epoch_second IS NOT NULL AND consumed_nano IS NOT NULL
            AND consumed_nano BETWEEN 0 AND 999999999
            AND (consumed_epoch_second, consumed_nano)
                >= (issued_epoch_second, issued_nano))),
    CONSTRAINT ck_agent_runtime_action_permit_issued_time CHECK (
        issued_nano BETWEEN 0 AND 999999999
        AND updated_nano BETWEEN 0 AND 999999999
        AND (updated_epoch_second, updated_nano)
            >= (issued_epoch_second, issued_nano)
        AND ((status = 'ISSUED'
              AND updated_epoch_second = issued_epoch_second
              AND updated_nano = issued_nano)
             OR status = 'CONSUMED'))
);

CREATE UNIQUE INDEX uq_agent_runtime_action_consume_operation
    ON public.nw_runtime_action_permit_consumptions
        (tenant_id, consume_operation_id)
    WHERE consume_operation_id IS NOT NULL;

CREATE TABLE public.nw_runtime_action_operations (
    tenant_id                      text NOT NULL,
    operation_key                  text NOT NULL,
    original_operation_id          text NOT NULL,
    operation_kind                 varchar(24) NOT NULL,
    action_id                      varchar(160) NOT NULL,
    invocation_id                  text NOT NULL,
    attempt_id                     text,
    expected_action_revision       bigint,
    response_fact_ref              text,
    writes_resolution_slot         boolean NOT NULL DEFAULT false,
    writes_permit_consumption      boolean NOT NULL DEFAULT false,
    command_type                   text NOT NULL,
    command_fingerprint            varchar(64) NOT NULL,
    command_data                   text NOT NULL,
    response_kind                  varchar(32) NOT NULL,
    response_fingerprint           varchar(64) NOT NULL,
    response_data                  text NOT NULL,
    output_action_revision         bigint,
    output_action_state            varchar(24),
    output_authorization           varchar(24),
    output_action_fingerprint      varchar(64),
    output_action_data             text,
    written_fact_ref               text,
    written_fact_fingerprint       varchar(64),
    written_fact_data              text,
    latest_fact_ref                text,
    latest_fact_fingerprint        varchar(64),
    latest_fact_data               text,
    winner_fact_ref                text,
    winner_fact_fingerprint        varchar(64),
    winner_fact_data               text,
    audit_ref                      text,
    publication_event_id           varchar(160),
    publication_source_revision    bigint,
    publication_fingerprint        varchar(64),
    publication_data               text,
    writes_action                  boolean NOT NULL DEFAULT false,
    writes_fact                    boolean NOT NULL DEFAULT false,
    writes_resolution              boolean NOT NULL DEFAULT false,
    writes_permit                  boolean NOT NULL DEFAULT false,
    writes_audit                   boolean NOT NULL DEFAULT false,
    writes_publication             boolean NOT NULL DEFAULT false,
    created_epoch_second           bigint NOT NULL,
    created_nano                   integer NOT NULL,
    CONSTRAINT skillforge_runtime_action_operations_pkey
        PRIMARY KEY (tenant_id, operation_key),
    CONSTRAINT agent_runtime_action_operation_written_fact_fkey
        FOREIGN KEY (tenant_id, written_fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts
            (tenant_id, fact_ref)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT agent_runtime_action_operation_latest_fact_fkey
        FOREIGN KEY (tenant_id, latest_fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts
            (tenant_id, fact_ref)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT agent_runtime_action_operation_winner_fact_fkey
        FOREIGN KEY (tenant_id, winner_fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts
            (tenant_id, fact_ref)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_action_operation_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(operation_key) <> ''
        AND btrim(original_operation_id) <> ''
        AND btrim(action_id) <> '' AND btrim(invocation_id) <> ''
        AND (attempt_id IS NULL OR btrim(attempt_id) <> '')
        AND (operation_kind = 'CLAIM' OR attempt_id IS NOT NULL)),
    CONSTRAINT ck_agent_runtime_action_operation_kind CHECK (
        operation_kind IN
            ('CLAIM', 'RENEW', 'START', 'CONSUME', 'RESULT', 'RETRY',
             'COMPLETE', 'FAIL', 'OUTCOME_UNKNOWN')),
    CONSTRAINT ck_agent_runtime_action_operation_command CHECK (
        (operation_kind = 'CLAIM'
         AND command_type =
            'com.skillforge.runtime.api.port.ActionLedgerPort$ClaimAction')
        OR (operation_kind = 'RENEW'
            AND command_type =
            'com.skillforge.runtime.api.port.ActionLedgerPort$RenewAction')
        OR (operation_kind = 'START'
            AND command_type =
            'com.skillforge.runtime.api.port.ActionLedgerPort$ActionInvocationIntent')
        OR (operation_kind = 'CONSUME'
            AND command_type =
            'com.skillforge.runtime.adapter.jdbc.action.StoredConsumeCommand')
        OR (operation_kind = 'RESULT'
            AND command_type =
            'com.skillforge.runtime.adapter.jdbc.action.StoredResultCommand')
        OR (operation_kind = 'RETRY'
            AND command_type =
            'com.skillforge.runtime.api.port.ActionLedgerPort$RetryAction')
        OR (operation_kind = 'COMPLETE'
            AND command_type =
            'com.skillforge.runtime.api.port.ActionLedgerPort$CompleteAction')
        OR (operation_kind = 'FAIL'
            AND command_type =
            'com.skillforge.runtime.api.port.ActionLedgerPort$FailAction')
        OR (operation_kind = 'OUTCOME_UNKNOWN'
            AND command_type =
            'com.skillforge.runtime.api.port.ActionLedgerPort$OutcomeUnknownAction')),
    CONSTRAINT ck_agent_runtime_action_operation_codec CHECK (
        btrim(command_type) <> ''
        AND command_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(command_data) <> '' AND btrim(response_kind) <> ''
        AND response_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(response_data) <> ''
        AND ((output_action_revision IS NULL
              AND output_action_state IS NULL
              AND output_authorization IS NULL
              AND output_action_fingerprint IS NULL
              AND output_action_data IS NULL)
             OR (output_action_revision IS NOT NULL
                 AND output_action_revision > 0
                 AND output_action_state IN
                    ('CLAIMED', 'SUCCEEDED', 'FAILED', 'OUTCOME_UNKNOWN')
                 AND output_authorization IN
                    ('EXECUTE', 'RECONCILE',
                     'FINALIZE_KNOWN', 'FINALIZE_UNKNOWN')
                 AND output_action_fingerprint ~ '^[0-9a-f]{64}$'
                 AND output_action_data IS NOT NULL
                 AND btrim(output_action_data) <> ''))
        AND ((written_fact_ref IS NULL
              AND written_fact_fingerprint IS NULL
              AND written_fact_data IS NULL)
             OR (written_fact_ref IS NOT NULL
                 AND written_fact_fingerprint ~ '^[0-9a-f]{64}$'
                 AND written_fact_data IS NOT NULL
                 AND btrim(written_fact_ref) <> ''
                 AND btrim(written_fact_data) <> ''))
        AND ((latest_fact_ref IS NULL
              AND latest_fact_fingerprint IS NULL
              AND latest_fact_data IS NULL)
             OR (latest_fact_ref IS NOT NULL
                 AND latest_fact_fingerprint ~ '^[0-9a-f]{64}$'
                 AND latest_fact_data IS NOT NULL
                 AND btrim(latest_fact_ref) <> ''
                 AND btrim(latest_fact_data) <> ''))
        AND ((winner_fact_ref IS NULL
              AND winner_fact_fingerprint IS NULL
              AND winner_fact_data IS NULL)
             OR (winner_fact_ref IS NOT NULL
                 AND winner_fact_fingerprint ~ '^[0-9a-f]{64}$'
                 AND winner_fact_data IS NOT NULL
                 AND btrim(winner_fact_ref) <> ''
                 AND btrim(winner_fact_data) <> ''))
        AND (audit_ref IS NULL OR btrim(audit_ref) <> '')),
    CONSTRAINT ck_agent_runtime_action_operation_response CHECK (
        (operation_kind = 'CLAIM'
         AND response_kind IN
            ('EXECUTE', 'RECONCILE', 'FINALIZE_KNOWN',
             'FINALIZE_UNKNOWN', 'REPLAY', 'IN_PROGRESS',
             'CONFLICT', 'REJECTED'))
        OR (operation_kind = 'RENEW'
            AND response_kind IN
                ('RENEWED', 'REPLAYED', 'CONFLICT', 'LEASE_LOST', 'REJECTED'))
        OR (operation_kind = 'START'
            AND response_kind IN
                ('STARTED', 'IN_PROGRESS', 'REPLAYED',
                 'CONFLICT', 'LEASE_LOST', 'REJECTED'))
        OR (operation_kind = 'CONSUME'
            AND response_kind IN
                ('CONSUMED', 'ALREADY_CONSUMED', 'RESOLVED',
                 'CONFLICT', 'LEASE_LOST', 'REJECTED'))
        OR (operation_kind = 'RESULT'
            AND response_kind IN
                ('RECORDED', 'REPLAYED', 'CONFLICT', 'REJECTED'))
        OR (operation_kind = 'RETRY'
            AND response_kind IN
                ('EXECUTE', 'RECONCILE', 'FINALIZE_KNOWN',
                 'CONFLICT', 'LEASE_LOST', 'REJECTED'))
        OR (operation_kind IN ('COMPLETE', 'FAIL', 'OUTCOME_UNKNOWN')
            AND response_kind IN
                ('APPLIED', 'REPLAYED', 'RESOLVED',
                 'CONFLICT', 'LEASE_LOST', 'REJECTED'))),
    CONSTRAINT ck_agent_runtime_action_operation_outputs CHECK (
        (NOT writes_action
         OR (output_action_revision IS NOT NULL
             AND output_action_state IS NOT NULL
             AND output_authorization IS NOT NULL
             AND output_action_fingerprint IS NOT NULL
             AND output_action_data IS NOT NULL))
        AND (NOT writes_action OR operation_kind IN
            ('CLAIM', 'RENEW', 'RETRY', 'COMPLETE', 'FAIL',
             'OUTCOME_UNKNOWN'))
        AND (NOT (writes_action OR writes_fact OR writes_permit)
             OR attempt_id IS NOT NULL)
        AND (writes_fact = (written_fact_ref IS NOT NULL))
        AND (NOT writes_fact
             OR operation_kind IN ('CLAIM', 'START', 'RESULT'))
        AND (NOT writes_resolution_slot
             OR (operation_kind = 'CLAIM' AND writes_action
                 AND output_action_revision = 1 AND winner_fact_ref IS NULL))
        AND (NOT writes_resolution
             OR (operation_kind = 'RESULT' AND writes_fact
                 AND winner_fact_ref = written_fact_ref
                 AND winner_fact_fingerprint = written_fact_fingerprint
                 AND winner_fact_data = written_fact_data))
        AND (NOT writes_permit
             OR (operation_kind = 'START' AND response_kind = 'STARTED'
                 AND writes_fact))
        AND (NOT writes_permit_consumption
             OR (operation_kind = 'CONSUME' AND response_kind = 'CONSUMED'
                 AND response_fact_ref IS NOT NULL))
        AND (writes_audit = (audit_ref IS NOT NULL))
        AND (NOT writes_audit
             OR (operation_kind IN ('CLAIM', 'RESULT')
                 AND response_kind = 'CONFLICT'))
        AND ((publication_event_id IS NULL
              AND publication_source_revision IS NULL
              AND publication_fingerprint IS NULL
              AND publication_data IS NULL)
             OR (publication_event_id IS NOT NULL
                 AND btrim(publication_event_id) <> ''
                 AND publication_source_revision IS NOT NULL
                 AND publication_source_revision > 0
                 AND publication_fingerprint ~ '^[0-9a-f]{64}$'
                 AND publication_data IS NOT NULL
                 AND btrim(publication_data) <> ''))
        AND (writes_publication = (publication_event_id IS NOT NULL))
        AND (NOT writes_publication
             OR (writes_action
                 AND operation_kind IN
                    ('CLAIM', 'RETRY', 'COMPLETE', 'FAIL',
                     'OUTCOME_UNKNOWN')
                 AND publication_source_revision = output_action_revision))),
    CONSTRAINT ck_agent_runtime_action_operation_time CHECK (
        created_nano BETWEEN 0 AND 999999999)
);

CREATE UNIQUE INDEX uq_agent_runtime_action_consume_operation_journal
    ON public.nw_runtime_action_operations
        (tenant_id, original_operation_id)
    WHERE operation_kind = 'CONSUME';

CREATE TABLE public.nw_runtime_action_conflict_audits (
    tenant_id               text NOT NULL,
    audit_ref               text NOT NULL,
    action_id               varchar(160) NOT NULL,
    invocation_id           text NOT NULL,
    operation_key           text NOT NULL,
    audit_kind              varchar(40) NOT NULL,
    attempt_id              text,
    reason_code             text NOT NULL,
    winner_fact_ref         text,
    winner_fact_data        text,
    conflicting_fact_ref    text,
    conflicting_fact_data   text,
    audit_data              text NOT NULL,
    created_epoch_second    bigint NOT NULL,
    created_nano            integer NOT NULL,
    CONSTRAINT skillforge_runtime_action_conflict_audits_pkey
        PRIMARY KEY (tenant_id, audit_ref),
    CONSTRAINT uq_agent_runtime_action_conflict_operation
        UNIQUE (tenant_id, operation_key, audit_kind),
    CONSTRAINT agent_runtime_action_conflict_operation_fkey
        FOREIGN KEY (tenant_id, operation_key)
        REFERENCES public.nw_runtime_action_operations
            (tenant_id, operation_key)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT agent_runtime_action_conflict_winner_fkey
        FOREIGN KEY (
            tenant_id, action_id, invocation_id, winner_fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts
            (tenant_id, action_id, invocation_id, fact_ref)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT agent_runtime_action_conflict_later_fkey
        FOREIGN KEY (
            tenant_id, action_id, invocation_id, conflicting_fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts
            (tenant_id, action_id, invocation_id, fact_ref)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_action_conflict_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(audit_ref) <> ''
        AND btrim(action_id) <> '' AND btrim(invocation_id) <> ''
        AND btrim(operation_key) <> '' AND btrim(reason_code) <> ''
        AND btrim(audit_data) <> ''),
    CONSTRAINT ck_agent_runtime_action_conflict_kind CHECK (
        (audit_kind = 'CLAIM_CONFLICT'
         AND reason_code = 'action_claim_conflict'
         AND attempt_id IS NULL
         AND winner_fact_ref IS NULL AND winner_fact_data IS NULL
         AND conflicting_fact_ref IS NULL AND conflicting_fact_data IS NULL)
        OR (audit_kind = 'PROVIDER_OUTCOME_CONFLICT'
            AND reason_code = 'provider_outcome_conflict'
            AND attempt_id IS NOT NULL AND btrim(attempt_id) <> ''
            AND winner_fact_ref IS NOT NULL
            AND winner_fact_data IS NOT NULL
            AND conflicting_fact_ref IS NOT NULL
            AND conflicting_fact_data IS NOT NULL
            AND btrim(winner_fact_ref) <> ''
            AND btrim(winner_fact_data) <> ''
            AND btrim(conflicting_fact_ref) <> ''
            AND btrim(conflicting_fact_data) <> ''
            AND winner_fact_ref <> conflicting_fact_ref)),
    CONSTRAINT ck_agent_runtime_action_conflict_time CHECK (
        created_nano BETWEEN 0 AND 999999999)
);

CREATE TABLE public.agent_runtime_action_state_publications (
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
    publication_fingerprint      varchar(64) NOT NULL,
    publication_data             text NOT NULL,
    created_epoch_second         bigint NOT NULL,
    created_nano                 integer NOT NULL,
    updated_epoch_second         bigint NOT NULL,
    updated_nano                 integer NOT NULL,
    CONSTRAINT agent_runtime_action_state_publications_pkey
        PRIMARY KEY (tenant_id, source_id, event_id),
    CONSTRAINT uq_agent_runtime_action_state_publication_revision
        UNIQUE (tenant_id, source_id, source_revision),
    CONSTRAINT agent_runtime_action_publication_action_fkey
        FOREIGN KEY (tenant_id, source_id)
        REFERENCES public.agent_runtime_actions (tenant_id, action_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_action_publication_identity CHECK (
        btrim(tenant_id) <> '' AND source_kind = 'action'
        AND btrim(source_id) <> '' AND btrim(event_id) <> ''
        AND source_revision > 0 AND publication_revision > 0
        AND available_nano BETWEEN 0 AND 999999999
        AND attempts >= 0),
    CONSTRAINT ck_agent_runtime_action_publication_status CHECK (
        status IN ('PENDING', 'CLAIMED', 'ACKNOWLEDGED')),
    CONSTRAINT ck_agent_runtime_action_publication_lease CHECK (
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
    CONSTRAINT ck_agent_runtime_action_publication_projection CHECK (
        (status = 'ACKNOWLEDGED') = (projection_ref IS NOT NULL)
        AND (projection_ref IS NULL OR btrim(projection_ref) <> '')),
    CONSTRAINT ck_agent_runtime_action_publication_codec CHECK (
        publication_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(publication_data) <> ''),
    CONSTRAINT ck_agent_runtime_action_publication_times CHECK (
        created_nano BETWEEN 0 AND 999999999
        AND updated_nano BETWEEN 0 AND 999999999
        AND (updated_epoch_second, updated_nano)
            >= (created_epoch_second, created_nano))
);

CREATE INDEX idx_agent_runtime_action_publication_claim
    ON public.agent_runtime_action_state_publications
        (tenant_id, status, available_epoch_second, available_nano,
         lease_until_epoch_second, lease_until_nano, source_id, source_revision);

CREATE TABLE public.nw_runtime_action_publication_mutations (
    tenant_id            text NOT NULL,
    source_id            varchar(160) NOT NULL,
    event_id             varchar(160) NOT NULL,
    mutation_key         text NOT NULL,
    mutation_kind        varchar(16) NOT NULL,
    command_type         text NOT NULL,
    command_fingerprint  varchar(64) NOT NULL,
    command_data         text NOT NULL,
    output_revision      bigint NOT NULL,
    output_status        varchar(16) NOT NULL,
    output_fingerprint   varchar(64) NOT NULL,
    output_data          text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano         integer NOT NULL,
    CONSTRAINT skillforge_runtime_action_publication_mutations_pkey
        PRIMARY KEY (tenant_id, source_id, event_id, mutation_key),
    CONSTRAINT agent_runtime_action_publication_mutation_publication_fkey
        FOREIGN KEY (tenant_id, source_id, event_id)
        REFERENCES public.agent_runtime_action_state_publications
            (tenant_id, source_id, event_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_action_publication_mutation_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(source_id) <> ''
        AND btrim(event_id) <> '' AND btrim(mutation_key) <> ''),
    CONSTRAINT ck_agent_runtime_action_publication_mutation_kind CHECK (
        (mutation_kind = 'ACKNOWLEDGE'
         AND command_type =
            'com.skillforge.runtime.api.port.PublicationAcknowledge')
        OR (mutation_kind = 'RETRY'
            AND command_type =
            'com.skillforge.runtime.api.port.PublicationRetry')),
    CONSTRAINT ck_agent_runtime_action_publication_mutation_codec CHECK (
        command_fingerprint ~ '^[0-9a-f]{64}$'
        AND output_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(command_data) <> '' AND btrim(output_data) <> ''),
    CONSTRAINT ck_agent_runtime_action_publication_mutation_output CHECK (
        output_revision > 1
        AND ((mutation_kind = 'ACKNOWLEDGE'
              AND output_status = 'ACKNOWLEDGED')
             OR (mutation_kind = 'RETRY' AND output_status = 'PENDING'))),
    CONSTRAINT ck_agent_runtime_action_publication_mutation_time CHECK (
        created_nano BETWEEN 0 AND 999999999)
);

CREATE UNIQUE INDEX uq_agent_runtime_action_operation_written_fact
    ON public.nw_runtime_action_operations (tenant_id, written_fact_ref)
    WHERE written_fact_ref IS NOT NULL;

CREATE FUNCTION public.guard_agent_runtime_action()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime action is not deletable';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'CLAIMED' OR NEW."authorization" <> 'EXECUTE'
           OR NEW.action_revision <> 1 OR NEW.attempt <> 1
           OR NEW.reconciles_execute_attempt_id IS NOT NULL THEN
            RAISE EXCEPTION 'initial action must be revision-one EXECUTE claim';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id, NEW.action_id, NEW.session_id, NEW.agent_id,
        NEW.end_user_id, NEW.run_id, NEW.causation_input_id,
        NEW.causation_identity_ref, NEW.execution_id, NEW.delegation_id,
        NEW.action_kind, NEW.execute_call_kind, NEW.execute_target_handle,
        NEW.execute_request_fingerprint,
        NEW.execute_invocation_snapshot_ref, NEW.execute_call_data,
        NEW.reconcile_call_kind, NEW.reconcile_target_handle,
        NEW.reconcile_request_fingerprint,
        NEW.reconcile_invocation_snapshot_ref, NEW.reconcile_call_data,
        NEW.idempotency_key, NEW.retry_mode, NEW.invocation_id,
        NEW.initial_claim_data, NEW.created_epoch_second, NEW.created_nano)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.action_id, OLD.session_id, OLD.agent_id,
        OLD.end_user_id, OLD.run_id, OLD.causation_input_id,
        OLD.causation_identity_ref, OLD.execution_id, OLD.delegation_id,
        OLD.action_kind, OLD.execute_call_kind, OLD.execute_target_handle,
        OLD.execute_request_fingerprint,
        OLD.execute_invocation_snapshot_ref, OLD.execute_call_data,
        OLD.reconcile_call_kind, OLD.reconcile_target_handle,
        OLD.reconcile_request_fingerprint,
        OLD.reconcile_invocation_snapshot_ref, OLD.reconcile_call_data,
        OLD.idempotency_key, OLD.retry_mode, OLD.invocation_id,
        OLD.initial_claim_data, OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'agent runtime action immutable tuple changed';
    END IF;
    IF (NEW.updated_epoch_second, NEW.updated_nano)
       < (OLD.updated_epoch_second, OLD.updated_nano) THEN
        RAISE EXCEPTION 'agent runtime action time moved backwards';
    END IF;
    IF NEW.action_revision = OLD.action_revision THEN
        IF OLD.state <> 'CLAIMED' OR NEW.state <> 'CLAIMED'
           OR (NEW."authorization", NEW.attempt, NEW.attempt_id,
               NEW.attempt_call_kind, NEW.attempt_target_handle,
               NEW.attempt_request_fingerprint,
               NEW.attempt_invocation_snapshot_ref, NEW.attempt_call_data,
               NEW.reconciles_execute_attempt_id,
               NEW.canonical_observation_data, NEW.outcome_fact_ref,
               NEW.reason_code, NEW.lease_claim_id, NEW.lease_worker_id,
               NEW.lease_fencing_token, NEW.lease_action_revision)
              IS DISTINCT FROM
              (OLD."authorization", OLD.attempt, OLD.attempt_id,
               OLD.attempt_call_kind, OLD.attempt_target_handle,
               OLD.attempt_request_fingerprint,
               OLD.attempt_invocation_snapshot_ref, OLD.attempt_call_data,
               OLD.reconciles_execute_attempt_id,
               OLD.canonical_observation_data, OLD.outcome_fact_ref,
               OLD.reason_code, OLD.lease_claim_id, OLD.lease_worker_id,
               OLD.lease_fencing_token, OLD.lease_action_revision)
           OR (NEW.lease_until_epoch_second, NEW.lease_until_nano)
              <= (OLD.lease_until_epoch_second, OLD.lease_until_nano) THEN
            RAISE EXCEPTION 'same-revision action update must only extend current lease';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.action_revision <> OLD.action_revision + 1 THEN
        RAISE EXCEPTION 'agent runtime action revision must advance by one';
    END IF;
    IF OLD.state IN ('SUCCEEDED', 'FAILED') THEN
        RAISE EXCEPTION 'completed agent runtime action is immutable';
    END IF;
    IF OLD.state = 'OUTCOME_UNKNOWN' THEN
        IF NEW.state <> 'CLAIMED'
           OR NEW."authorization" <> 'FINALIZE_KNOWN'
           OR NEW.attempt <> OLD.attempt OR NEW.attempt_id <> OLD.attempt_id THEN
            RAISE EXCEPTION 'outcome-unknown action may only claim a known winner';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state <> 'CLAIMED' THEN
        RAISE EXCEPTION 'illegal agent runtime action source state';
    END IF;
    IF NEW.state = 'CLAIMED' THEN
        IF NEW.attempt NOT IN (OLD.attempt, OLD.attempt + 1) THEN
            RAISE EXCEPTION 'claimed action attempt must stay or advance by one';
        END IF;
        IF (NEW.attempt = OLD.attempt AND
            (NEW.attempt_id <> OLD.attempt_id
             OR NEW."authorization" NOT IN
                ('FINALIZE_KNOWN', 'FINALIZE_UNKNOWN')))
           OR (NEW.attempt = OLD.attempt + 1
               AND NEW.attempt_id = OLD.attempt_id) THEN
            RAISE EXCEPTION 'claimed action attempt identity is inconsistent';
        END IF;
        IF (NEW.lease_claim_id, NEW.lease_worker_id,
            NEW.lease_fencing_token)
           IS DISTINCT FROM
           (OLD.lease_claim_id, OLD.lease_worker_id,
            OLD.lease_fencing_token)
           AND NEW.lease_fencing_token <= OLD.lease_fencing_token THEN
            RAISE EXCEPTION 'fresh action claim must advance fence';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.state NOT IN ('SUCCEEDED', 'FAILED', 'OUTCOME_UNKNOWN')
       OR (NEW."authorization", NEW.attempt, NEW.attempt_id,
           NEW.attempt_call_kind, NEW.attempt_target_handle,
           NEW.attempt_request_fingerprint,
           NEW.attempt_invocation_snapshot_ref, NEW.attempt_call_data,
           NEW.reconciles_execute_attempt_id)
          IS DISTINCT FROM
          (OLD."authorization", OLD.attempt, OLD.attempt_id,
           OLD.attempt_call_kind, OLD.attempt_target_handle,
           OLD.attempt_request_fingerprint,
           OLD.attempt_invocation_snapshot_ref, OLD.attempt_call_data,
           OLD.reconciles_execute_attempt_id) THEN
        RAISE EXCEPTION 'terminal action must preserve its last "authorization"';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_runtime_action_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.agent_runtime_actions
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_action();

CREATE FUNCTION public.guard_agent_runtime_action_resolution()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime action resolution is not deletable';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.winner_fact_ref IS NOT NULL THEN
            RAISE EXCEPTION 'action resolution must start as an empty slot';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id, NEW.action_id, NEW.invocation_id,
        NEW.created_epoch_second, NEW.created_nano)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.action_id, OLD.invocation_id,
        OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'action resolution immutable identity changed';
    END IF;
    IF OLD.winner_fact_ref IS NOT NULL THEN
        RAISE EXCEPTION 'action resolution winner is immutable';
    END IF;
    IF NEW.winner_fact_ref IS NULL THEN
        RAISE EXCEPTION 'empty action resolution slot cannot be rewritten';
    END IF;
    IF (NEW.updated_epoch_second, NEW.updated_nano)
       < (OLD.updated_epoch_second, OLD.updated_nano) THEN
        RAISE EXCEPTION 'action resolution time moved backwards';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_runtime_action_resolution_guard
    BEFORE INSERT OR UPDATE OR DELETE
    ON public.nw_runtime_action_resolutions
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_action_resolution();

CREATE FUNCTION public.guard_agent_runtime_action_permit()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime action permit is not deletable';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ISSUED' THEN
            RAISE EXCEPTION 'action permit must start in issued state';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id, NEW.action_id, NEW.invocation_id, NEW.attempt_id,
        NEW.start_operation_id, NEW.started_fact_ref,
        NEW.bearer_fingerprint, NEW.intent_data, NEW.started_fact_data,
        NEW.issued_epoch_second, NEW.issued_nano)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.action_id, OLD.invocation_id, OLD.attempt_id,
        OLD.start_operation_id, OLD.started_fact_ref,
        OLD.bearer_fingerprint, OLD.intent_data, OLD.started_fact_data,
        OLD.issued_epoch_second, OLD.issued_nano) THEN
        RAISE EXCEPTION 'action permit immutable issue facts changed';
    END IF;
    IF OLD.status <> 'ISSUED' OR NEW.status <> 'CONSUMED' THEN
        RAISE EXCEPTION 'action permit may be consumed only once';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_runtime_action_permit_guard
    BEFORE INSERT OR UPDATE OR DELETE
    ON public.nw_runtime_action_permit_consumptions
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_action_permit();

CREATE FUNCTION public.guard_agent_runtime_action_publication()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime action publication is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.source_kind, NEW.source_id, NEW.event_id,
        NEW.source_revision, NEW.created_epoch_second, NEW.created_nano)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.source_kind, OLD.source_id, OLD.event_id,
        OLD.source_revision, OLD.created_epoch_second, OLD.created_nano) THEN
        RAISE EXCEPTION 'action publication immutable identity changed';
    END IF;
    IF OLD.status = 'ACKNOWLEDGED' THEN
        RAISE EXCEPTION 'acknowledged action publication is immutable';
    END IF;
    IF NEW.publication_revision <> OLD.publication_revision + 1 THEN
        RAISE EXCEPTION 'action publication revision must advance by one';
    END IF;
    IF NEW.status = 'CLAIMED' THEN
        IF OLD.status NOT IN ('PENDING', 'CLAIMED')
           OR NEW.attempts <> OLD.attempts + 1
           OR (OLD.status = 'CLAIMED'
               AND NEW.lease_fencing_token <= OLD.lease_fencing_token) THEN
            RAISE EXCEPTION 'invalid action publication claim';
        END IF;
    ELSIF OLD.status = 'CLAIMED'
          AND NEW.status IN ('PENDING', 'ACKNOWLEDGED') THEN
        IF NEW.attempts <> OLD.attempts THEN
            RAISE EXCEPTION 'action publication mutation changed attempts';
        END IF;
    ELSE
        RAISE EXCEPTION 'illegal action publication state transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_runtime_action_publication_guard
    BEFORE UPDATE OR DELETE
    ON public.agent_runtime_action_state_publications
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_action_publication();

CREATE FUNCTION public.reject_agent_runtime_action_append_only_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime action journal is append-only';
    RETURN NULL;
END;
$$;

CREATE TRIGGER agent_runtime_action_attempt_fact_immutable
    BEFORE UPDATE OR DELETE ON public.agent_runtime_action_attempt_facts
    FOR EACH ROW
    EXECUTE FUNCTION public.reject_agent_runtime_action_append_only_mutation();
CREATE TRIGGER agent_runtime_action_operation_immutable
    BEFORE UPDATE OR DELETE ON public.nw_runtime_action_operations
    FOR EACH ROW
    EXECUTE FUNCTION public.reject_agent_runtime_action_append_only_mutation();
CREATE TRIGGER agent_runtime_action_conflict_audit_immutable
    BEFORE UPDATE OR DELETE ON public.nw_runtime_action_conflict_audits
    FOR EACH ROW
    EXECUTE FUNCTION public.reject_agent_runtime_action_append_only_mutation();
CREATE TRIGGER agent_runtime_action_publication_mutation_immutable
    BEFORE UPDATE OR DELETE
    ON public.nw_runtime_action_publication_mutations
    FOR EACH ROW
    EXECUTE FUNCTION public.reject_agent_runtime_action_append_only_mutation();

CREATE FUNCTION public.assert_agent_runtime_action_attempt_chain()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    previous public.agent_runtime_action_attempt_facts%ROWTYPE;
BEGIN
    IF NEW.evidence_revision = 1 THEN
        IF NEW.status <> 'STARTED' THEN
            RAISE EXCEPTION 'first action attempt fact must be STARTED';
        END IF;
        RETURN NULL;
    END IF;
    SELECT * INTO previous
      FROM public.agent_runtime_action_attempt_facts f
     WHERE f.tenant_id = NEW.tenant_id
       AND f.action_id = NEW.action_id
       AND f.attempt_id = NEW.attempt_id
       AND f.evidence_revision = NEW.evidence_revision - 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'action attempt evidence revision has a gap';
    END IF;
    IF (NEW.invocation_id, NEW.start_operation_id,
        NEW.reconciles_execute_attempt_id, NEW."authorization",
        NEW.call_kind, NEW.started_epoch_second, NEW.started_nano)
       IS DISTINCT FROM
       (previous.invocation_id, previous.start_operation_id,
        previous.reconciles_execute_attempt_id, previous."authorization",
        previous.call_kind, previous.started_epoch_second,
        previous.started_nano) THEN
        RAISE EXCEPTION 'action attempt immutable identity changed';
    END IF;
    IF NOT ((previous.status = 'STARTED'
             AND NEW.status IN
                ('NOT_DISPATCHED', 'SUCCEEDED', 'FAILED', 'UNRESOLVED'))
            OR (previous.status = 'UNRESOLVED'
                AND NEW.status IN ('SUCCEEDED', 'FAILED'))) THEN
        RAISE EXCEPTION 'illegal action attempt evidence transition';
    END IF;
    IF (NEW.updated_epoch_second, NEW.updated_nano)
       < (previous.updated_epoch_second, previous.updated_nano) THEN
        RAISE EXCEPTION 'action attempt evidence time moved backwards';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_action_attempt_chain
    AFTER INSERT ON public.agent_runtime_action_attempt_facts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_action_attempt_chain();

CREATE FUNCTION public.assert_agent_runtime_action_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation public.nw_runtime_action_operations%ROWTYPE;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.action_revision = OLD.action_revision THEN
        SELECT * INTO operation
          FROM public.nw_runtime_action_operations o
         WHERE o.tenant_id = NEW.tenant_id
           AND o.action_id = NEW.action_id
           AND o.operation_kind = 'RENEW'
           AND o.writes_action
           AND o.output_action_revision = NEW.action_revision
           AND o.output_action_state = NEW.state
           AND o.output_action_data = NEW.record_data;
    ELSE
        SELECT * INTO operation
          FROM public.nw_runtime_action_operations o
         WHERE o.tenant_id = NEW.tenant_id
           AND o.action_id = NEW.action_id
           AND o.operation_kind <> 'RENEW'
           AND o.writes_action
           AND o.output_action_revision = NEW.action_revision
           AND o.output_action_state = NEW.state
           AND o.output_action_data = NEW.record_data;
    END IF;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'action state mutation must commit its operation journal';
    END IF;
    IF operation.operation_kind <> 'RENEW' THEN
        IF NOT operation.writes_publication
           OR NOT EXISTS (
                SELECT 1
                  FROM public.agent_runtime_action_state_publications p
                 WHERE p.tenant_id = NEW.tenant_id
                   AND p.source_id = NEW.action_id
                   AND p.event_id = operation.publication_event_id
                   AND p.source_revision = NEW.action_revision) THEN
            RAISE EXCEPTION 'action domain mutation must commit its publication';
        END IF;
    ELSIF operation.writes_publication THEN
        RAISE EXCEPTION 'action renewal must not commit a publication';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_action_resolutions r
         WHERE r.tenant_id = NEW.tenant_id
           AND r.action_id = NEW.action_id
           AND r.invocation_id = NEW.invocation_id
           AND r.winner_fact_ref IS NULL) THEN
        RAISE EXCEPTION 'initial action claim must precreate an empty resolution slot';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_action_closed
    AFTER INSERT OR UPDATE ON public.agent_runtime_actions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_action_closed();

CREATE FUNCTION public.assert_agent_runtime_action_attempt_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_action_operations o
         WHERE o.tenant_id = NEW.tenant_id
           AND o.action_id = NEW.action_id
           AND o.invocation_id = NEW.invocation_id
           AND (o.operation_kind = 'CLAIM' OR o.attempt_id = NEW.attempt_id)
           AND o.writes_fact
           AND o.written_fact_ref = NEW.fact_ref
           AND o.written_fact_data = NEW.fact_data) THEN
        RAISE EXCEPTION 'action attempt fact must commit its operation journal';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_action_attempt_closed
    AFTER INSERT ON public.agent_runtime_action_attempt_facts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_action_attempt_closed();

CREATE FUNCTION public.assert_agent_runtime_action_resolution_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (
            SELECT 1
              FROM public.nw_runtime_action_operations o
             WHERE o.tenant_id = NEW.tenant_id
               AND o.action_id = NEW.action_id
               AND o.invocation_id = NEW.invocation_id
               AND o.operation_kind = 'CLAIM'
               AND o.writes_resolution_slot) THEN
            RAISE EXCEPTION 'action resolution slot must commit with initial claim';
        END IF;
        RETURN NULL;
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_action_attempt_facts f
         WHERE f.tenant_id = NEW.tenant_id
           AND f.action_id = NEW.action_id
           AND f.invocation_id = NEW.invocation_id
           AND f.fact_ref = NEW.winner_fact_ref
           AND f.fact_data = NEW.winner_fact_data
           AND f.resolution_key = NEW.resolution_key
           AND f.status IN ('SUCCEEDED', 'FAILED')) THEN
        RAISE EXCEPTION 'action resolution winner must match an eligible known fact';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_action_operations o
         WHERE o.tenant_id = NEW.tenant_id
           AND o.action_id = NEW.action_id
           AND o.invocation_id = NEW.invocation_id
           AND o.operation_kind = 'RESULT'
           AND o.writes_resolution
           AND o.written_fact_ref = NEW.winner_fact_ref
           AND o.winner_fact_ref = NEW.winner_fact_ref) THEN
        RAISE EXCEPTION 'action resolution winner must commit its result journal';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_action_resolution_closed
    AFTER INSERT OR UPDATE ON public.nw_runtime_action_resolutions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_action_resolution_closed();

CREATE FUNCTION public.assert_agent_runtime_action_permit_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (
            SELECT 1
              FROM public.agent_runtime_action_attempt_facts f
             WHERE f.tenant_id = NEW.tenant_id
               AND f.action_id = NEW.action_id
               AND f.invocation_id = NEW.invocation_id
               AND f.attempt_id = NEW.attempt_id
               AND f.start_operation_id = NEW.start_operation_id
               AND f.evidence_revision = 1
               AND f.status = 'STARTED'
               AND f.fact_ref = NEW.started_fact_ref
               AND f.fact_data = NEW.started_fact_data) THEN
            RAISE EXCEPTION 'issued action permit must match its STARTED fact';
        END IF;
        IF NOT EXISTS (
            SELECT 1
              FROM public.nw_runtime_action_operations o
             WHERE o.tenant_id = NEW.tenant_id
               AND o.action_id = NEW.action_id
               AND o.invocation_id = NEW.invocation_id
               AND o.attempt_id = NEW.attempt_id
               AND o.operation_kind = 'START'
               AND o.original_operation_id = NEW.start_operation_id
               AND o.response_kind = 'STARTED'
               AND o.writes_permit
               AND o.written_fact_ref = NEW.started_fact_ref) THEN
            RAISE EXCEPTION 'issued action permit must commit its start journal';
        END IF;
        RETURN NULL;
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_action_operations o
         WHERE o.tenant_id = NEW.tenant_id
           AND o.action_id = NEW.action_id
           AND o.invocation_id = NEW.invocation_id
           AND o.attempt_id = NEW.attempt_id
           AND o.operation_kind = 'CONSUME'
           AND o.original_operation_id = NEW.consume_operation_id
           AND o.response_kind = 'CONSUMED'
           AND o.writes_permit_consumption
           AND o.response_fact_ref = NEW.started_fact_ref) THEN
        RAISE EXCEPTION 'consumed action permit must commit its consume journal';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_action_permit_closed
    AFTER INSERT OR UPDATE
    ON public.nw_runtime_action_permit_consumptions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_action_permit_closed();

CREATE FUNCTION public.assert_agent_runtime_action_operation_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.writes_action AND NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_actions a
         WHERE a.tenant_id = NEW.tenant_id
           AND a.action_id = NEW.action_id
           AND a.action_revision = NEW.output_action_revision
           AND a.state = NEW.output_action_state
           AND a.record_data = NEW.output_action_data) THEN
        RAISE EXCEPTION 'action operation output must match current action';
    END IF;
    IF NEW.writes_fact AND NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_action_attempt_facts f
         WHERE f.tenant_id = NEW.tenant_id
           AND f.action_id = NEW.action_id
           AND f.invocation_id = NEW.invocation_id
           AND (NEW.operation_kind = 'CLAIM' OR f.attempt_id = NEW.attempt_id)
           AND f.fact_ref = NEW.written_fact_ref
           AND f.fact_data = NEW.written_fact_data) THEN
        RAISE EXCEPTION 'action operation written fact must match attempt evidence';
    END IF;
    IF NEW.writes_resolution_slot AND NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_action_resolutions r
         WHERE r.tenant_id = NEW.tenant_id
           AND r.action_id = NEW.action_id
           AND r.invocation_id = NEW.invocation_id) THEN
        RAISE EXCEPTION 'initial claim operation requires resolution slot';
    END IF;
    IF NEW.writes_resolution AND NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_action_resolutions r
         WHERE r.tenant_id = NEW.tenant_id
           AND r.action_id = NEW.action_id
           AND r.invocation_id = NEW.invocation_id
           AND r.winner_fact_ref = NEW.winner_fact_ref) THEN
        RAISE EXCEPTION 'result operation winner must match resolved index';
    END IF;
    IF NEW.writes_permit AND NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_action_permit_consumptions p
         WHERE p.tenant_id = NEW.tenant_id
           AND p.action_id = NEW.action_id
           AND p.invocation_id = NEW.invocation_id
           AND p.attempt_id = NEW.attempt_id
           AND p.start_operation_id = NEW.original_operation_id
           AND p.started_fact_ref = NEW.written_fact_ref
           AND p.status = 'ISSUED') THEN
        RAISE EXCEPTION 'start operation must commit its issued permit';
    END IF;
    IF NEW.writes_permit_consumption AND NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_action_permit_consumptions p
         WHERE p.tenant_id = NEW.tenant_id
           AND p.action_id = NEW.action_id
           AND p.invocation_id = NEW.invocation_id
           AND p.attempt_id = NEW.attempt_id
           AND p.consume_operation_id = NEW.original_operation_id
           AND p.started_fact_ref = NEW.response_fact_ref
           AND p.status = 'CONSUMED') THEN
        RAISE EXCEPTION 'consume operation must match committed permit consumption';
    END IF;
    IF NEW.writes_audit AND NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_action_conflict_audits c
         WHERE c.tenant_id = NEW.tenant_id
           AND c.operation_key = NEW.operation_key
           AND c.audit_ref = NEW.audit_ref) THEN
        RAISE EXCEPTION 'conflict operation must commit its audit fact';
    END IF;
    IF NEW.writes_publication AND NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_action_state_publications p
         WHERE p.tenant_id = NEW.tenant_id
           AND p.source_id = NEW.action_id
           AND p.event_id = NEW.publication_event_id
           AND p.source_revision = NEW.publication_source_revision) THEN
        RAISE EXCEPTION 'action operation must commit its publication';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_action_operation_closed
    AFTER INSERT ON public.nw_runtime_action_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_action_operation_closed();

CREATE FUNCTION public.assert_agent_runtime_action_conflict_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_action_operations o
         WHERE o.tenant_id = NEW.tenant_id
           AND o.operation_key = NEW.operation_key
           AND o.action_id = NEW.action_id
           AND o.invocation_id = NEW.invocation_id
           AND o.writes_audit
           AND o.audit_ref = NEW.audit_ref) THEN
        RAISE EXCEPTION 'action conflict audit must match its operation journal';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_action_conflict_closed
    AFTER INSERT ON public.nw_runtime_action_conflict_audits
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_action_conflict_closed();

CREATE FUNCTION public.assert_agent_runtime_action_publication_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_action_operations o
         WHERE o.tenant_id = NEW.tenant_id
           AND o.action_id = NEW.source_id
           AND o.writes_publication
           AND o.publication_event_id = NEW.event_id
           AND o.publication_source_revision = NEW.source_revision
           AND o.output_action_revision = NEW.source_revision) THEN
        RAISE EXCEPTION 'action publication must match a journaled action revision';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_action_publication_closed
    AFTER INSERT ON public.agent_runtime_action_state_publications
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_action_publication_closed();

CREATE FUNCTION public.assert_agent_runtime_action_publication_mutation_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_action_publication_mutations m
         WHERE m.tenant_id = NEW.tenant_id
           AND m.source_id = NEW.source_id AND m.event_id = NEW.event_id
           AND m.output_revision = NEW.publication_revision
           AND m.output_status = NEW.status
           AND m.output_data = NEW.publication_data) THEN
        RAISE EXCEPTION 'action publication mutation must commit its journal';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_action_publication_mutation_closed
    AFTER UPDATE ON public.agent_runtime_action_state_publications
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    WHEN (OLD.status = 'CLAIMED' AND NEW.status IN ('PENDING', 'ACKNOWLEDGED'))
    EXECUTE FUNCTION public.assert_agent_runtime_action_publication_mutation_closed();

CREATE FUNCTION public.assert_agent_runtime_action_publication_journal_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_action_state_publications p
         WHERE p.tenant_id = NEW.tenant_id
           AND p.source_id = NEW.source_id AND p.event_id = NEW.event_id
           AND p.publication_revision = NEW.output_revision
           AND p.status = NEW.output_status
           AND p.publication_data = NEW.output_data) THEN
        RAISE EXCEPTION 'action publication journal must match current output';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_action_publication_journal_closed
    AFTER INSERT ON public.nw_runtime_action_publication_mutations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_action_publication_journal_closed();

ALTER TABLE public.agent_runtime_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_actions FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_action_tenant_isolation
    ON public.agent_runtime_actions
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_action_attempt_facts
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_action_attempt_facts
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_action_attempt_tenant_isolation
    ON public.agent_runtime_action_attempt_facts
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.nw_runtime_action_resolutions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_action_resolutions FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_action_resolution_tenant_isolation
    ON public.nw_runtime_action_resolutions
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.nw_runtime_action_permit_consumptions
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_action_permit_consumptions
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_action_permit_tenant_isolation
    ON public.nw_runtime_action_permit_consumptions
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.nw_runtime_action_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_action_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_action_operation_tenant_isolation
    ON public.nw_runtime_action_operations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.nw_runtime_action_conflict_audits
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_action_conflict_audits
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_action_conflict_tenant_isolation
    ON public.nw_runtime_action_conflict_audits
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.agent_runtime_action_state_publications
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_action_state_publications
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_action_publication_tenant_isolation
    ON public.agent_runtime_action_state_publications
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.nw_runtime_action_publication_mutations
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_action_publication_mutations
    FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_runtime_action_publication_mutation_tenant_isolation
    ON public.nw_runtime_action_publication_mutations
    USING (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON SEQUENCE public.agent_runtime_action_fencing_seq IS
    'Action writer lease 与 action-owned publication 共用的跨进程单调 fencing token；回滚允许留空洞';
COMMENT ON TABLE public.agent_runtime_actions IS
    '逻辑 Action 的不可变调用 tuple、当前 "authorization"/attempt、单调 revision、lease 与终态';
COMMENT ON TABLE public.agent_runtime_action_attempt_facts IS
    '每个外部 attempt 按 evidenceRevision append-only 保存的唯一权威事实历史';
COMMENT ON TABLE public.nw_runtime_action_resolutions IS
    '首次 claim 预建的 invocation resolution slot；只允许一次性固定 first winner';
COMMENT ON TABLE public.nw_runtime_action_permit_consumptions IS
    'STARTED intent、不可逆 bearer 摘要和最多一次 committed consumption 的绑定事实';
COMMENT ON TABLE public.nw_runtime_action_operations IS
    'Action claim/renew/start/consume/result/retry/terminal 的 append-only 精确响应 journal';
COMMENT ON TABLE public.nw_runtime_action_conflict_audits IS
    '幂等 tuple 漂移与 provider outcome 不兼容的 append-only 稳定审计事实';
COMMENT ON TABLE public.agent_runtime_action_state_publications IS
    'Action 领域自有 Session state event durable outbox';
COMMENT ON TABLE public.nw_runtime_action_publication_mutations IS
    'Action publication ACK/retry 的 append-only fenced operation journal';

COMMENT ON COLUMN public.agent_runtime_actions.tenant_id IS 'Action 租户与 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_actions.action_id IS '租户内稳定逻辑 Action 标识';
COMMENT ON COLUMN public.agent_runtime_actions.session_id IS '冻结 Run owner 的 Session 标识';
COMMENT ON COLUMN public.agent_runtime_actions.agent_id IS '冻结 Run owner 的 Agent 标识';
COMMENT ON COLUMN public.agent_runtime_actions.end_user_id IS '冻结 Run owner 的终端用户标识';
COMMENT ON COLUMN public.agent_runtime_actions.run_id IS '冻结 RunIdentity 的 façade Run 标识';
COMMENT ON COLUMN public.agent_runtime_actions.causation_input_id IS '冻结 RunIdentity 的因果 Input 标识';
COMMENT ON COLUMN public.agent_runtime_actions.causation_identity_ref IS '冻结原调用方身份事实引用';
COMMENT ON COLUMN public.agent_runtime_actions.execution_id IS '可选归属 Workflow Execution 标识；不建立跨 Ledger 外键';
COMMENT ON COLUMN public.agent_runtime_actions.delegation_id IS '可选归属 Delegation 标识；不建立跨 Ledger 外键';
COMMENT ON COLUMN public.agent_runtime_actions.action_kind IS '逻辑 Action 的不可变领域种类';
COMMENT ON COLUMN public.agent_runtime_actions.execute_call_kind IS '不可变原始调用 PortalCallKind';
COMMENT ON COLUMN public.agent_runtime_actions.execute_target_handle IS '不可变原始调用冻结 target handle';
COMMENT ON COLUMN public.agent_runtime_actions.execute_request_fingerprint IS '原始调用闭包的 lowercase SHA-256';
COMMENT ON COLUMN public.agent_runtime_actions.execute_invocation_snapshot_ref IS '原始完整调用快照的 content-addressed 引用';
COMMENT ON COLUMN public.agent_runtime_actions.execute_call_data IS '完整 ActionCallSnapshot 的 canonical 私有 codec';
COMMENT ON COLUMN public.agent_runtime_actions.reconcile_call_kind IS 'RESULT_QUERYABLE 唯一 WORKFLOW_RECOVER_RESULT 模板种类';
COMMENT ON COLUMN public.agent_runtime_actions.reconcile_target_handle IS '可选协调模板冻结 target handle';
COMMENT ON COLUMN public.agent_runtime_actions.reconcile_request_fingerprint IS '可选协调模板的 lowercase SHA-256';
COMMENT ON COLUMN public.agent_runtime_actions.reconcile_invocation_snapshot_ref IS '可选协调模板快照引用';
COMMENT ON COLUMN public.agent_runtime_actions.reconcile_call_data IS '可选完整协调模板的 canonical 私有 codec';
COMMENT ON COLUMN public.agent_runtime_actions.idempotency_key IS '租户级唯一、首次 claim 后不可重绑的幂等键';
COMMENT ON COLUMN public.agent_runtime_actions.retry_mode IS 'claim-time 冻结 ActionRetryMode';
COMMENT ON COLUMN public.agent_runtime_actions.invocation_id IS '安全重试间稳定的逻辑 invocation 标识';
COMMENT ON COLUMN public.agent_runtime_actions.state IS 'CLAIMED 或 SUCCEEDED/FAILED/OUTCOME_UNKNOWN 当前状态';
COMMENT ON COLUMN public.agent_runtime_actions."authorization" IS '当前 lease 或终态保留的最后 ActionAuthorization';
COMMENT ON COLUMN public.agent_runtime_actions.action_revision IS '每次领域 mutation 单调加一；renew 不递增';
COMMENT ON COLUMN public.agent_runtime_actions.attempt IS '已签发外部调用 "authorization" 的正数计数';
COMMENT ON COLUMN public.agent_runtime_actions.attempt_id IS '当前外部 attempt 的全局唯一标识';
COMMENT ON COLUMN public.agent_runtime_actions.attempt_call_kind IS '当前或最后 "authorization" 的 PortalCallKind';
COMMENT ON COLUMN public.agent_runtime_actions.attempt_target_handle IS '当前或最后 attempt 的冻结 target handle';
COMMENT ON COLUMN public.agent_runtime_actions.attempt_request_fingerprint IS '当前或最后 attempt 调用闭包的 lowercase SHA-256';
COMMENT ON COLUMN public.agent_runtime_actions.attempt_invocation_snapshot_ref IS '当前或最后完整 attempt 快照引用';
COMMENT ON COLUMN public.agent_runtime_actions.attempt_call_data IS '当前或最后完整 ActionCallSnapshot canonical 私有 codec';
COMMENT ON COLUMN public.agent_runtime_actions.reconciles_execute_attempt_id IS 'RECONCILE attempt 绑定的最近 EXECUTE attempt 标识';
COMMENT ON COLUMN public.agent_runtime_actions.canonical_observation_data IS 'SUCCEEDED 按 registry presence 规则保存的安全模型观察 codec';
COMMENT ON COLUMN public.agent_runtime_actions.outcome_fact_ref IS '终结当前 Action 的不可变 attempt 事实引用';
COMMENT ON COLUMN public.agent_runtime_actions.reason_code IS 'FAILED/OUTCOME_UNKNOWN 从事实确定性解码的稳定原因码';
COMMENT ON COLUMN public.agent_runtime_actions.lease_claim_id IS '当前 CLAIMED writer lease 的 claim 标识';
COMMENT ON COLUMN public.agent_runtime_actions.lease_worker_id IS '当前 CLAIMED writer lease 的 worker 标识';
COMMENT ON COLUMN public.agent_runtime_actions.lease_fencing_token IS '当前 CLAIMED writer lease 的单调 fencing token';
COMMENT ON COLUMN public.agent_runtime_actions.lease_action_revision IS '当前 ActionLease 精确绑定的 action revision';
COMMENT ON COLUMN public.agent_runtime_actions.lease_until_epoch_second IS '存储时钟 lease 到期秒';
COMMENT ON COLUMN public.agent_runtime_actions.lease_until_nano IS '存储时钟 lease 到期纳秒';
COMMENT ON COLUMN public.agent_runtime_actions.initial_claim_data IS '首次 ClaimAction 完整 canonical 私有 codec；用于 tuple 反校验';
COMMENT ON COLUMN public.agent_runtime_actions.record_data IS '当前完整 ActionRecord canonical 私有 codec';
COMMENT ON COLUMN public.agent_runtime_actions.created_epoch_second IS 'Action 数据库创建时间秒';
COMMENT ON COLUMN public.agent_runtime_actions.created_nano IS 'Action 数据库创建时间纳秒';
COMMENT ON COLUMN public.agent_runtime_actions.updated_epoch_second IS '最近 Action mutation 或 renew 的数据库时间秒';
COMMENT ON COLUMN public.agent_runtime_actions.updated_nano IS '最近 Action mutation 或 renew 的数据库时间纳秒';

COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.tenant_id IS 'attempt 事实租户与 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.action_id IS 'attempt 所属逻辑 Action 标识';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.invocation_id IS 'attempt 所属稳定逻辑 invocation 标识';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.attempt_id IS '一次具体外部 attempt 标识';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.start_operation_id IS '创建该 attempt 的稳定 start operation 标识';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.evidence_revision IS '同 attempt 从 STARTED 起逐次加一的证据 revision';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.reconciles_execute_attempt_id IS 'RECONCILE 事实绑定的被查询 EXECUTE attempt';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts."authorization" IS '仅 EXECUTE 或 RECONCILE 的外部调用授权';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.call_kind IS '事实中完整调用快照的 PortalCallKind';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.status IS 'STARTED/NOT_DISPATCHED/SUCCEEDED/FAILED/UNRESOLVED';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.outcome_resolution IS '非空 outcome 的 KNOWN_RESULT/KNOWN_FAILURE/OUTCOME_UNKNOWN';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.outcome_type IS 'registry 登记的 Portal outcome subtype 名';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.resolution_key IS 'eligible known outcome 的 provider-neutral action resolution key';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.fact_ref IS '该 evidenceRevision 不可变事实的稳定 ResultRef';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.fact_data IS '完整 ActionAttemptFact canonical 私有 codec';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.started_epoch_second IS 'attempt 首次 STARTED 数据库时间秒';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.started_nano IS 'attempt 首次 STARTED 数据库时间纳秒';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.updated_epoch_second IS '本证据版本数据库时间秒';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.updated_nano IS '本证据版本数据库时间纳秒';

COMMENT ON COLUMN public.nw_runtime_action_resolutions.tenant_id IS 'resolved index 租户与 RLS 隔离键';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.action_id IS 'resolved index 所属逻辑 Action 标识';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.invocation_id IS 'resolved index 唯一逻辑 invocation 标识';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.winner_attempt_id IS 'first winner 所属 attempt 标识；空表示未决';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.winner_evidence_revision IS 'first winner 的 evidence revision；空表示未决';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.winner_fact_ref IS '不可变 first winner 事实引用；空表示未决';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.winner_fact_data IS '不可变 first winner 完整 canonical 私有 codec';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.resolution_key IS 'first winner 的 provider-neutral action resolution key';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.created_epoch_second IS '首次 claim 预建 resolution slot 的数据库时间秒';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.created_nano IS '首次 claim 预建 resolution slot 的数据库时间纳秒';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.resolved_epoch_second IS 'first winner 线性化提交时间秒';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.resolved_nano IS 'first winner 线性化提交时间纳秒';

COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.tenant_id IS 'permit 租户与 RLS 隔离键';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.action_id IS 'permit 所属逻辑 Action 标识';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.invocation_id IS 'permit 所属稳定逻辑 invocation 标识';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.attempt_id IS 'permit 唯一绑定的 attempt 标识';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.start_operation_id IS '首次签发 permit 的 start operation 标识';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.started_fact_ref IS 'permit 唯一绑定的 evidenceRevision=1 STARTED 事实';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.bearer_fingerprint IS 'bearer 原值的不可逆 lowercase SHA-256；原值绝不持久化';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.status IS 'ISSUED 或单向 CONSUMED';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.consume_operation_id IS '首次成功消费的租户级全局 operation 标识';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.intent_data IS '首次签发时冻结的完整 ActionInvocationIntent codec';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.started_fact_data IS '首次签发时冻结的完整 STARTED fact codec';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.issued_epoch_second IS 'permit 首次签发数据库时间秒';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.issued_nano IS 'permit 首次签发数据库时间纳秒';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.consumed_epoch_second IS '首次 committed consumption 数据库时间秒';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.consumed_nano IS '首次 committed consumption 数据库时间纳秒';

COMMENT ON COLUMN public.nw_runtime_action_operations.tenant_id IS 'operation journal 租户与 RLS 隔离键';
COMMENT ON COLUMN public.nw_runtime_action_operations.operation_key IS 'operation kind 与资源身份 domain-separated 的稳定 journal 键';
COMMENT ON COLUMN public.nw_runtime_action_operations.original_operation_id IS '调用方提交的原始 event/start/consume/result/renew operation 标识';
COMMENT ON COLUMN public.nw_runtime_action_operations.operation_kind IS 'CLAIM/RENEW/START/CONSUME/RESULT/RETRY/COMPLETE/FAIL/OUTCOME_UNKNOWN';
COMMENT ON COLUMN public.nw_runtime_action_operations.action_id IS 'operation 所属或冲突绑定的逻辑 Action 标识';
COMMENT ON COLUMN public.nw_runtime_action_operations.invocation_id IS 'operation 冻结的逻辑 invocation 标识';
COMMENT ON COLUMN public.nw_runtime_action_operations.attempt_id IS 'operation 输入或首次输出绑定的 attempt 标识；未创建 Action 的 claim 拒绝为空';
COMMENT ON COLUMN public.nw_runtime_action_operations.expected_action_revision IS '需要 CAS 的 operation 输入 Action revision';
COMMENT ON COLUMN public.nw_runtime_action_operations.command_fingerprint IS '完整原命令 canonical codec 的 lowercase SHA-256';
COMMENT ON COLUMN public.nw_runtime_action_operations.command_data IS '完整原命令 canonical 私有 codec；不含 bearer 原值';
COMMENT ON COLUMN public.nw_runtime_action_operations.response_kind IS '首次线性化响应的精确 sealed subtype 名';
COMMENT ON COLUMN public.nw_runtime_action_operations.response_data IS '首次响应 canonical 私有 codec；STARTED 只保存可持久安全投影';
COMMENT ON COLUMN public.nw_runtime_action_operations.output_action_revision IS '响应冻结或实际写入的 Action revision';
COMMENT ON COLUMN public.nw_runtime_action_operations.output_action_state IS '响应冻结或实际写入的 Action state';
COMMENT ON COLUMN public.nw_runtime_action_operations.output_action_fingerprint IS '完整输出 ActionRecord codec 的 lowercase SHA-256';
COMMENT ON COLUMN public.nw_runtime_action_operations.output_action_data IS '完整输出 ActionRecord canonical 私有 codec';
COMMENT ON COLUMN public.nw_runtime_action_operations.written_fact_ref IS '本 operation 新追加的 attempt fact 引用';
COMMENT ON COLUMN public.nw_runtime_action_operations.written_fact_data IS '本 operation 新追加的完整 attempt fact codec';
COMMENT ON COLUMN public.nw_runtime_action_operations.response_fact_ref IS '首次响应直接携带的 attempt fact 引用';
COMMENT ON COLUMN public.nw_runtime_action_operations.latest_fact_ref IS '响应决策时冻结的同 attempt latest fact 引用';
COMMENT ON COLUMN public.nw_runtime_action_operations.winner_fact_ref IS '响应决策时冻结的 resolved first winner 引用';
COMMENT ON COLUMN public.nw_runtime_action_operations.audit_ref IS '首次冲突响应创建的稳定审计事实引用';
COMMENT ON COLUMN public.nw_runtime_action_operations.publication_event_id IS '本 operation 原子写入的 Action state event 标识';
COMMENT ON COLUMN public.nw_runtime_action_operations.publication_source_revision IS '本 operation publication 对应 Action revision';
COMMENT ON COLUMN public.nw_runtime_action_operations.writes_action IS '本 operation 是否实际写入当前 Action row';
COMMENT ON COLUMN public.nw_runtime_action_operations.writes_fact IS '本 operation 是否实际追加 attempt fact';
COMMENT ON COLUMN public.nw_runtime_action_operations.writes_resolution_slot IS '本 operation 是否预建首次 claim 空 resolution slot';
COMMENT ON COLUMN public.nw_runtime_action_operations.writes_resolution IS '本 operation 是否首次固定 resolved winner';
COMMENT ON COLUMN public.nw_runtime_action_operations.writes_permit IS '本 operation 是否首次签发 permit 摘要 row';
COMMENT ON COLUMN public.nw_runtime_action_operations.writes_permit_consumption IS '本 operation 是否首次提交 permit consumption';
COMMENT ON COLUMN public.nw_runtime_action_operations.writes_audit IS '本 operation 是否追加稳定冲突审计事实';
COMMENT ON COLUMN public.nw_runtime_action_operations.writes_publication IS '本 operation 是否原子写入 Action state publication';
COMMENT ON COLUMN public.nw_runtime_action_operations.created_epoch_second IS '首次持久化 immutable operation journal 的数据库时间秒';
COMMENT ON COLUMN public.nw_runtime_action_operations.created_nano IS '首次持久化 immutable operation journal 的数据库时间纳秒';

COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.tenant_id IS '冲突审计租户与 RLS 隔离键';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.audit_ref IS '稳定、可重放的冲突 ResultRef';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.action_id IS '幂等绑定的既有 Action 或 provider 冲突所属 Action';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.invocation_id IS '冲突审计绑定的逻辑 invocation 标识';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.operation_key IS '创建该审计事实的唯一 operation journal 键';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.audit_kind IS 'CLAIM_CONFLICT 或 PROVIDER_OUTCOME_CONFLICT';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.winner_fact_ref IS 'provider outcome 冲突时不可变 first winner 引用';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.conflicting_fact_ref IS 'provider outcome 冲突时仍须保存的后续事实引用';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.audit_data IS '完整脱敏冲突审计 canonical 私有 codec';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.created_epoch_second IS '冲突事实数据库创建时间秒';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.created_nano IS '冲突事实数据库创建时间纳秒';

COMMENT ON COLUMN public.agent_runtime_action_state_publications.tenant_id IS 'Action publication 租户与 RLS 隔离键';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.source_kind IS '固定 action 领域来源';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.source_id IS 'publication 所属逻辑 Action 标识';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.event_id IS 'Action 内唯一 Session event 标识';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.source_revision IS '对应唯一 Action 领域 revision';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.publication_revision IS 'claim/ACK/retry 每次递增的 publication CAS revision';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.available_epoch_second IS '待领取或 retry 后可用数据库时间秒';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.available_nano IS '待领取或 retry 后可用数据库时间纳秒';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.status IS 'PENDING/CLAIMED/ACKNOWLEDGED publication 状态';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.attempts IS '成功 claim/takeover 次数';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.lease_claim_id IS '当前 projector claim 标识；仅 CLAIMED 存在';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.lease_worker_id IS '当前 projector worker 标识；仅 CLAIMED 存在';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.lease_fencing_token IS '当前 projector claim 的单调 fencing token';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.lease_until_epoch_second IS 'projector claim 数据库到期时间秒';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.lease_until_nano IS 'projector claim 数据库到期时间纳秒';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.projection_ref IS 'ACK 后持久 Session 投影引用';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.failure_data IS '最近 retry 的稳定 PortFailure 私有 codec';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.publication_data IS '完整 StateEventPublication canonical codec';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.created_epoch_second IS 'publication 首次持久化数据库时间秒';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.created_nano IS 'publication 首次持久化数据库时间纳秒';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.updated_epoch_second IS 'publication 最近一次 claim/ACK/retry 数据库时间秒';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.updated_nano IS 'publication 最近一次 claim/ACK/retry 数据库时间纳秒';

COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.tenant_id IS 'publication mutation 租户与 RLS 隔离键';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.source_id IS 'mutation 所属逻辑 Action 标识';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.event_id IS 'mutation 所属 Session event 标识';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.mutation_key IS 'ACK/retry 的 fence 与 expected revision operation identity';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.mutation_kind IS 'ACKNOWLEDGE 或 RETRY';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.command_fingerprint IS '完整 ACK/retry 命令 canonical codec 指纹';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.command_data IS '完整 ACK/retry 命令 canonical 私有 codec';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.output_revision IS '首次 mutation 后 publication revision';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.output_status IS '首次 mutation 后 PENDING 或 ACKNOWLEDGED';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.output_fingerprint IS '含存储状态的完整 publication 输出指纹';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.output_data IS '首次输出 StateEventPublication canonical codec';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.created_epoch_second IS '首次持久化 immutable ACK/retry journal 的数据库时间秒';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.created_nano IS '首次持久化 immutable ACK/retry journal 的数据库时间纳秒';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.outcome_schema_id IS '结果快照的 schema 标识；与 outcome 类型及内容同时存在';
COMMENT ON COLUMN public.agent_runtime_action_attempt_facts.outcome_canonical_json IS '由 PortalOutcomeCodec 验证的完整规范结果快照';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.updated_epoch_second IS '空槽创建或 winner 固定的数据库时间秒';
COMMENT ON COLUMN public.nw_runtime_action_resolutions.updated_nano IS '空槽创建或 winner 固定的数据库时间纳秒';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.updated_epoch_second IS 'permit 签发或首次消费的数据库时间秒';
COMMENT ON COLUMN public.nw_runtime_action_permit_consumptions.updated_nano IS 'permit 签发或首次消费的数据库时间纳秒';
COMMENT ON COLUMN public.nw_runtime_action_operations.command_type IS '封闭命令类型；consume 与 result 仅允许无 bearer 的私有投影';
COMMENT ON COLUMN public.nw_runtime_action_operations.response_fingerprint IS '首次响应完整规范快照的 SHA-256';
COMMENT ON COLUMN public.nw_runtime_action_operations.output_authorization IS '首次输出 Action 的授权类型';
COMMENT ON COLUMN public.nw_runtime_action_operations.written_fact_fingerprint IS '本 operation 追加事实完整快照的 SHA-256';
COMMENT ON COLUMN public.nw_runtime_action_operations.latest_fact_fingerprint IS '首次响应时最新 attempt 事实快照的 SHA-256';
COMMENT ON COLUMN public.nw_runtime_action_operations.latest_fact_data IS '首次响应时最新 attempt 的完整不可变快照';
COMMENT ON COLUMN public.nw_runtime_action_operations.winner_fact_fingerprint IS '首次响应时 first winner 完整快照的 SHA-256';
COMMENT ON COLUMN public.nw_runtime_action_operations.winner_fact_data IS '首次响应时 first winner 的完整不可变快照';
COMMENT ON COLUMN public.nw_runtime_action_operations.publication_fingerprint IS '原子提交的初始 publication 完整快照的 SHA-256';
COMMENT ON COLUMN public.nw_runtime_action_operations.publication_data IS '原子提交的初始 publication 完整不可变快照';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.attempt_id IS 'Provider 冲突 candidate 所属 attempt；claim 冲突为空';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.reason_code IS '封闭的 claim 或 Provider outcome 冲突原因';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.winner_fact_data IS 'Provider 冲突 first winner 的完整事实快照；claim 冲突为空';
COMMENT ON COLUMN public.nw_runtime_action_conflict_audits.conflicting_fact_data IS 'Provider 冲突 candidate 的完整事实快照；claim 冲突为空';
COMMENT ON COLUMN public.agent_runtime_action_state_publications.publication_fingerprint IS '当前完整 publication 快照的 SHA-256';
COMMENT ON COLUMN public.nw_runtime_action_publication_mutations.command_type IS '封闭的 PublicationAcknowledge 或 PublicationRetry 命令类型';
