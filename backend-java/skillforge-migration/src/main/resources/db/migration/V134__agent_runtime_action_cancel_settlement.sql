-- 取消结算是 JDBC 私有 fact 操作，不产生执行许可、业务结果或状态 publication。
ALTER TABLE public.nw_runtime_action_operations
 ADD COLUMN cancel_run_id varchar(160),
 ADD COLUMN cancel_request_id varchar(160),
 ADD COLUMN cancel_revision bigint,
 ADD COLUMN cancel_mutation_key text,
 ADD COLUMN cancel_source_fingerprint varchar(64),
 ADD CONSTRAINT agent_runtime_action_cancel_source_fkey
   FOREIGN KEY(tenant_id,cancel_run_id,cancel_mutation_key)
   REFERENCES public.agent_runtime_run_mutations(tenant_id,run_id,mutation_key)
   DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.nw_runtime_action_operations DROP CONSTRAINT ck_agent_runtime_action_operation_kind;
ALTER TABLE public.nw_runtime_action_operations ADD CONSTRAINT ck_agent_runtime_action_operation_kind CHECK ((operation_kind<>'CANCEL_SETTLE' AND (
        operation_kind IN
            ('CLAIM', 'RENEW', 'START', 'CONSUME', 'RESULT', 'RETRY',
             'COMPLETE', 'FAIL', 'OUTCOME_UNKNOWN'))) OR (operation_kind='CANCEL_SETTLE'));
ALTER TABLE public.nw_runtime_action_operations DROP CONSTRAINT ck_agent_runtime_action_operation_command;
ALTER TABLE public.nw_runtime_action_operations ADD CONSTRAINT ck_agent_runtime_action_operation_command CHECK ((operation_kind<>'CANCEL_SETTLE' AND (
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
            'com.skillforge.runtime.api.port.ActionLedgerPort$OutcomeUnknownAction'))) OR (operation_kind='CANCEL_SETTLE' AND command_type='com.skillforge.runtime.adapter.jdbc.action.StoredCancellationCommand'));
ALTER TABLE public.nw_runtime_action_operations DROP CONSTRAINT ck_agent_runtime_action_operation_response;
ALTER TABLE public.nw_runtime_action_operations ADD CONSTRAINT ck_agent_runtime_action_operation_response CHECK ((operation_kind<>'CANCEL_SETTLE' AND (
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
                 'CONFLICT', 'LEASE_LOST', 'REJECTED')))) OR (operation_kind='CANCEL_SETTLE' AND response_kind='SETTLED'));
ALTER TABLE public.nw_runtime_action_operations DROP CONSTRAINT ck_agent_runtime_action_operation_outputs;
ALTER TABLE public.nw_runtime_action_operations ADD CONSTRAINT ck_agent_runtime_action_operation_outputs CHECK ((operation_kind<>'CANCEL_SETTLE' AND (
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
                 AND publication_source_revision = output_action_revision)))) OR (operation_kind='CANCEL_SETTLE' AND writes_fact AND attempt_id IS NOT NULL AND btrim(attempt_id)<>''
 AND NOT writes_action AND NOT writes_resolution_slot AND NOT writes_resolution
 AND NOT writes_permit AND NOT writes_permit_consumption AND NOT writes_audit AND NOT writes_publication
 AND output_action_revision IS NULL AND output_action_state IS NULL AND output_authorization IS NULL
 AND output_action_fingerprint IS NULL AND output_action_data IS NULL
 AND winner_fact_ref IS NULL AND winner_fact_fingerprint IS NULL AND winner_fact_data IS NULL AND audit_ref IS NULL
 AND publication_event_id IS NULL AND publication_source_revision IS NULL AND publication_fingerprint IS NULL AND publication_data IS NULL
 AND written_fact_ref IS NOT NULL AND btrim(written_fact_ref)<>''
 AND written_fact_data IS NOT NULL AND btrim(written_fact_data)<>''
 AND written_fact_fingerprint IS NOT NULL AND written_fact_fingerprint ~ '^[0-9a-f]{64}$'
 AND latest_fact_ref IS NOT NULL AND latest_fact_data IS NOT NULL AND latest_fact_fingerprint IS NOT NULL
 AND latest_fact_fingerprint ~ '^[0-9a-f]{64}$' AND response_fact_ref IS NOT NULL
 AND latest_fact_ref=written_fact_ref AND response_fact_ref=written_fact_ref
 AND latest_fact_data=written_fact_data AND latest_fact_fingerprint=written_fact_fingerprint));

