-- Tool input_required 是同一逻辑 Action 的非终态多轮 continuation。

ALTER TABLE public.agent_runtime_actions
    DROP CONSTRAINT ck_agent_runtime_action_state;
ALTER TABLE public.agent_runtime_actions
    ADD CONSTRAINT ck_agent_runtime_action_state CHECK (
        state IN ('CLAIMED', 'WAITING_INPUT', 'SUCCEEDED', 'FAILED',
                  'OUTCOME_UNKNOWN')
        AND "authorization" IN
            ('EXECUTE', 'RESUME_INPUT', 'RECONCILE', 'FINALIZE_INPUT',
             'FINALIZE_KNOWN', 'FINALIZE_UNKNOWN'));

ALTER TABLE public.agent_runtime_actions
    DROP CONSTRAINT ck_agent_runtime_action_attempt_call;
ALTER TABLE public.agent_runtime_actions
    ADD CONSTRAINT ck_agent_runtime_action_attempt_call CHECK (
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
             OR ("authorization" = 'RESUME_INPUT'
                 AND execute_call_kind = 'TOOL_INVOKE'
                 AND attempt_call_kind = 'TOOL_INVOKE'
                  AND attempt_target_handle = execute_target_handle
                  AND attempt_invocation_snapshot_ref
                      <> execute_invocation_snapshot_ref
                  AND attempt_invocation_snapshot_ref
                      ~ '^skillforge[.]runtime[.]portal[.]tool-continuation[.]v1:[0-9a-f]{64}$'
                  AND reconciles_execute_attempt_id IS NULL)
             OR ("authorization" = 'RECONCILE'
                 AND attempt_call_kind = 'WORKFLOW_RECOVER_RESULT'
                 AND reconciles_execute_attempt_id IS NOT NULL
                 AND btrim(reconciles_execute_attempt_id) <> ''
                 AND attempt_target_handle = reconcile_target_handle)
             OR ("authorization" = 'FINALIZE_INPUT'
                 AND execute_call_kind = 'TOOL_INVOKE'
                 AND attempt_call_kind = 'TOOL_INVOKE'
                 AND attempt_target_handle = execute_target_handle
                 AND reconciles_execute_attempt_id IS NULL)
             OR ("authorization" IN
                    ('FINALIZE_KNOWN', 'FINALIZE_UNKNOWN')
                 AND ((attempt_call_kind = 'WORKFLOW_RECOVER_RESULT'
                       AND reconciles_execute_attempt_id IS NOT NULL
                       AND btrim(reconciles_execute_attempt_id) <> '')
                      OR (attempt_call_kind <> 'WORKFLOW_RECOVER_RESULT'
                          AND reconciles_execute_attempt_id IS NULL)))));

ALTER TABLE public.agent_runtime_actions
    DROP CONSTRAINT ck_agent_runtime_action_terminal_fields;
ALTER TABLE public.agent_runtime_actions
    ADD CONSTRAINT ck_agent_runtime_action_terminal_fields CHECK (
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
        OR (state = 'WAITING_INPUT'
            AND canonical_observation_data IS NULL
            AND outcome_fact_ref IS NULL AND reason_code IS NULL
            AND lease_claim_id IS NULL AND lease_worker_id IS NULL
            AND lease_fencing_token IS NULL
            AND lease_action_revision IS NULL
            AND lease_until_epoch_second IS NULL
            AND lease_until_nano IS NULL)
        OR (state = 'SUCCEEDED'
            AND outcome_fact_ref IS NOT NULL
            AND btrim(outcome_fact_ref) <> '' AND reason_code IS NULL
            AND lease_claim_id IS NULL AND lease_worker_id IS NULL
            AND lease_fencing_token IS NULL
            AND lease_action_revision IS NULL
            AND lease_until_epoch_second IS NULL
            AND lease_until_nano IS NULL)
        OR (state IN ('FAILED', 'OUTCOME_UNKNOWN')
            AND canonical_observation_data IS NULL
            AND outcome_fact_ref IS NOT NULL AND reason_code IS NOT NULL
            AND btrim(outcome_fact_ref) <> '' AND btrim(reason_code) <> ''
            AND lease_claim_id IS NULL AND lease_worker_id IS NULL
            AND lease_fencing_token IS NULL
            AND lease_action_revision IS NULL
            AND lease_until_epoch_second IS NULL
            AND lease_until_nano IS NULL));

ALTER TABLE public.agent_runtime_action_attempt_facts
    DROP CONSTRAINT ck_agent_runtime_action_attempt_authorization;
ALTER TABLE public.agent_runtime_action_attempt_facts
    ADD CONSTRAINT ck_agent_runtime_action_attempt_authorization CHECK (
        call_kind IN
            ('MODEL_GENERATE', 'MODEL_STREAM', 'TOOL_INVOKE',
             'WORKFLOW_DISPATCH', 'WORKFLOW_RESUME',
             'WORKFLOW_RECOVER_RESULT', 'WORKFLOW_CANCEL',
             'KNOWLEDGE_EXECUTE')
        AND (("authorization" = 'EXECUTE'
              AND reconciles_execute_attempt_id IS NULL
              AND call_kind <> 'WORKFLOW_RECOVER_RESULT')
             OR ("authorization" = 'RESUME_INPUT'
                 AND reconciles_execute_attempt_id IS NULL
                 AND call_kind = 'TOOL_INVOKE')
             OR ("authorization" = 'RECONCILE'
                 AND reconciles_execute_attempt_id IS NOT NULL
                 AND btrim(reconciles_execute_attempt_id) <> ''
                 AND call_kind = 'WORKFLOW_RECOVER_RESULT')));

ALTER TABLE public.agent_runtime_action_attempt_facts
    DROP CONSTRAINT ck_agent_runtime_action_attempt_outcome;
ALTER TABLE public.agent_runtime_action_attempt_facts
    ADD CONSTRAINT ck_agent_runtime_action_attempt_outcome CHECK (
        status IN ('STARTED', 'NOT_DISPATCHED', 'INPUT_REQUIRED',
                   'SUCCEEDED', 'FAILED', 'UNRESOLVED')
        AND ((status IN ('STARTED', 'NOT_DISPATCHED')
              AND outcome_resolution IS NULL AND outcome_type IS NULL
              AND outcome_schema_id IS NULL
              AND outcome_canonical_json IS NULL
              AND resolution_key IS NULL)
             OR (status = 'INPUT_REQUIRED'
                 AND outcome_resolution = 'INPUT_REQUIRED'
                 AND outcome_type = 'InputRequired'
                 AND outcome_schema_id IS NOT NULL
                 AND btrim(outcome_schema_id) <> ''
                 AND outcome_canonical_json IS NOT NULL
                 AND btrim(outcome_canonical_json) <> ''
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
        AND (resolution_key IS NULL OR btrim(resolution_key) <> ''));

CREATE OR REPLACE FUNCTION public.guard_agent_runtime_action()
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
    IF OLD.state = 'WAITING_INPUT' THEN
        IF NEW.state <> 'CLAIMED'
           OR NEW."authorization" <> 'RESUME_INPUT'
           OR NEW.attempt <> OLD.attempt + 1
           OR NEW.attempt_id = OLD.attempt_id
           OR NEW.execute_call_kind <> 'TOOL_INVOKE'
           OR NEW.attempt_call_kind <> 'TOOL_INVOKE'
           OR NEW.reconciles_execute_attempt_id IS NOT NULL THEN
            RAISE EXCEPTION 'waiting Tool action may only claim one continuation attempt';
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
        IF (NEW.attempt = OLD.attempt
            AND (NEW.attempt_id <> OLD.attempt_id
                 OR NEW."authorization" NOT IN
                    ('FINALIZE_INPUT', 'FINALIZE_KNOWN',
                     'FINALIZE_UNKNOWN')))
           OR (NEW.attempt = OLD.attempt + 1
               AND (NEW.attempt_id = OLD.attempt_id
                    OR NEW."authorization" NOT IN
                        ('EXECUTE', 'RESUME_INPUT', 'RECONCILE')
                    OR (NEW."authorization" = 'RESUME_INPUT'
                        AND (OLD."authorization" <> 'RESUME_INPUT'
                             OR NEW.execute_call_kind <> 'TOOL_INVOKE'
                             OR NEW.attempt_call_kind <> 'TOOL_INVOKE'
                             OR NEW.reconciles_execute_attempt_id IS NOT NULL)))) THEN
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
    IF NEW.state = 'WAITING_INPUT' THEN
        IF NEW."authorization" <> OLD."authorization"
           OR NEW.attempt <> OLD.attempt
           OR NEW.attempt_id <> OLD.attempt_id
           OR (NEW.attempt_call_kind, NEW.attempt_target_handle,
               NEW.attempt_request_fingerprint,
               NEW.attempt_invocation_snapshot_ref, NEW.attempt_call_data,
               NEW.reconciles_execute_attempt_id)
              IS DISTINCT FROM
              (OLD.attempt_call_kind, OLD.attempt_target_handle,
               OLD.attempt_request_fingerprint,
               OLD.attempt_invocation_snapshot_ref, OLD.attempt_call_data,
               OLD.reconciles_execute_attempt_id)
           OR NEW."authorization" NOT IN
                ('EXECUTE', 'RESUME_INPUT', 'FINALIZE_INPUT') THEN
            RAISE EXCEPTION 'input wait must preserve the producing Tool attempt';
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
        RAISE EXCEPTION 'terminal action must preserve its last authorization';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE public.nw_runtime_action_operations
    DROP CONSTRAINT ck_agent_runtime_action_operation_kind;
ALTER TABLE public.nw_runtime_action_operations
    ADD CONSTRAINT ck_agent_runtime_action_operation_kind CHECK (
        operation_kind IN
            ('CLAIM', 'AWAIT_INPUT', 'RESUME_INPUT', 'RENEW', 'START',
             'CONSUME', 'RESULT', 'RETRY', 'COMPLETE', 'FAIL',
             'OUTCOME_UNKNOWN', 'CANCEL_SETTLE'));

ALTER TABLE public.nw_runtime_action_operations
    DROP CONSTRAINT ck_agent_runtime_action_operation_command;
ALTER TABLE public.nw_runtime_action_operations
    ADD CONSTRAINT ck_agent_runtime_action_operation_command CHECK (
        (operation_kind = 'CLAIM'
         AND command_type =
            'com.skillforge.runtime.api.port.ActionLedgerPort$ClaimAction')
        OR (operation_kind = 'AWAIT_INPUT'
            AND command_type =
            'com.skillforge.runtime.api.port.ActionLedgerPort$AwaitToolInput')
        OR (operation_kind = 'RESUME_INPUT'
            AND command_type =
            'com.skillforge.runtime.api.port.ActionLedgerPort$ResumeToolInput')
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
            'com.skillforge.runtime.api.port.ActionLedgerPort$OutcomeUnknownAction')
        OR (operation_kind = 'CANCEL_SETTLE'
            AND command_type =
            'com.skillforge.runtime.adapter.jdbc.action.StoredCancellationCommand'));

ALTER TABLE public.nw_runtime_action_operations
    DROP CONSTRAINT ck_agent_runtime_action_operation_codec;
ALTER TABLE public.nw_runtime_action_operations
    ADD CONSTRAINT ck_agent_runtime_action_operation_codec CHECK (
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
                    ('CLAIMED', 'WAITING_INPUT', 'SUCCEEDED', 'FAILED',
                     'OUTCOME_UNKNOWN')
                 AND output_authorization IN
                    ('EXECUTE', 'RESUME_INPUT', 'RECONCILE',
                     'FINALIZE_INPUT', 'FINALIZE_KNOWN', 'FINALIZE_UNKNOWN')
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
        AND (audit_ref IS NULL OR btrim(audit_ref) <> ''));

ALTER TABLE public.nw_runtime_action_operations
    DROP CONSTRAINT ck_agent_runtime_action_operation_response;
ALTER TABLE public.nw_runtime_action_operations
    ADD CONSTRAINT ck_agent_runtime_action_operation_response CHECK (
        (operation_kind = 'CLAIM'
         AND response_kind IN
            ('EXECUTE', 'RESUME_INPUT', 'RECONCILE', 'FINALIZE_INPUT',
             'FINALIZE_KNOWN', 'FINALIZE_UNKNOWN', 'REPLAY',
             'AWAITING_INPUT', 'IN_PROGRESS', 'CONFLICT', 'REJECTED'))
        OR (operation_kind = 'AWAIT_INPUT'
            AND response_kind IN
                ('APPLIED', 'REPLAYED', 'CONFLICT', 'LEASE_LOST', 'REJECTED'))
        OR (operation_kind = 'RESUME_INPUT'
            AND response_kind IN
                ('RESUMED', 'REPLAYED', 'IN_PROGRESS', 'CONFLICT', 'REJECTED'))
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
                 'CONFLICT', 'LEASE_LOST', 'REJECTED'))
        OR (operation_kind = 'CANCEL_SETTLE' AND response_kind = 'SETTLED'));