ALTER TABLE public.nw_runtime_action_operations ADD CONSTRAINT ck_agent_runtime_action_cancel_source CHECK (
 (operation_kind='CANCEL_SETTLE' AND cancel_run_id IS NOT NULL AND btrim(cancel_run_id)<>''
  AND cancel_request_id IS NOT NULL AND btrim(cancel_request_id)<>'' AND cancel_revision IS NOT NULL AND cancel_revision>0
  AND cancel_mutation_key IS NOT NULL AND btrim(cancel_mutation_key)<>''
  AND cancel_source_fingerprint IS NOT NULL AND cancel_source_fingerprint ~ '^[0-9a-f]{64}$')
 OR (operation_kind<>'CANCEL_SETTLE' AND cancel_run_id IS NULL AND cancel_request_id IS NULL
  AND cancel_revision IS NULL AND cancel_mutation_key IS NULL AND cancel_source_fingerprint IS NULL));

CREATE FUNCTION public.assert_agent_runtime_action_cancel_closed()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.operation_kind='CANCEL_SETTLE' AND NOT EXISTS (
   SELECT 1 FROM public.agent_runtime_actions a
   JOIN public.agent_runtime_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id
     AND r.session_id=a.session_id AND r.agent_id=a.agent_id AND r.end_user_id=a.end_user_id
     AND r.causation_input_id=a.causation_input_id
   JOIN public.agent_runtime_run_mutations m ON m.tenant_id=r.tenant_id AND m.run_id=r.run_id
     AND m.session_id=r.session_id AND m.mutation_kind='CANCEL'
   JOIN public.nw_runtime_action_permit_consumptions p ON p.tenant_id=a.tenant_id AND p.action_id=a.action_id
     AND p.invocation_id=a.invocation_id AND p.attempt_id=NEW.attempt_id AND p.status='ISSUED'
   JOIN public.agent_runtime_action_attempt_facts started ON started.tenant_id=p.tenant_id AND started.fact_ref=p.started_fact_ref
     AND started.action_id=p.action_id AND started.attempt_id=p.attempt_id AND started.invocation_id=p.invocation_id
     AND started.status='STARTED' AND started.fact_data=p.started_fact_data
   JOIN public.agent_runtime_action_attempt_facts closed ON closed.tenant_id=p.tenant_id AND closed.fact_ref=NEW.written_fact_ref
     AND closed.action_id=p.action_id AND closed.attempt_id=p.attempt_id AND closed.invocation_id=p.invocation_id
     AND closed.status='NOT_DISPATCHED' AND closed.evidence_revision=started.evidence_revision+1
   WHERE a.tenant_id=NEW.tenant_id AND a.action_id=NEW.action_id AND a.invocation_id=NEW.invocation_id
     AND r.run_id=NEW.cancel_run_id AND r.cancel_request_id=NEW.cancel_request_id
     AND r.cancel_requested_epoch_second IS NOT NULL AND r.cancel_requested_nano IS NOT NULL
     AND m.mutation_key=NEW.cancel_mutation_key AND m.output_revision=NEW.cancel_revision
     AND m.output_revision<=r.run_revision AND m.output_fingerprint=NEW.cancel_source_fingerprint
 ) THEN RAISE EXCEPTION 'action cancellation settlement requires original cancel journal and issued proof'; END IF;
 RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER agent_runtime_action_cancel_closed
 AFTER INSERT ON public.nw_runtime_action_operations DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION public.assert_agent_runtime_action_cancel_closed();
COMMENT ON COLUMN public.nw_runtime_action_operations.operation_kind IS 'CLAIM/RENEW/START/CONSUME/RESULT/RETRY/COMPLETE/FAIL/OUTCOME_UNKNOWN/CANCEL_SETTLE';