ALTER TABLE public.nw_runtime_action_operations
    DROP CONSTRAINT ck_agent_runtime_action_operation_outputs;
ALTER TABLE public.nw_runtime_action_operations
    ADD CONSTRAINT ck_agent_runtime_action_operation_outputs CHECK (
        (operation_kind <> 'CANCEL_SETTLE' AND
         (NOT writes_action
          OR (output_action_revision IS NOT NULL
              AND output_action_state IS NOT NULL
              AND output_authorization IS NOT NULL
              AND output_action_fingerprint IS NOT NULL
              AND output_action_data IS NOT NULL))
         AND (NOT writes_action OR operation_kind IN
            ('CLAIM', 'AWAIT_INPUT', 'RESUME_INPUT', 'RENEW', 'RETRY',
             'COMPLETE', 'FAIL', 'OUTCOME_UNKNOWN'))
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
                    ('CLAIM', 'AWAIT_INPUT', 'RESUME_INPUT', 'RETRY',
                     'COMPLETE', 'FAIL', 'OUTCOME_UNKNOWN')
                  AND publication_source_revision = output_action_revision)))
        OR (operation_kind = 'CANCEL_SETTLE'
            AND writes_fact AND attempt_id IS NOT NULL
            AND btrim(attempt_id) <> ''
            AND NOT writes_action AND NOT writes_resolution_slot
            AND NOT writes_resolution AND NOT writes_permit
            AND NOT writes_permit_consumption AND NOT writes_audit
            AND NOT writes_publication
            AND output_action_revision IS NULL
            AND output_action_state IS NULL
            AND output_authorization IS NULL
            AND output_action_fingerprint IS NULL
            AND output_action_data IS NULL
            AND winner_fact_ref IS NULL
            AND winner_fact_fingerprint IS NULL
            AND winner_fact_data IS NULL AND audit_ref IS NULL
            AND publication_event_id IS NULL
            AND publication_source_revision IS NULL
            AND publication_fingerprint IS NULL
            AND publication_data IS NULL
            AND written_fact_ref IS NOT NULL
            AND btrim(written_fact_ref) <> ''
            AND written_fact_data IS NOT NULL
            AND btrim(written_fact_data) <> ''
            AND written_fact_fingerprint IS NOT NULL
            AND written_fact_fingerprint ~ '^[0-9a-f]{64}$'
            AND latest_fact_ref IS NOT NULL
            AND latest_fact_data IS NOT NULL
            AND latest_fact_fingerprint IS NOT NULL
            AND latest_fact_fingerprint ~ '^[0-9a-f]{64}$'
            AND response_fact_ref IS NOT NULL
            AND latest_fact_ref = written_fact_ref
            AND response_fact_ref = written_fact_ref
            AND latest_fact_data = written_fact_data
            AND latest_fact_fingerprint = written_fact_fingerprint));

ALTER TABLE public.nw_runtime_session_tool_operations
    DROP CONSTRAINT skillforge_runtime_session_tool_operations_command_kind_check;
ALTER TABLE public.nw_runtime_session_tool_operations
    ADD CONSTRAINT skillforge_runtime_session_tool_operations_command_kind_check
    CHECK (command_kind IN(
        'CLAIM', 'KNOWN_CLAIM', 'START', 'RESULT', 'RETRY_PENDING',
        'RETRY_CONFIRMED', 'AWAIT_INPUT', 'RESUME_INPUT', 'COMPLETE',
        'FAIL', 'UNKNOWN'));

CREATE TABLE public.nw_runtime_tool_input_waits (
    tenant_id text NOT NULL,
    action_id varchar(160) NOT NULL,
    invocation_id text NOT NULL,
    wait_revision bigint NOT NULL,
    pending_id text NOT NULL,
    wake_id text NOT NULL,
    input_required_fact_ref text NOT NULL,
    invocation_snapshot_ref text NOT NULL,
    operation_id varchar(160) NOT NULL,
    command_fingerprint varchar(64) NOT NULL,
    command_data text NOT NULL,
    wait_fingerprint varchar(64) NOT NULL,
    wait_data text NOT NULL,
    action_fingerprint varchar(64) NOT NULL,
    action_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    CONSTRAINT skillforge_runtime_tool_input_waits_pkey
        PRIMARY KEY (tenant_id, action_id, wait_revision),
    CONSTRAINT uq_agent_runtime_tool_input_wait_operation
        UNIQUE (tenant_id, operation_id),
    CONSTRAINT uq_agent_runtime_tool_input_wait_pending
        UNIQUE (tenant_id, action_id, pending_id),
    CONSTRAINT uq_agent_runtime_tool_input_wait_wake
        UNIQUE (tenant_id, action_id, wake_id),
    CONSTRAINT fk_agent_runtime_tool_input_wait_action
        FOREIGN KEY (tenant_id, action_id, invocation_id)
        REFERENCES public.agent_runtime_actions
            (tenant_id, action_id, invocation_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_agent_runtime_tool_input_wait_fact
        FOREIGN KEY (tenant_id, action_id, invocation_id,
                     input_required_fact_ref)
        REFERENCES public.agent_runtime_action_attempt_facts
            (tenant_id, action_id, invocation_id, fact_ref)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_tool_input_wait_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(action_id) <> ''
        AND btrim(invocation_id) <> '' AND wait_revision > 0
        AND btrim(pending_id) <> '' AND btrim(wake_id) <> ''
        AND pending_id <> wake_id
        AND btrim(input_required_fact_ref) <> ''
        AND btrim(invocation_snapshot_ref) <> ''
        AND btrim(operation_id) <> ''),
    CONSTRAINT ck_agent_runtime_tool_input_wait_codec CHECK (
        command_fingerprint ~ '^[0-9a-f]{64}$'
        AND wait_fingerprint ~ '^[0-9a-f]{64}$'
        AND action_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(command_data) <> '' AND btrim(wait_data) <> ''
        AND btrim(action_data) <> ''),
    CONSTRAINT ck_agent_runtime_tool_input_wait_time CHECK (
        created_nano BETWEEN 0 AND 999999999)
);

CREATE TABLE public.nw_runtime_session_tool_input_responses (
    tenant_id text NOT NULL,
    session_id text NOT NULL,
    agent_id text NOT NULL,
    end_user_id bigint NOT NULL,
    run_id varchar(160) NOT NULL,
    input_id varchar(160) NOT NULL,
    input_event_id varchar(160) NOT NULL,
    journal_ref text NOT NULL,
    action_id varchar(160) NOT NULL,
    invocation_id text NOT NULL,
    pending_id text NOT NULL,
    wake_id text NOT NULL,
    wait_revision bigint NOT NULL,
    request_fingerprint text NOT NULL,
    journal_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    CONSTRAINT skillforge_runtime_session_tool_input_responses_pkey
        PRIMARY KEY (tenant_id, journal_ref),
    CONSTRAINT uq_agent_runtime_session_tool_input_response_input
        UNIQUE (tenant_id, agent_id, session_id, input_id),
    CONSTRAINT uq_agent_runtime_session_tool_input_response_wait
        UNIQUE (tenant_id, action_id, wait_revision),
    CONSTRAINT fk_agent_runtime_session_tool_input_response_input
        FOREIGN KEY (tenant_id, agent_id, session_id, end_user_id, input_id)
        REFERENCES public.agent_runtime_input_ledger
            (tenant_id, agent_id, session_id, end_user_id, input_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_agent_runtime_session_tool_input_response_run
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES public.agent_runtime_runs (tenant_id, run_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_agent_runtime_session_tool_input_response_wait
        FOREIGN KEY (tenant_id, action_id, wait_revision)
        REFERENCES public.nw_runtime_tool_input_waits
            (tenant_id, action_id, wait_revision)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_agent_runtime_session_tool_input_response_action
        FOREIGN KEY (tenant_id, action_id, invocation_id)
        REFERENCES public.agent_runtime_actions
            (tenant_id, action_id, invocation_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_session_tool_input_response_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(session_id) <> ''
        AND btrim(agent_id) <> '' AND end_user_id > 0
        AND btrim(run_id) <> '' AND btrim(input_id) <> ''
        AND btrim(input_event_id) <> '' AND btrim(journal_ref) <> ''
        AND btrim(action_id) <> '' AND btrim(invocation_id) <> ''
        AND btrim(pending_id) <> '' AND btrim(wake_id) <> ''
        AND pending_id <> wake_id AND wait_revision > 0
        AND btrim(request_fingerprint) <> ''),
    CONSTRAINT ck_agent_runtime_session_tool_input_response_codec CHECK (
        btrim(journal_data) <> ''),
    CONSTRAINT ck_agent_runtime_session_tool_input_response_time CHECK (
        created_nano BETWEEN 0 AND 999999999)
);

CREATE TABLE public.nw_runtime_tool_input_resumes (
    tenant_id text NOT NULL,
    action_id varchar(160) NOT NULL,
    invocation_id text NOT NULL,
    wait_revision bigint NOT NULL,
    response_journal_ref text NOT NULL,
    resume_operation_id text NOT NULL,
    attempt_id text NOT NULL,
    command_fingerprint varchar(64) NOT NULL,
    command_data text NOT NULL,
    journal_fingerprint varchar(64) NOT NULL,
    journal_data text NOT NULL,
    action_fingerprint varchar(64) NOT NULL,
    action_data text NOT NULL,
    created_epoch_second bigint NOT NULL,
    created_nano integer NOT NULL,
    CONSTRAINT skillforge_runtime_tool_input_resumes_pkey
        PRIMARY KEY (tenant_id, action_id, wait_revision),
    CONSTRAINT uq_agent_runtime_tool_input_resume_journal
        UNIQUE (tenant_id, response_journal_ref),
    CONSTRAINT uq_agent_runtime_tool_input_resume_operation
        UNIQUE (tenant_id, resume_operation_id),
    CONSTRAINT uq_agent_runtime_tool_input_resume_attempt
        UNIQUE (tenant_id, action_id, attempt_id),
    CONSTRAINT fk_agent_runtime_tool_input_resume_wait
        FOREIGN KEY (tenant_id, action_id, wait_revision)
        REFERENCES public.nw_runtime_tool_input_waits
            (tenant_id, action_id, wait_revision)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_agent_runtime_tool_input_resume_response
        FOREIGN KEY (tenant_id, response_journal_ref)
        REFERENCES public.nw_runtime_session_tool_input_responses
            (tenant_id, journal_ref)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_agent_runtime_tool_input_resume_action
        FOREIGN KEY (tenant_id, action_id, invocation_id)
        REFERENCES public.agent_runtime_actions
            (tenant_id, action_id, invocation_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_agent_runtime_tool_input_resume_identity CHECK (
        btrim(tenant_id) <> '' AND btrim(action_id) <> ''
        AND btrim(invocation_id) <> '' AND wait_revision > 0
        AND btrim(response_journal_ref) <> ''
        AND btrim(resume_operation_id) <> '' AND btrim(attempt_id) <> ''),
    CONSTRAINT ck_agent_runtime_tool_input_resume_codec CHECK (
        command_fingerprint ~ '^[0-9a-f]{64}$'
        AND journal_fingerprint ~ '^[0-9a-f]{64}$'
        AND action_fingerprint ~ '^[0-9a-f]{64}$'
        AND btrim(command_data) <> '' AND btrim(journal_data) <> ''
        AND btrim(action_data) <> ''),
    CONSTRAINT ck_agent_runtime_tool_input_resume_time CHECK (
        created_nano BETWEEN 0 AND 999999999)
);

ALTER TABLE public.nw_runtime_tool_input_waits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_tool_input_waits FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_tool_input_waits
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE public.nw_runtime_session_tool_input_responses
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_session_tool_input_responses
    FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation
    ON public.nw_runtime_session_tool_input_responses
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
ALTER TABLE public.nw_runtime_tool_input_resumes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_tool_input_resumes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_tool_input_resumes
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

CREATE FUNCTION public.agent_runtime_tool_input_append_only()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent runtime Tool input journal is append-only';
END;
$$;

CREATE TRIGGER agent_runtime_tool_input_wait_immutable
    BEFORE UPDATE OR DELETE ON public.nw_runtime_tool_input_waits
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_tool_input_append_only();
CREATE TRIGGER agent_runtime_session_tool_input_response_immutable
    BEFORE UPDATE OR DELETE ON public.nw_runtime_session_tool_input_responses
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_tool_input_append_only();
CREATE TRIGGER agent_runtime_tool_input_resume_immutable
    BEFORE UPDATE OR DELETE ON public.nw_runtime_tool_input_resumes
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_tool_input_append_only();

CREATE FUNCTION public.assert_agent_runtime_tool_input_wait_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_actions a
          JOIN public.agent_runtime_action_attempt_facts f
            ON f.tenant_id = a.tenant_id AND f.action_id = a.action_id
           AND f.invocation_id = a.invocation_id
           AND f.fact_ref = NEW.input_required_fact_ref
          JOIN public.nw_runtime_action_operations o
            ON o.tenant_id = a.tenant_id AND o.action_id = a.action_id
           AND o.invocation_id = a.invocation_id
           AND o.attempt_id = f.attempt_id
           AND o.operation_kind = 'AWAIT_INPUT'
           AND o.original_operation_id = NEW.operation_id
          JOIN public.agent_runtime_action_state_publications p
            ON p.tenant_id = o.tenant_id AND p.source_id = o.action_id
           AND p.event_id = o.publication_event_id
           AND p.source_revision = o.publication_source_revision
         WHERE a.tenant_id = NEW.tenant_id
           AND a.action_id = NEW.action_id
           AND a.invocation_id = NEW.invocation_id
           AND a.state = 'WAITING_INPUT'
           AND a.record_data = NEW.action_data
           AND f.attempt_id = a.attempt_id
           AND f.status = 'INPUT_REQUIRED'
           AND f.outcome_resolution = 'INPUT_REQUIRED'
           AND f.resolution_key IS NULL
           AND o.command_fingerprint = NEW.command_fingerprint
           AND o.command_data = NEW.command_data
           AND o.response_kind = 'APPLIED'
           AND o.response_fact_ref = f.fact_ref
           AND o.latest_fact_ref = f.fact_ref
           AND o.output_action_revision = a.action_revision
           AND o.output_action_state = 'WAITING_INPUT'
           AND o.output_authorization = a."authorization"
           AND o.output_action_fingerprint = NEW.action_fingerprint
           AND o.output_action_data = NEW.action_data
           AND o.expected_action_revision + 1 = o.output_action_revision
           AND o.writes_action AND o.writes_publication
           AND NOT o.writes_fact AND NOT o.writes_resolution_slot
           AND NOT o.writes_resolution AND NOT o.writes_permit
           AND NOT o.writes_permit_consumption AND NOT o.writes_audit) THEN
        RAISE EXCEPTION 'Tool input wait sources are not transactionally closed';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_tool_input_wait_closed
    AFTER INSERT ON public.nw_runtime_tool_input_waits
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_tool_input_wait_closed();

CREATE FUNCTION public.assert_agent_runtime_session_tool_input_response_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.agent_runtime_input_ledger i
          JOIN public.agent_runtime_session_inbox inbox
            ON inbox.tenant_id = i.tenant_id
           AND inbox.agent_id = i.agent_id
           AND inbox.session_id = i.session_id
           AND inbox.end_user_id = i.end_user_id
           AND inbox.input_id = i.input_id
          JOIN public.agent_runtime_runs r
            ON r.tenant_id = i.tenant_id AND r.run_id = i.run_id
           AND r.agent_id = i.agent_id AND r.session_id = i.session_id
           AND r.end_user_id = i.end_user_id
          JOIN public.nw_runtime_tool_input_waits w
            ON w.tenant_id = i.tenant_id AND w.action_id = NEW.action_id
           AND w.invocation_id = NEW.invocation_id
           AND w.wait_revision = NEW.wait_revision
           AND w.pending_id = NEW.pending_id AND w.wake_id = NEW.wake_id
          JOIN public.agent_runtime_actions a
            ON a.tenant_id = w.tenant_id AND a.action_id = w.action_id
           AND a.invocation_id = w.invocation_id
           AND a.agent_id = i.agent_id AND a.session_id = i.session_id
           AND a.end_user_id = i.end_user_id AND a.run_id = i.run_id
         WHERE i.tenant_id = NEW.tenant_id
           AND i.agent_id = NEW.agent_id
           AND i.session_id = NEW.session_id
           AND i.end_user_id = NEW.end_user_id
           AND i.input_id = NEW.input_id AND i.run_id = NEW.run_id
           AND i.request_fingerprint = NEW.request_fingerprint
           AND i.decision_kind = 'ACCEPTED_EXISTING_RUN'
           AND i.receipt_status = 'ACCEPTED'
           AND inbox.status = 'PROCESSING'
           AND a.state = 'WAITING_INPUT'
           AND a.record_data = w.action_data) THEN
        RAISE EXCEPTION 'Session Tool input response sources are not closed';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_session_tool_input_response_closed
    AFTER INSERT ON public.nw_runtime_session_tool_input_responses
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_session_tool_input_response_closed();

CREATE FUNCTION public.assert_agent_runtime_tool_input_resume_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.nw_runtime_tool_input_waits w
          JOIN public.nw_runtime_session_tool_input_responses response
            ON response.tenant_id = w.tenant_id
           AND response.action_id = w.action_id
           AND response.invocation_id = w.invocation_id
           AND response.wait_revision = w.wait_revision
           AND response.journal_ref = NEW.response_journal_ref
          JOIN public.agent_runtime_actions a
            ON a.tenant_id = w.tenant_id AND a.action_id = w.action_id
           AND a.invocation_id = w.invocation_id
          JOIN public.nw_runtime_action_operations o
            ON o.tenant_id = a.tenant_id AND o.action_id = a.action_id
           AND o.invocation_id = a.invocation_id
           AND o.attempt_id = NEW.attempt_id
           AND o.operation_kind = 'RESUME_INPUT'
           AND o.original_operation_id = NEW.resume_operation_id
          JOIN public.agent_runtime_action_state_publications p
            ON p.tenant_id = o.tenant_id AND p.source_id = o.action_id
           AND p.event_id = o.publication_event_id
           AND p.source_revision = o.publication_source_revision
         WHERE w.tenant_id = NEW.tenant_id
           AND w.action_id = NEW.action_id
           AND w.invocation_id = NEW.invocation_id
           AND w.wait_revision = NEW.wait_revision
            AND a.state = 'CLAIMED'
           AND a."authorization" = 'RESUME_INPUT'
           AND a.attempt_id = NEW.attempt_id
           AND a.record_data = NEW.action_data
           AND o.command_fingerprint = NEW.command_fingerprint
           AND o.command_data = NEW.command_data
           AND o.response_kind = 'RESUMED'
           AND o.output_action_revision = a.action_revision
           AND o.output_action_state = 'CLAIMED'
           AND o.output_authorization = 'RESUME_INPUT'
           AND o.output_action_fingerprint = NEW.action_fingerprint
           AND o.output_action_data = NEW.action_data
           AND o.writes_action AND o.writes_publication
           AND NOT o.writes_fact AND NOT o.writes_resolution_slot
           AND NOT o.writes_resolution AND NOT o.writes_permit
           AND NOT o.writes_permit_consumption AND NOT o.writes_audit) THEN
        RAISE EXCEPTION 'Tool input resume sources are not transactionally closed';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_tool_input_resume_closed
    AFTER INSERT ON public.nw_runtime_tool_input_resumes
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_tool_input_resume_closed();

CREATE FUNCTION public.assert_agent_runtime_tool_input_operation_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.operation_kind = 'AWAIT_INPUT' AND NOT EXISTS (
        SELECT 1 FROM public.nw_runtime_tool_input_waits w
         WHERE w.tenant_id = NEW.tenant_id
           AND w.action_id = NEW.action_id
           AND w.invocation_id = NEW.invocation_id
           AND w.operation_id = NEW.original_operation_id
           AND w.command_fingerprint = NEW.command_fingerprint
           AND w.command_data = NEW.command_data
           AND w.action_fingerprint = NEW.output_action_fingerprint
           AND w.action_data = NEW.output_action_data) THEN
        RAISE EXCEPTION 'AWAIT_INPUT operation requires its Tool input wait';
    END IF;
    IF NEW.operation_kind = 'RESUME_INPUT' AND NOT EXISTS (
        SELECT 1 FROM public.nw_runtime_tool_input_resumes r
         WHERE r.tenant_id = NEW.tenant_id
           AND r.action_id = NEW.action_id
           AND r.invocation_id = NEW.invocation_id
           AND r.attempt_id = NEW.attempt_id
           AND r.resume_operation_id = NEW.original_operation_id
           AND r.command_fingerprint = NEW.command_fingerprint
           AND r.command_data = NEW.command_data
           AND r.action_fingerprint = NEW.output_action_fingerprint
           AND r.action_data = NEW.output_action_data) THEN
        RAISE EXCEPTION 'RESUME_INPUT operation requires its continuation journal';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_runtime_tool_input_operation_closed
    AFTER INSERT ON public.nw_runtime_action_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.assert_agent_runtime_tool_input_operation_closed();

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_callback_action_binding()
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
           AND a."authorization" IN ('EXECUTE', 'RESUME_INPUT')
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

COMMENT ON TABLE public.nw_runtime_tool_input_waits IS
    '每轮 Tool input_required 的不可变等待事实；wait revision 独立于 Action revision';
COMMENT ON COLUMN public.nw_runtime_tool_input_waits.wait_revision IS
    '同一逻辑 Action 的单调等待轮次，不是 Action revision';
COMMENT ON COLUMN public.nw_runtime_tool_input_waits.input_required_fact_ref IS
    '产生本轮等待且不进入 first-winner 的 INPUT_REQUIRED attempt 事实';
COMMENT ON COLUMN public.nw_runtime_tool_input_waits.invocation_snapshot_ref IS
    '原 Tool 调用快照引用；continuation 必须基于它派生';
COMMENT ON COLUMN public.nw_runtime_tool_input_waits.operation_id IS
    '原子进入 WAITING_INPUT 的 AWAIT_INPUT operation 身份';
COMMENT ON COLUMN public.nw_runtime_tool_input_waits.wait_data IS
    '完整 ToolInputWait 私有规范编码';
COMMENT ON TABLE public.nw_runtime_session_tool_input_responses IS
    '从已接受 ExistingRun Session Inbox 冻结的 Tool input response 权威 journal';
COMMENT ON COLUMN public.nw_runtime_session_tool_input_responses.journal_ref IS
    'ResumeToolInput 唯一可提交的响应引用；调用方不得直接提交响应正文';
COMMENT ON COLUMN public.nw_runtime_session_tool_input_responses.request_fingerprint IS
    '原已接受输入与 Inbox 的请求指纹';
COMMENT ON COLUMN public.nw_runtime_session_tool_input_responses.journal_data IS
    '完整 ToolInputResponseJournal 私有规范编码';
COMMENT ON TABLE public.nw_runtime_tool_input_resumes IS
    '每轮已接受 Session 响应领取新 continuation attempt 的不可变 operation journal';
COMMENT ON COLUMN public.nw_runtime_tool_input_resumes.wait_revision IS
    '被当前 continuation 精确消费的 Tool 等待轮次';
COMMENT ON COLUMN public.nw_runtime_tool_input_resumes.response_journal_ref IS
    '权威 Session Tool input response journal 引用';
COMMENT ON COLUMN public.nw_runtime_tool_input_resumes.attempt_id IS
    '本轮 continuation 新签发且不得复用的 attempt 身份';
COMMENT ON FUNCTION public.agent_runtime_tool_input_append_only() IS
    '禁止改写或删除 Tool input wait、Session response 与 continuation journal';
COMMENT ON FUNCTION public.assert_agent_runtime_tool_input_wait_closed() IS
    '要求 WAITING_INPUT Action、INPUT_REQUIRED fact、operation 与 publication 同事务闭合';
COMMENT ON FUNCTION public.assert_agent_runtime_session_tool_input_response_closed() IS
    '要求响应来自当前 WAITING_INPUT Action 的已接受 ExistingRun Inbox 输入';
COMMENT ON FUNCTION public.assert_agent_runtime_tool_input_resume_closed() IS
    '要求 response journal、continuation claim、operation 与 publication 同事务闭合';
COMMENT ON FUNCTION public.assert_agent_runtime_tool_input_operation_closed() IS
    '禁止 AWAIT_INPUT 或 RESUME_INPUT operation 脱离专用不可变 journal';
COMMENT ON FUNCTION public.assert_agent_runtime_callback_action_binding() IS
    'Action callback 只可绑定持有 live EXECUTE 或 RESUME_INPUT lease 的 Tool Action';
COMMENT ON COLUMN public.nw_runtime_action_operations.operation_kind IS
    'CLAIM/AWAIT_INPUT/RESUME_INPUT/RENEW/START/CONSUME/RESULT/RETRY/COMPLETE/FAIL/OUTCOME_UNKNOWN/CANCEL_SETTLE';
COMMENT ON COLUMN public.nw_runtime_session_tool_operations.command_kind IS
    'Tool claim、attempt、input wait/resume、retry 与 terminal 原命令类别';

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_action_attempt_chain()
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
                ('NOT_DISPATCHED', 'INPUT_REQUIRED', 'SUCCEEDED',
                 'FAILED', 'UNRESOLVED'))
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
