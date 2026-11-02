-- S07 R5: fixed Runtime Session private storage retirement.
-- Owner/Session roots, create/CLOSED CAS and scalar/FK identities remain authoritative and hot.
-- Only new/explicitly rebuilt schemas; this migration does not operate on an existing deployment.
-- Exact text/bytea is preserved; cold copies do not imply net database or filesystem reduction.
CREATE FUNCTION public.agent_runtime_session_archive_text_digest(value text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT SECURITY INVOKER AS $$
 SELECT encode(sha256(convert_to('skillforge.runtime.session.private-archive.text.v1','UTF8')
     ||decode('00','hex')||convert_to(value,'UTF8')),'hex')
$$;
CREATE FUNCTION public.agent_runtime_session_archive_marker(value text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT SECURITY INVOKER AS $$
 SELECT '{"$runtimeSessionArchive":"v1","sha256":"'||
     public.agent_runtime_session_archive_text_digest(value)||'"}'
$$;
CREATE FUNCTION public.agent_runtime_session_archive_is_marker(value text) RETURNS boolean
LANGUAGE sql IMMUTABLE SECURITY INVOKER AS $$
 SELECT coalesce(starts_with(value,'{"$runtimeSessionArchive":"v1","sha256":"'),false)
$$;

CREATE TABLE public.agent_runtime_input_ledger_archives (
 LIKE public.agent_runtime_input_ledger INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES,
 owner_session_id text NOT NULL,
 owner_target_kind text NOT NULL,
 owner_target_id text NOT NULL,
 owner_end_user_id bigint NOT NULL CHECK(owner_end_user_id>0),
 source_session_revision bigint NOT NULL CHECK(source_session_revision>0),
 closed_epoch_second bigint NOT NULL,
 closed_nano integer NOT NULL CHECK(closed_nano BETWEEN 0 AND 999999999),
 source_run_id text,
 source_run_revision bigint,
 source_run_updated_epoch_second bigint,
 source_run_updated_nano integer,
 original_row_sha256 text NOT NULL CHECK(original_row_sha256 ~ '^[a-f0-9]{64}$'),
 archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 command_data_sha256 text NOT NULL CHECK(command_data_sha256 ~ '^[a-f0-9]{64}$'),
 receipt_data_sha256 text NOT NULL CHECK(receipt_data_sha256 ~ '^[a-f0-9]{64}$')
 ,
 FOREIGN KEY(tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) ON DELETE RESTRICT
);
CREATE INDEX input_ledger_archives__owner_idx ON public.agent_runtime_input_ledger_archives
 (tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id);
ALTER TABLE public.agent_runtime_input_ledger_archives ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_input_ledger_archives FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_input_ledger_archives
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER archive_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_input_ledger_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_input_ledger_archives IS '固定来源agent_runtime_input_ledger的精确冷存储；原标量键及关闭源事实可校验，复制不等于退役';

CREATE TABLE public.agent_runtime_session_admission_prepare_archives (
 LIKE public.agent_runtime_session_admission_prepares INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES,
 owner_session_id text NOT NULL,
 owner_target_kind text NOT NULL,
 owner_target_id text NOT NULL,
 owner_end_user_id bigint NOT NULL CHECK(owner_end_user_id>0),
 source_session_revision bigint NOT NULL CHECK(source_session_revision>0),
 closed_epoch_second bigint NOT NULL,
 closed_nano integer NOT NULL CHECK(closed_nano BETWEEN 0 AND 999999999),
 source_run_id text,
 source_run_revision bigint,
 source_run_updated_epoch_second bigint,
 source_run_updated_nano integer,
 original_row_sha256 text NOT NULL CHECK(original_row_sha256 ~ '^[a-f0-9]{64}$'),
 archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 command_data_sha256 text NOT NULL CHECK(command_data_sha256 ~ '^[a-f0-9]{64}$')
 ,
 FOREIGN KEY(tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) ON DELETE RESTRICT
);
CREATE INDEX session_admission_prepare_archives__owner_idx ON public.agent_runtime_session_admission_prepare_archives
 (tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id);
ALTER TABLE public.agent_runtime_session_admission_prepare_archives ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_admission_prepare_archives FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_admission_prepare_archives
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER archive_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_session_admission_prepare_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_session_admission_prepare_archives IS '固定来源agent_runtime_session_admission_prepares的精确冷存储；原标量键及关闭源事实可校验，复制不等于退役';

CREATE TABLE public.agent_runtime_session_commit_journal_archives (
 LIKE public.agent_runtime_session_commit_journals INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES,
 owner_session_id text NOT NULL,
 owner_target_kind text NOT NULL,
 owner_target_id text NOT NULL,
 owner_end_user_id bigint NOT NULL CHECK(owner_end_user_id>0),
 source_session_revision bigint NOT NULL CHECK(source_session_revision>0),
 closed_epoch_second bigint NOT NULL,
 closed_nano integer NOT NULL CHECK(closed_nano BETWEEN 0 AND 999999999),
 source_run_id text,
 source_run_revision bigint,
 source_run_updated_epoch_second bigint,
 source_run_updated_nano integer,
 original_row_sha256 text NOT NULL CHECK(original_row_sha256 ~ '^[a-f0-9]{64}$'),
 archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 command_data_sha256 text NOT NULL CHECK(command_data_sha256 ~ '^[a-f0-9]{64}$')
 ,
 FOREIGN KEY(tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) ON DELETE RESTRICT
);
CREATE INDEX session_commit_journal_archives__owner_idx ON public.agent_runtime_session_commit_journal_archives
 (tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id);
ALTER TABLE public.agent_runtime_session_commit_journal_archives ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_commit_journal_archives FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_commit_journal_archives
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER archive_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_session_commit_journal_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_session_commit_journal_archives IS '固定来源agent_runtime_session_commit_journals的精确冷存储；原标量键及关闭源事实可校验，复制不等于退役';

CREATE TABLE public.agent_runtime_run_record_archives (
 LIKE public.agent_runtime_runs INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES,
 owner_session_id text NOT NULL,
 owner_target_kind text NOT NULL,
 owner_target_id text NOT NULL,
 owner_end_user_id bigint NOT NULL CHECK(owner_end_user_id>0),
 source_session_revision bigint NOT NULL CHECK(source_session_revision>0),
 closed_epoch_second bigint NOT NULL,
 closed_nano integer NOT NULL CHECK(closed_nano BETWEEN 0 AND 999999999),
 source_run_id text,
 source_run_revision bigint,
 source_run_updated_epoch_second bigint,
 source_run_updated_nano integer,
 original_row_sha256 text NOT NULL CHECK(original_row_sha256 ~ '^[a-f0-9]{64}$'),
 archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 record_data_sha256 text NOT NULL CHECK(record_data_sha256 ~ '^[a-f0-9]{64}$')
 ,
 FOREIGN KEY(tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) ON DELETE RESTRICT
);
CREATE INDEX run_record_archives__owner_idx ON public.agent_runtime_run_record_archives
 (tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id);
ALTER TABLE public.agent_runtime_run_record_archives ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_run_record_archives FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_run_record_archives
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER archive_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_run_record_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_run_record_archives IS '固定来源agent_runtime_runs的精确冷存储；原标量键及关闭源事实可校验，复制不等于退役';

CREATE TABLE public.agent_runtime_session_inbox_archives (
 LIKE public.agent_runtime_session_inbox INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES,
 owner_session_id text NOT NULL,
 owner_target_kind text NOT NULL,
 owner_target_id text NOT NULL,
 owner_end_user_id bigint NOT NULL CHECK(owner_end_user_id>0),
 source_session_revision bigint NOT NULL CHECK(source_session_revision>0),
 closed_epoch_second bigint NOT NULL,
 closed_nano integer NOT NULL CHECK(closed_nano BETWEEN 0 AND 999999999),
 source_run_id text,
 source_run_revision bigint,
 source_run_updated_epoch_second bigint,
 source_run_updated_nano integer,
 original_row_sha256 text NOT NULL CHECK(original_row_sha256 ~ '^[a-f0-9]{64}$'),
 archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 entry_data_sha256 text NOT NULL CHECK(entry_data_sha256 ~ '^[a-f0-9]{64}$')
 ,
 FOREIGN KEY(tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) ON DELETE RESTRICT
);
CREATE INDEX session_inbox_archives__owner_idx ON public.agent_runtime_session_inbox_archives
 (tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id);
ALTER TABLE public.agent_runtime_session_inbox_archives ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_inbox_archives FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_inbox_archives
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER archive_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_session_inbox_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_session_inbox_archives IS '固定来源agent_runtime_session_inbox的精确冷存储；原标量键及关闭源事实可校验，复制不等于退役';

CREATE TABLE public.agent_runtime_run_mutation_archives (
 LIKE public.agent_runtime_run_mutations INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES,
 owner_session_id text NOT NULL,
 owner_target_kind text NOT NULL,
 owner_target_id text NOT NULL,
 owner_end_user_id bigint NOT NULL CHECK(owner_end_user_id>0),
 source_session_revision bigint NOT NULL CHECK(source_session_revision>0),
 closed_epoch_second bigint NOT NULL,
 closed_nano integer NOT NULL CHECK(closed_nano BETWEEN 0 AND 999999999),
 source_run_id text,
 source_run_revision bigint,
 source_run_updated_epoch_second bigint,
 source_run_updated_nano integer,
 original_row_sha256 text NOT NULL CHECK(original_row_sha256 ~ '^[a-f0-9]{64}$'),
 archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 output_data_sha256 text NOT NULL CHECK(output_data_sha256 ~ '^[a-f0-9]{64}$')
 ,
 FOREIGN KEY(tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) ON DELETE RESTRICT
);
CREATE INDEX run_mutation_archives__owner_idx ON public.agent_runtime_run_mutation_archives
 (tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id);
ALTER TABLE public.agent_runtime_run_mutation_archives ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_run_mutation_archives FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_run_mutation_archives
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER archive_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_run_mutation_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_run_mutation_archives IS '固定来源agent_runtime_run_mutations的精确冷存储；原标量键及关闭源事实可校验，复制不等于退役';

CREATE TABLE public.agent_runtime_session_inbox_mutation_archives (
 LIKE public.agent_runtime_inbox_mutations INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES,
 owner_session_id text NOT NULL,
 owner_target_kind text NOT NULL,
 owner_target_id text NOT NULL,
 owner_end_user_id bigint NOT NULL CHECK(owner_end_user_id>0),
 source_session_revision bigint NOT NULL CHECK(source_session_revision>0),
 closed_epoch_second bigint NOT NULL,
 closed_nano integer NOT NULL CHECK(closed_nano BETWEEN 0 AND 999999999),
 source_run_id text,
 source_run_revision bigint,
 source_run_updated_epoch_second bigint,
 source_run_updated_nano integer,
 original_row_sha256 text NOT NULL CHECK(original_row_sha256 ~ '^[a-f0-9]{64}$'),
 archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 output_data_sha256 text NOT NULL CHECK(output_data_sha256 ~ '^[a-f0-9]{64}$')
 ,
 FOREIGN KEY(tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) ON DELETE RESTRICT
);
CREATE INDEX session_inbox_mutation_archives__owner_idx ON public.agent_runtime_session_inbox_mutation_archives
 (tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id);
ALTER TABLE public.agent_runtime_session_inbox_mutation_archives ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_inbox_mutation_archives FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_inbox_mutation_archives
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER archive_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_session_inbox_mutation_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_session_inbox_mutation_archives IS '固定来源agent_runtime_inbox_mutations的精确冷存储；原标量键及关闭源事实可校验，复制不等于退役';

CREATE TABLE public.agent_runtime_run_publication_mutation_archives (
 LIKE public.agent_runtime_run_publication_mutations INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES,
 owner_session_id text NOT NULL,
 owner_target_kind text NOT NULL,
 owner_target_id text NOT NULL,
 owner_end_user_id bigint NOT NULL CHECK(owner_end_user_id>0),
 source_session_revision bigint NOT NULL CHECK(source_session_revision>0),
 closed_epoch_second bigint NOT NULL,
 closed_nano integer NOT NULL CHECK(closed_nano BETWEEN 0 AND 999999999),
 source_run_id text,
 source_run_revision bigint,
 source_run_updated_epoch_second bigint,
 source_run_updated_nano integer,
 original_row_sha256 text NOT NULL CHECK(original_row_sha256 ~ '^[a-f0-9]{64}$'),
 archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 output_data_sha256 text NOT NULL CHECK(output_data_sha256 ~ '^[a-f0-9]{64}$')
 ,
 FOREIGN KEY(tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) ON DELETE RESTRICT
);
CREATE INDEX run_publication_mutation_archives__owner_idx ON public.agent_runtime_run_publication_mutation_archives
 (tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id);
ALTER TABLE public.agent_runtime_run_publication_mutation_archives ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_run_publication_mutation_archives FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_run_publication_mutation_archives
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER archive_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_run_publication_mutation_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_run_publication_mutation_archives IS '固定来源agent_runtime_run_publication_mutations的精确冷存储；原标量键及关闭源事实可校验，复制不等于退役';

CREATE TABLE public.agent_runtime_session_event_payload_archives (
 LIKE public.agent_runtime_session_events INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES,
 owner_session_id text NOT NULL,
 owner_target_kind text NOT NULL,
 owner_target_id text NOT NULL,
 owner_end_user_id bigint NOT NULL CHECK(owner_end_user_id>0),
 source_session_revision bigint NOT NULL CHECK(source_session_revision>0),
 closed_epoch_second bigint NOT NULL,
 closed_nano integer NOT NULL CHECK(closed_nano BETWEEN 0 AND 999999999),
 source_run_id text,
 source_run_revision bigint,
 source_run_updated_epoch_second bigint,
 source_run_updated_nano integer,
 original_row_sha256 text NOT NULL CHECK(original_row_sha256 ~ '^[a-f0-9]{64}$'),
 archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 payload_canonical_json_sha256 text NOT NULL CHECK(payload_canonical_json_sha256 ~ '^[a-f0-9]{64}$')
 , original_payload_canonical_json text NOT NULL,
 original_event_digest text NOT NULL CHECK(original_event_digest ~ '^[a-f0-9]{64}$'),
 FOREIGN KEY(tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) ON DELETE RESTRICT
);
CREATE INDEX session_event_payload_archives__owner_idx ON public.agent_runtime_session_event_payload_archives
 (tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id);
ALTER TABLE public.agent_runtime_session_event_payload_archives ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_event_payload_archives FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_event_payload_archives
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER archive_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_session_event_payload_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_session_event_payload_archives IS '固定来源agent_runtime_session_events的精确冷存储；原标量键及关闭源事实可校验，复制不等于退役';

CREATE TABLE public.agent_runtime_session_checkpoint_body_archives (
 LIKE public.agent_runtime_session_checkpoint_bodies INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES,
 owner_session_id text NOT NULL,
 owner_target_kind text NOT NULL,
 owner_target_id text NOT NULL,
 owner_end_user_id bigint NOT NULL CHECK(owner_end_user_id>0),
 source_session_revision bigint NOT NULL CHECK(source_session_revision>0),
 closed_epoch_second bigint NOT NULL,
 closed_nano integer NOT NULL CHECK(closed_nano BETWEEN 0 AND 999999999),
 source_run_id text,
 source_run_revision bigint,
 source_run_updated_epoch_second bigint,
 source_run_updated_nano integer,
 original_row_sha256 text NOT NULL CHECK(original_row_sha256 ~ '^[a-f0-9]{64}$'),
 archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 payload_data_sha256 text NOT NULL CHECK(payload_data_sha256 ~ '^[a-f0-9]{64}$')
 ,
 FOREIGN KEY(tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) ON DELETE RESTRICT
);
CREATE INDEX session_checkpoint_body_archives__owner_idx ON public.agent_runtime_session_checkpoint_body_archives
 (tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id);
ALTER TABLE public.agent_runtime_session_checkpoint_body_archives ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_checkpoint_body_archives FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_checkpoint_body_archives
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER archive_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_session_checkpoint_body_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_session_checkpoint_body_archives IS '固定来源agent_runtime_session_checkpoint_bodies的精确冷存储；原标量键及关闭源事实可校验，复制不等于退役';

CREATE TABLE public.agent_runtime_session_checkpoint_archives (
 LIKE public.agent_runtime_session_checkpoints INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES,
 owner_session_id text NOT NULL,
 owner_target_kind text NOT NULL,
 owner_target_id text NOT NULL,
 owner_end_user_id bigint NOT NULL CHECK(owner_end_user_id>0),
 source_session_revision bigint NOT NULL CHECK(source_session_revision>0),
 closed_epoch_second bigint NOT NULL,
 closed_nano integer NOT NULL CHECK(closed_nano BETWEEN 0 AND 999999999),
 source_run_id text,
 source_run_revision bigint,
 source_run_updated_epoch_second bigint,
 source_run_updated_nano integer,
 original_row_sha256 text NOT NULL CHECK(original_row_sha256 ~ '^[a-f0-9]{64}$'),
 archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 payload_data_sha256 text NOT NULL CHECK(payload_data_sha256 ~ '^[a-f0-9]{64}$')
 ,
 FOREIGN KEY(tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) ON DELETE RESTRICT
);
CREATE INDEX session_checkpoint_archives__owner_idx ON public.agent_runtime_session_checkpoint_archives
 (tenant_id,owner_target_kind,owner_target_id,owner_session_id,owner_end_user_id);
ALTER TABLE public.agent_runtime_session_checkpoint_archives ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_checkpoint_archives FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_checkpoint_archives
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER archive_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_session_checkpoint_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_session_checkpoint_archives IS '固定来源agent_runtime_session_checkpoints的精确冷存储；原标量键及关闭源事实可校验，复制不等于退役';

CREATE TABLE public.agent_runtime_session_checkpoint_archive_body_refs(
 tenant_id text NOT NULL, run_id text NOT NULL, checkpoint_hash text NOT NULL, body_hash varchar(64) NOT NULL,
 PRIMARY KEY(tenant_id,run_id,checkpoint_hash,body_hash),
 FOREIGN KEY(tenant_id,run_id,checkpoint_hash) REFERENCES public.agent_runtime_session_checkpoint_archives(tenant_id,run_id,content_hash),
 FOREIGN KEY(tenant_id,run_id,body_hash) REFERENCES public.agent_runtime_session_checkpoint_body_archives(tenant_id,run_id,content_hash)
);
CREATE INDEX session_checkpoint_archive_body_refs_body_idx
 ON public.agent_runtime_session_checkpoint_archive_body_refs(tenant_id,run_id,body_hash);
ALTER TABLE public.agent_runtime_session_checkpoint_archive_body_refs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_checkpoint_archive_body_refs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_checkpoint_archive_body_refs
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER archive_immutable BEFORE UPDATE OR DELETE ON public.agent_runtime_session_checkpoint_archive_body_refs
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_session_checkpoint_archive_body_refs IS '冷checkpoint至同原Run冷正文的精确引用，热引用解除后仍可恢复';

CREATE TABLE public.agent_runtime_session_owner_retirements(
 tenant_id text NOT NULL, session_id text NOT NULL, target_kind text NOT NULL, target_id text NOT NULL, end_user_id bigint NOT NULL,
 source_session_revision bigint NOT NULL, closed_epoch_second bigint NOT NULL, closed_nano integer NOT NULL,
 phase integer NOT NULL DEFAULT 1 CHECK(phase BETWEEN 1 AND 12),
 scan_after_key text[] NOT NULL DEFAULT '{}',
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','RETIRED')),
 retired_rows bigint NOT NULL DEFAULT 0 CHECK(retired_rows>=0),
 retired_bytes bigint NOT NULL DEFAULT 0 CHECK(retired_bytes>=0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,target_kind,target_id,session_id),
 FOREIGN KEY(tenant_id,target_kind,target_id,session_id,end_user_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) ON DELETE RESTRICT,
 CHECK(closed_nano BETWEEN 0 AND 999999999)
);
ALTER TABLE public.agent_runtime_session_owner_retirements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_owner_retirements FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_owner_retirements
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
COMMENT ON TABLE public.agent_runtime_session_owner_retirements IS 'Owner私有正文退役的持久阶段/对象游标及真实收缩计数；RETIRED不屏蔽后续合法冻结事实';
CREATE INDEX session_retirement_runs_gate_idx ON public.agent_runtime_runs
 (tenant_id,target_kind,target_id,session_id,end_user_id,status,updated_epoch_second,updated_nano,run_id);

-- Fixed whitelist, never caller-provided table/column names.
CREATE FUNCTION public.agent_runtime_session_archive_spec(requested_phase integer)
RETURNS TABLE(hot_table text,cold_table text,payload_columns text[],key_columns text[],from_sql text,owner_sql text,run_sql text)
LANGUAGE sql IMMUTABLE SECURITY INVOKER AS $spec$
 SELECT hot_table,cold_table,payload_columns,key_columns,from_sql,owner_sql,run_sql FROM (VALUES
 (1,'agent_runtime_input_ledger','agent_runtime_input_ledger_archives',ARRAY['command_data','receipt_data']::text[],ARRAY['tenant_id','target_kind','target_id','session_id','input_id']::text[],'public.agent_runtime_input_ledger h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (2,'agent_runtime_session_admission_prepares','agent_runtime_session_admission_prepare_archives',ARRAY['command_data']::text[],ARRAY['tenant_id','target_kind','target_id','session_id','input_id']::text[],'public.agent_runtime_session_admission_prepares h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','NULL::text'),
 (3,'agent_runtime_session_commit_journals','agent_runtime_session_commit_journal_archives',ARRAY['command_data']::text[],ARRAY['tenant_id','run_id','operation_hash']::text[],'public.agent_runtime_session_commit_journals h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (4,'agent_runtime_runs','agent_runtime_run_record_archives',ARRAY['record_data']::text[],ARRAY['tenant_id','run_id']::text[],'public.agent_runtime_runs h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (5,'agent_runtime_session_inbox','agent_runtime_session_inbox_archives',ARRAY['entry_data']::text[],ARRAY['tenant_id','target_kind','target_id','session_id','input_id']::text[],'public.agent_runtime_session_inbox h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','(SELECT l.run_id FROM public.agent_runtime_input_ledger l WHERE (l.tenant_id,l.target_kind,l.target_id,l.session_id,l.input_id)=(h.tenant_id,h.target_kind,h.target_id,h.session_id,h.input_id))'),
 (6,'agent_runtime_run_mutations','agent_runtime_run_mutation_archives',ARRAY['output_data']::text[],ARRAY['tenant_id','run_id','mutation_key']::text[],'public.agent_runtime_run_mutations h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (7,'agent_runtime_inbox_mutations','agent_runtime_session_inbox_mutation_archives',ARRAY['output_data']::text[],ARRAY['tenant_id','target_kind','target_id','session_id','input_id','mutation_key']::text[],'public.agent_runtime_inbox_mutations h JOIN public.agent_runtime_session_inbox o ON (o.tenant_id,o.target_kind,o.target_id,o.session_id,o.input_id)=(h.tenant_id,h.target_kind,h.target_id,h.session_id,h.input_id)','(o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=($1,$2,$3,$4,$5)','(SELECT l.run_id FROM public.agent_runtime_input_ledger l WHERE (l.tenant_id,l.target_kind,l.target_id,l.session_id,l.input_id)=(h.tenant_id,h.target_kind,h.target_id,h.session_id,h.input_id))'),
 (8,'agent_runtime_run_publication_mutations','agent_runtime_run_publication_mutation_archives',ARRAY['output_data']::text[],ARRAY['tenant_id','source_id','event_id','mutation_key']::text[],'public.agent_runtime_run_publication_mutations h JOIN public.agent_runtime_runs o ON (o.tenant_id,o.run_id)=(h.tenant_id,h.source_id)','(o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=($1,$2,$3,$4,$5)','h.source_id'),
 (9,'agent_runtime_session_events','agent_runtime_session_event_payload_archives',ARRAY['payload_canonical_json']::text[],ARRAY['tenant_id','target_kind','target_id','session_id','event_id']::text[],'public.agent_runtime_session_events h','(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (10,'agent_runtime_session_checkpoint_bodies','agent_runtime_session_checkpoint_body_archives',ARRAY['payload_data']::text[],ARRAY['tenant_id','run_id','content_hash']::text[],'public.agent_runtime_session_checkpoint_bodies h JOIN public.agent_runtime_runs o ON (o.tenant_id,o.run_id)=(h.tenant_id,h.run_id)','(o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (11,'agent_runtime_session_checkpoints','agent_runtime_session_checkpoint_archives',ARRAY['payload_data']::text[],ARRAY['tenant_id','run_id','content_hash']::text[],'public.agent_runtime_session_checkpoints h JOIN public.agent_runtime_runs o ON (o.tenant_id,o.run_id)=(h.tenant_id,h.run_id)','(o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=($1,$2,$3,$4,$5)','h.run_id'),
 (12,'agent_runtime_session_checkpoint_bodies','agent_runtime_session_checkpoint_body_archives',ARRAY['payload_data']::text[],ARRAY['tenant_id','run_id','content_hash']::text[],'public.agent_runtime_session_checkpoint_bodies h JOIN public.agent_runtime_runs o ON (o.tenant_id,o.run_id)=(h.tenant_id,h.run_id)','(o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=($1,$2,$3,$4,$5)','h.run_id')
 ) fixed(phase,hot_table,cold_table,payload_columns,key_columns,from_sql,owner_sql,run_sql) WHERE phase=requested_phase
$spec$;

CREATE FUNCTION public.agent_runtime_session_archive_original_row(cold jsonb,payload_columns text[],event_row boolean DEFAULT false)
RETURNS jsonb LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER AS $$
DECLARE result jsonb; field text;
BEGIN
 result:=cold-ARRAY['owner_session_id','owner_target_kind','owner_target_id','owner_end_user_id',
 'source_session_revision','closed_epoch_second','closed_nano','source_run_id','source_run_revision',
 'source_run_updated_epoch_second','source_run_updated_nano','original_row_sha256','archived_at','original_payload_canonical_json','original_event_digest'];
 FOREACH field IN ARRAY payload_columns LOOP result:=result-(field||'_sha256'); END LOOP;
 IF event_row THEN result:=jsonb_set(result,'{payload_canonical_json}',cold->'original_payload_canonical_json'); END IF;
 RETURN result;
END $$;

CREATE FUNCTION public.agent_runtime_session_owner_archive_protection(t text,s text,k text,id text,u bigint)
RETURNS text[] LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE reasons text[];
BEGIN
 reasons:=public.agent_runtime_session_history_owner_protection(t,s,k,id,u);
 IF EXISTS(SELECT 1 FROM public.agent_runtime_session_inbox h
  WHERE (h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=(t,s,k,id,u)
    AND h.status IN('QUEUED','PROCESSING')) THEN reasons:=array_append(reasons,'ACTIVE_INBOX'); END IF;
 IF EXISTS(SELECT 1 FROM public.agent_runtime_runs h
  WHERE (h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=(t,s,k,id,u)
    AND h.status IN('COMPLETED','TERMINATED','FAILED','CANCELLED')
    AND h.updated_epoch_second::numeric+h.updated_nano::numeric/1000000000
       >extract(epoch FROM clock_timestamp()-interval '30 days'))
 THEN reasons:=array_append(reasons,'RECENT_RUN'); END IF;
 RETURN reasons;
END $$;

-- Authoritative locks always precede finite Run/object locks.
CREATE FUNCTION public.agent_runtime_session_archive_gate(cold jsonb) RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE t text:=cold->>'tenant_id'; s text:=cold->>'owner_session_id';
 k text:=cold->>'owner_target_kind'; id text:=cold->>'owner_target_id'; u bigint:=(cold->>'owner_end_user_id')::bigint;
 source public.agent_runtime_sessions; run public.agent_runtime_runs; root bigint;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 SELECT o.end_user_id INTO root FROM public.agent_runtime_session_owners o
  WHERE (o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=(t,s,k,id,u) FOR NO KEY UPDATE NOWAIT;
 SELECT h.* INTO source FROM public.agent_runtime_sessions h
  WHERE (h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=(t,s,k,id,u) FOR UPDATE NOWAIT;
 IF root IS NULL OR source.status IS DISTINCT FROM 'CLOSED'
  OR (source.session_revision,source.updated_epoch_second,source.updated_nano) IS DISTINCT FROM
    ((cold->>'source_session_revision')::bigint,(cold->>'closed_epoch_second')::bigint,(cold->>'closed_nano')::integer)
  OR source.updated_epoch_second::numeric+source.updated_nano::numeric/1000000000
       >extract(epoch FROM clock_timestamp()-interval '30 days')
 THEN RAISE EXCEPTION 'session_archive_source_changed'; END IF;
 IF cardinality(public.agent_runtime_session_owner_archive_protection(t,s,k,id,u))>0
 THEN RAISE EXCEPTION 'session_archive_owner_protected'; END IF;
 IF cold->>'source_run_id' IS NOT NULL THEN
  SELECT h.* INTO run FROM public.agent_runtime_runs h
   WHERE h.tenant_id=t AND h.run_id=cold->>'source_run_id' FOR NO KEY UPDATE NOWAIT;
  IF (run.tenant_id,run.session_id,run.target_kind,run.target_id,run.end_user_id) IS DISTINCT FROM (t,s,k,id,u)
    OR run.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED')
    OR (run.run_revision,run.updated_epoch_second,run.updated_nano) IS DISTINCT FROM
      ((cold->>'source_run_revision')::bigint,(cold->>'source_run_updated_epoch_second')::bigint,(cold->>'source_run_updated_nano')::integer)
    OR run.updated_epoch_second::numeric+run.updated_nano::numeric/1000000000
       >extract(epoch FROM clock_timestamp()-interval '30 days')
  THEN RAISE EXCEPTION 'session_archive_run_source_changed'; END IF;
 END IF;
END $$;

CREATE FUNCTION public.agent_runtime_session_archive_insert_guard() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE spec record; cold jsonb:=to_jsonb(NEW); original jsonb; hot jsonb; key jsonb:='{}';
 field text; expected text; key_where text; actual_run_id text; event public.agent_runtime_session_events;
BEGIN
 SELECT * INTO STRICT spec FROM public.agent_runtime_session_archive_spec(TG_ARGV[0]::integer);
 IF TG_TABLE_NAME<>spec.cold_table THEN RAISE EXCEPTION 'session_archive_fixed_table_required'; END IF;
 PERFORM public.agent_runtime_session_archive_gate(cold);
 FOREACH field IN ARRAY spec.key_columns LOOP key:=key||jsonb_build_object(field,cold->field); END LOOP;
 SELECT string_agg('h.'||quote_ident(x)||'=($6->>'||quote_literal(x)||')',' AND ') INTO key_where FROM unnest(spec.key_columns) x;
 EXECUTE 'SELECT to_jsonb(h),'||spec.run_sql||' FROM '||spec.from_sql||' WHERE '||spec.owner_sql||' AND '||key_where
  INTO hot,actual_run_id USING NEW.tenant_id,NEW.owner_session_id,NEW.owner_target_kind,NEW.owner_target_id,NEW.owner_end_user_id,key;
 original:=public.agent_runtime_session_archive_original_row(cold,spec.payload_columns,TG_ARGV[0]='9');
 IF hot IS NULL OR hot IS DISTINCT FROM original OR NEW.source_run_id IS DISTINCT FROM actual_run_id
  OR encode(sha256(convert_to(original::text,'UTF8')),'hex') IS DISTINCT FROM NEW.original_row_sha256
 THEN RAISE EXCEPTION 'session_archive_exact_hot_source_required'; END IF;
 FOREACH field IN ARRAY spec.payload_columns LOOP
  IF field='payload_data' THEN expected:=encode(sha256(decode(substring(cold->>field FROM 3),'hex')),'hex');
  ELSE expected:=public.agent_runtime_session_archive_text_digest(cold->>field);
    IF public.agent_runtime_session_archive_is_marker(cold->>field) THEN RAISE EXCEPTION 'session_archive_original_text_required'; END IF;
  END IF;
  IF expected IS DISTINCT FROM cold->>(field||'_sha256') THEN RAISE EXCEPTION 'session_archive_payload_digest_rejected'; END IF;
 END LOOP;
 IF TG_ARGV[0]='10' THEN
  -- Ownership is not a recovery reference. Hold a real edge until the copy commits.
  PERFORM 1 FROM public.agent_runtime_session_checkpoint_body_refs r
   WHERE (r.tenant_id,r.run_id,r.body_hash)=(NEW.tenant_id,NEW.run_id,NEW.content_hash)
   FOR KEY SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION 'session_checkpoint_archive_body_reference_required'; END IF;
 END IF;
 IF TG_ARGV[0]='9' THEN
  event:=jsonb_populate_record(NULL::public.agent_runtime_session_events,original);
  IF NEW.payload_canonical_json IS DISTINCT FROM public.agent_runtime_session_event_payload(event.tenant_id,event.session_id,
    event.target_kind,event.target_id,event.end_user_id,event.run_id,event.event_id,event.event_type,event.payload_schema_id,event.payload_canonical_json)
  THEN RAISE EXCEPTION 'session_archive_event_original_digest_rejected'; END IF;
  IF NEW.original_event_digest IS DISTINCT FROM public.agent_runtime_session_event_digest(event)
   OR NEW.original_event_digest IS DISTINCT FROM public.agent_runtime_session_event_digest(
    jsonb_populate_record(NULL::public.agent_runtime_session_events,cold))
  THEN RAISE EXCEPTION 'session_archive_event_logical_digest_rejected'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_input_ledger_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('1');
CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_admission_prepare_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('2');
CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_commit_journal_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('3');
CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_run_record_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('4');
CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_inbox_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('5');
CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_run_mutation_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('6');
CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_inbox_mutation_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('7');
CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_run_publication_mutation_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('8');
CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_event_payload_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('9');
CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_checkpoint_body_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('10');
CREATE TRIGGER archive_exact_source BEFORE INSERT ON public.agent_runtime_session_checkpoint_archives
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_archive_insert_guard('11');

-- A representation-only UPDATE must prove the exact archived old row and unchanged scalar facts.
CREATE FUNCTION public.agent_runtime_session_archive_update_valid(hot_table text,before_row jsonb,after_row jsonb)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE spec record; cold jsonb; original jsonb; field text; key_where text; key jsonb:='{}'; changed boolean:=false;
BEGIN
 SELECT * INTO spec FROM public.agent_runtime_session_archive_spec(
  CASE hot_table
   WHEN 'agent_runtime_input_ledger' THEN 1 WHEN 'agent_runtime_session_admission_prepares' THEN 2
   WHEN 'agent_runtime_session_commit_journals' THEN 3 WHEN 'agent_runtime_runs' THEN 4
   WHEN 'agent_runtime_session_inbox' THEN 5 WHEN 'agent_runtime_run_mutations' THEN 6
   WHEN 'agent_runtime_inbox_mutations' THEN 7 WHEN 'agent_runtime_run_publication_mutations' THEN 8
   WHEN 'agent_runtime_session_events' THEN 9 ELSE 0 END);
 IF spec.hot_table IS NULL THEN RETURN false; END IF;
 IF (before_row-spec.payload_columns) IS DISTINCT FROM (after_row-spec.payload_columns) THEN RETURN false; END IF;
 FOREACH field IN ARRAY spec.key_columns LOOP key:=key||jsonb_build_object(field,before_row->field); END LOOP;
 SELECT string_agg('h.'||quote_ident(x)||'=($2->>'||quote_literal(x)||')',' AND ') INTO key_where FROM unnest(spec.key_columns) x;
 EXECUTE format('SELECT to_jsonb(h) FROM public.%I h WHERE h.tenant_id=$1 AND %s',spec.cold_table,key_where)
  INTO cold USING before_row->>'tenant_id',key;
 IF cold IS NULL THEN RETURN false; END IF;
 original:=public.agent_runtime_session_archive_original_row(cold,spec.payload_columns,hot_table='agent_runtime_session_events');
 IF original IS DISTINCT FROM before_row
  OR encode(sha256(convert_to(original::text,'UTF8')),'hex') IS DISTINCT FROM cold->>'original_row_sha256' THEN RETURN false; END IF;
 FOREACH field IN ARRAY spec.payload_columns LOOP
  IF public.agent_runtime_session_archive_is_marker(before_row->>field)
    OR after_row->>field IS DISTINCT FROM public.agent_runtime_session_archive_marker(cold->>field)
  THEN RETURN false; END IF;
  changed:=changed OR before_row->>field IS DISTINCT FROM after_row->>field;
 END LOOP;
 IF NOT changed THEN RETURN false; END IF;
 PERFORM public.agent_runtime_session_archive_gate(cold);
 IF hot_table='agent_runtime_session_events' AND public.agent_runtime_session_event_referenced(
  cold->>'tenant_id',cold->>'owner_session_id',cold->>'owner_target_kind',cold->>'owner_target_id',
  (cold->>'owner_end_user_id')::bigint,cold->>'event_id')
 THEN RAISE EXCEPTION 'session_archive_event_source_reference'; END IF;
 RETURN true;
END $$;

CREATE FUNCTION public.agent_runtime_session_archived_input_command(t text,s text,k text,id text,u bigint,requested_input_id text,live text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE original text; digest text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF NOT public.agent_runtime_session_archive_is_marker(live) THEN RETURN live; END IF;
 SELECT h.command_data,h.command_data_sha256 INTO original,digest FROM public.agent_runtime_input_ledger_archives h
  WHERE (h.tenant_id,h.owner_session_id,h.owner_target_kind,h.owner_target_id,h.owner_end_user_id)=(t,s,k,id,u)
   AND h.input_id=requested_input_id;
 IF original IS NULL OR digest IS DISTINCT FROM public.agent_runtime_session_archive_text_digest(original)
  OR live IS DISTINCT FROM public.agent_runtime_session_archive_marker(original)
 THEN RAISE EXCEPTION 'session_archive_exact_reference_rejected'; END IF;
 RETURN original;
END $$;

CREATE FUNCTION public.agent_runtime_session_archived_input_receipt(t text,s text,k text,id text,u bigint,requested_input_id text,live text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE original text; digest text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF NOT public.agent_runtime_session_archive_is_marker(live) THEN RETURN live; END IF;
 SELECT h.receipt_data,h.receipt_data_sha256 INTO original,digest FROM public.agent_runtime_input_ledger_archives h
  WHERE (h.tenant_id,h.owner_session_id,h.owner_target_kind,h.owner_target_id,h.owner_end_user_id)=(t,s,k,id,u)
   AND h.input_id=requested_input_id;
 IF original IS NULL OR digest IS DISTINCT FROM public.agent_runtime_session_archive_text_digest(original)
  OR live IS DISTINCT FROM public.agent_runtime_session_archive_marker(original)
 THEN RAISE EXCEPTION 'session_archive_exact_reference_rejected'; END IF;
 RETURN original;
END $$;

CREATE FUNCTION public.agent_runtime_session_archived_admission_command(t text,s text,k text,id text,u bigint,requested_input_id text,live text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE original text; digest text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF NOT public.agent_runtime_session_archive_is_marker(live) THEN RETURN live; END IF;
 SELECT h.command_data,h.command_data_sha256 INTO original,digest FROM public.agent_runtime_session_admission_prepare_archives h
  WHERE (h.tenant_id,h.owner_session_id,h.owner_target_kind,h.owner_target_id,h.owner_end_user_id)=(t,s,k,id,u)
   AND h.input_id=requested_input_id;
 IF original IS NULL OR digest IS DISTINCT FROM public.agent_runtime_session_archive_text_digest(original)
  OR live IS DISTINCT FROM public.agent_runtime_session_archive_marker(original)
 THEN RAISE EXCEPTION 'session_archive_exact_reference_rejected'; END IF;
 RETURN original;
END $$;

CREATE FUNCTION public.agent_runtime_session_archived_commit_command(t text,s text,k text,id text,u bigint,requested_run_id text,requested_operation_hash text,live text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE original text; digest text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF NOT public.agent_runtime_session_archive_is_marker(live) THEN RETURN live; END IF;
 SELECT h.command_data,h.command_data_sha256 INTO original,digest FROM public.agent_runtime_session_commit_journal_archives h
  WHERE (h.tenant_id,h.owner_session_id,h.owner_target_kind,h.owner_target_id,h.owner_end_user_id)=(t,s,k,id,u)
   AND h.run_id=requested_run_id AND h.operation_hash=requested_operation_hash;
 IF original IS NULL OR digest IS DISTINCT FROM public.agent_runtime_session_archive_text_digest(original)
  OR live IS DISTINCT FROM public.agent_runtime_session_archive_marker(original)
 THEN RAISE EXCEPTION 'session_archive_exact_reference_rejected'; END IF;
 RETURN original;
END $$;

CREATE FUNCTION public.agent_runtime_session_archived_run_record(t text,s text,k text,id text,u bigint,requested_run_id text,live text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE original text; digest text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF NOT public.agent_runtime_session_archive_is_marker(live) THEN RETURN live; END IF;
 SELECT h.record_data,h.record_data_sha256 INTO original,digest FROM public.agent_runtime_run_record_archives h
  WHERE (h.tenant_id,h.owner_session_id,h.owner_target_kind,h.owner_target_id,h.owner_end_user_id)=(t,s,k,id,u)
   AND h.run_id=requested_run_id;
 IF original IS NULL OR digest IS DISTINCT FROM public.agent_runtime_session_archive_text_digest(original)
  OR live IS DISTINCT FROM public.agent_runtime_session_archive_marker(original)
 THEN RAISE EXCEPTION 'session_archive_exact_reference_rejected'; END IF;
 RETURN original;
END $$;

CREATE FUNCTION public.agent_runtime_session_archived_inbox_entry(t text,s text,k text,id text,u bigint,requested_input_id text,live text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE original text; digest text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF NOT public.agent_runtime_session_archive_is_marker(live) THEN RETURN live; END IF;
 SELECT h.entry_data,h.entry_data_sha256 INTO original,digest FROM public.agent_runtime_session_inbox_archives h
  WHERE (h.tenant_id,h.owner_session_id,h.owner_target_kind,h.owner_target_id,h.owner_end_user_id)=(t,s,k,id,u)
   AND h.input_id=requested_input_id;
 IF original IS NULL OR digest IS DISTINCT FROM public.agent_runtime_session_archive_text_digest(original)
  OR live IS DISTINCT FROM public.agent_runtime_session_archive_marker(original)
 THEN RAISE EXCEPTION 'session_archive_exact_reference_rejected'; END IF;
 RETURN original;
END $$;

CREATE FUNCTION public.agent_runtime_session_archived_run_mutation_output(t text,s text,k text,id text,u bigint,requested_run_id text,requested_mutation_key text,live text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE original text; digest text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF NOT public.agent_runtime_session_archive_is_marker(live) THEN RETURN live; END IF;
 SELECT h.output_data,h.output_data_sha256 INTO original,digest FROM public.agent_runtime_run_mutation_archives h
  WHERE (h.tenant_id,h.owner_session_id,h.owner_target_kind,h.owner_target_id,h.owner_end_user_id)=(t,s,k,id,u)
   AND h.run_id=requested_run_id AND h.mutation_key=requested_mutation_key;
 IF original IS NULL OR digest IS DISTINCT FROM public.agent_runtime_session_archive_text_digest(original)
  OR live IS DISTINCT FROM public.agent_runtime_session_archive_marker(original)
 THEN RAISE EXCEPTION 'session_archive_exact_reference_rejected'; END IF;
 RETURN original;
END $$;

CREATE FUNCTION public.agent_runtime_session_archived_inbox_mutation_output(t text,s text,k text,id text,u bigint,requested_input_id text,requested_mutation_key text,live text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE original text; digest text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF NOT public.agent_runtime_session_archive_is_marker(live) THEN RETURN live; END IF;
 SELECT h.output_data,h.output_data_sha256 INTO original,digest FROM public.agent_runtime_session_inbox_mutation_archives h
  WHERE (h.tenant_id,h.owner_session_id,h.owner_target_kind,h.owner_target_id,h.owner_end_user_id)=(t,s,k,id,u)
   AND h.input_id=requested_input_id AND h.mutation_key=requested_mutation_key;
 IF original IS NULL OR digest IS DISTINCT FROM public.agent_runtime_session_archive_text_digest(original)
  OR live IS DISTINCT FROM public.agent_runtime_session_archive_marker(original)
 THEN RAISE EXCEPTION 'session_archive_exact_reference_rejected'; END IF;
 RETURN original;
END $$;

CREATE FUNCTION public.agent_runtime_session_archived_publication_mutation_output(t text,s text,k text,id text,u bigint,requested_source_id text,requested_event_id text,requested_mutation_key text,live text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE original text; digest text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF NOT public.agent_runtime_session_archive_is_marker(live) THEN RETURN live; END IF;
 SELECT h.output_data,h.output_data_sha256 INTO original,digest FROM public.agent_runtime_run_publication_mutation_archives h
  WHERE (h.tenant_id,h.owner_session_id,h.owner_target_kind,h.owner_target_id,h.owner_end_user_id)=(t,s,k,id,u)
   AND h.source_id=requested_source_id AND h.event_id=requested_event_id AND h.mutation_key=requested_mutation_key;
 IF original IS NULL OR digest IS DISTINCT FROM public.agent_runtime_session_archive_text_digest(original)
  OR live IS DISTINCT FROM public.agent_runtime_session_archive_marker(original)
 THEN RAISE EXCEPTION 'session_archive_exact_reference_rejected'; END IF;
 RETURN original;
END $$;

CREATE FUNCTION public.agent_runtime_session_archived_event_payload(t text,s text,k text,id text,u bigint,requested_event_id text,live text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE original text; digest text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF NOT public.agent_runtime_session_archive_is_marker(live) THEN RETURN live; END IF;
 SELECT h.payload_canonical_json,h.payload_canonical_json_sha256 INTO original,digest FROM public.agent_runtime_session_event_payload_archives h
  WHERE (h.tenant_id,h.owner_session_id,h.owner_target_kind,h.owner_target_id,h.owner_end_user_id)=(t,s,k,id,u)
   AND h.event_id=requested_event_id;
 IF original IS NULL OR digest IS DISTINCT FROM public.agent_runtime_session_archive_text_digest(original)
  OR live IS DISTINCT FROM public.agent_runtime_session_archive_marker(original)
 THEN RAISE EXCEPTION 'session_archive_exact_reference_rejected'; END IF;
 RETURN original;
END $$;

CREATE FUNCTION public.agent_runtime_session_retirement_objects(t text,s text,k text,id text,u bigint,p integer,
 after_key text[] DEFAULT '{}',maximum integer DEFAULT 16)
RETURNS TABLE(source_key jsonb,sort_key text[],source_run text,payload_bytes bigint)
LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE spec record; key_expression text; sort_expression text; bytes_expression text;
 where_extra text:=''; matching_key text; field text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF maximum NOT BETWEEN 1 AND 16 THEN RAISE EXCEPTION 'session_archive_object_limit'; END IF;
 SELECT * INTO STRICT spec FROM public.agent_runtime_session_archive_spec(p);
 SELECT 'jsonb_build_object('||string_agg(quote_literal(x)||',h.'||quote_ident(x),',')||')',
  'ARRAY['||string_agg('h.'||quote_ident(x)||'::text',',')||']::text[]',
  string_agg('a.'||quote_ident(x)||'=h.'||quote_ident(x),' AND ')
 INTO key_expression,sort_expression,matching_key FROM unnest(spec.key_columns) x;
 SELECT string_agg('coalesce(octet_length(h.'||quote_ident(x)||'),0)::bigint','+') INTO bytes_expression
 FROM unnest(spec.payload_columns) x;
 IF p<=9 THEN
  SELECT ' AND ('||string_agg('NOT public.agent_runtime_session_archive_is_marker(h.'||quote_ident(x)||')',' OR ')||')'
  INTO where_extra FROM unnest(spec.payload_columns) x;
 ELSIF p=10 THEN
  where_extra:=format(' AND NOT EXISTS(SELECT 1 FROM public.%I a WHERE %s)',spec.cold_table,matching_key)||
   ' AND EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
    WHERE (r.tenant_id,r.run_id,r.body_hash)=(h.tenant_id,h.run_id,h.content_hash))';
 ELSIF p=11 THEN
  where_extra:=' AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
   WHERE (r.tenant_id,r.run_id,r.checkpoint_hash)=(h.tenant_id,h.run_id,h.content_hash)
    AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_archives a
     WHERE (a.tenant_id,a.run_id,a.content_hash)=(r.tenant_id,r.run_id,r.body_hash)))';
 ELSIF p=12 THEN
  -- Unreferenced bodies have no recovery obligation and require no cold copy.
  where_extra:=' AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
    WHERE (r.tenant_id,r.run_id,r.body_hash)=(h.tenant_id,h.run_id,h.content_hash))';
 END IF;
 IF p=9 THEN where_extra:=where_extra||' AND h.event_type NOT IN(''agent.model-answer'',''agent.model-reasoning'',''agent.runtime-diagnostic'')
   AND NOT public.agent_runtime_session_event_referenced(h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id,h.event_id)
   AND (h.run_id IS NULL OR EXISTS(SELECT 1 FROM public.agent_runtime_runs r
    WHERE (r.tenant_id,r.run_id,r.session_id,r.target_kind,r.target_id,r.end_user_id)
      =(h.tenant_id,h.run_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)))'; END IF;
 RETURN QUERY EXECUTE 'SELECT '||key_expression||','||sort_expression||',('||spec.run_sql||')::text,'||bytes_expression||
  ' FROM '||spec.from_sql||' WHERE '||spec.owner_sql||where_extra||' AND ('||sort_expression||
  ')>$6 ORDER BY '||sort_expression||' LIMIT $7' USING t,s,k,id,u,after_key,maximum;
END $$;

CREATE FUNCTION public.agent_runtime_session_owner_retirement_pending(t text,s text,k text,id text,u bigint)
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE p integer;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 FOR p IN 1..12 LOOP
  IF EXISTS(SELECT 1 FROM public.agent_runtime_session_retirement_objects(t,s,k,id,u,p,'{}',1)) THEN RETURN true; END IF;
 END LOOP;
 RETURN false;
END $$;

CREATE FUNCTION public.agent_runtime_session_owner_retirement_guard() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
BEGIN
 PERFORM public.agent_runtime_session_archive_gate(to_jsonb(NEW)||jsonb_build_object(
  'owner_session_id',NEW.session_id,'owner_target_kind',NEW.target_kind,'owner_target_id',NEW.target_id,'owner_end_user_id',NEW.end_user_id));
 IF TG_OP='UPDATE' AND (
  (NEW.tenant_id,NEW.target_kind,NEW.target_id,NEW.session_id,NEW.end_user_id,NEW.source_session_revision,
   NEW.closed_epoch_second,NEW.closed_nano,NEW.created_at) IS DISTINCT FROM
  (OLD.tenant_id,OLD.target_kind,OLD.target_id,OLD.session_id,OLD.end_user_id,OLD.source_session_revision,
   OLD.closed_epoch_second,OLD.closed_nano,OLD.created_at)
  OR NEW.retired_rows<OLD.retired_rows OR NEW.retired_bytes<OLD.retired_bytes)
 THEN RAISE EXCEPTION 'session_archive_progress_source_changed'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER owner_retirement_source BEFORE INSERT OR UPDATE ON public.agent_runtime_session_owner_retirements
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_owner_retirement_guard();
CREATE TRIGGER owner_retirement_no_delete BEFORE DELETE ON public.agent_runtime_session_owner_retirements
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();

-- Exact cold proof gates deletion; insert reuse cannot reintroduce an already retired hot body.
CREATE FUNCTION public.agent_runtime_session_checkpoint_retirement_guard() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE cold jsonb; original jsonb; spec record; p integer;
BEGIN
 p:=CASE TG_TABLE_NAME WHEN 'agent_runtime_session_checkpoints' THEN 11
  WHEN 'agent_runtime_session_checkpoint_bodies' THEN 10 ELSE 0 END;
 SELECT * INTO STRICT spec FROM public.agent_runtime_session_archive_spec(p);
 IF TG_OP='INSERT' THEN
  EXECUTE format('SELECT to_jsonb(a) FROM public.%I a WHERE (a.tenant_id,a.run_id,a.content_hash)=($1,$2,$3)',spec.cold_table)
   INTO cold USING NEW.tenant_id,NEW.run_id,NEW.content_hash;
  IF cold IS NULL THEN RETURN NEW; END IF;
  original:=public.agent_runtime_session_archive_original_row(cold,spec.payload_columns);
  IF (original->>'identity_data',original->>'schema_id',original->>'payload_data') IS DISTINCT FROM
     (NEW.identity_data,NEW.schema_id,'\x'||encode(NEW.payload_data,'hex'))
  THEN RAISE EXCEPTION 'session_checkpoint_archive_conflict'; END IF;
  -- A repeated exact freeze already has a durable cold row. Do not resurrect a hot row.
  RETURN NULL;
 END IF;
 EXECUTE format('SELECT to_jsonb(a) FROM public.%I a WHERE (a.tenant_id,a.run_id,a.content_hash)=($1,$2,$3)',spec.cold_table)
  INTO cold USING OLD.tenant_id,OLD.run_id,OLD.content_hash;
 IF p=10 AND cold IS NULL THEN
  IF EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
   WHERE (r.tenant_id,r.run_id,r.body_hash)=(OLD.tenant_id,OLD.run_id,OLD.content_hash))
  THEN RAISE EXCEPTION 'session_checkpoint_hot_body_referenced'; END IF;
  -- Existing unowned orphan GC remains legal. Owner retirement archives before releasing a reference.
  RETURN OLD;
 END IF;
 IF cold IS NULL OR public.agent_runtime_session_archive_original_row(cold,spec.payload_columns) IS DISTINCT FROM to_jsonb(OLD)
  OR cold->>'payload_data_sha256' IS DISTINCT FROM encode(sha256(OLD.payload_data),'hex')
 THEN RAISE EXCEPTION 'session_checkpoint_exact_archive_required'; END IF;
 PERFORM public.agent_runtime_session_archive_gate(cold);
 IF p=10 AND EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
  WHERE (r.tenant_id,r.run_id,r.body_hash)=(OLD.tenant_id,OLD.run_id,OLD.content_hash))
 THEN RAISE EXCEPTION 'session_checkpoint_hot_body_referenced'; END IF;
 IF p=11 AND EXISTS(
  SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
  WHERE (r.tenant_id,r.run_id,r.checkpoint_hash)=(OLD.tenant_id,OLD.run_id,OLD.content_hash)
   AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_archive_body_refs a
    WHERE (a.tenant_id,a.run_id,a.checkpoint_hash,a.body_hash)=(r.tenant_id,r.run_id,r.checkpoint_hash,r.body_hash)))
 THEN RAISE EXCEPTION 'session_checkpoint_archive_body_reference_required'; END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER checkpoint_retirement_proof BEFORE INSERT OR DELETE ON public.agent_runtime_session_checkpoints
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_retirement_guard();
CREATE TRIGGER checkpoint_body_retirement_proof BEFORE INSERT OR DELETE ON public.agent_runtime_session_checkpoint_bodies
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_retirement_guard();

CREATE FUNCTION public.agent_runtime_session_checkpoint_archive_ref_guard() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE checkpoint jsonb; body jsonb;
BEGIN
 SELECT to_jsonb(a) INTO checkpoint FROM public.agent_runtime_session_checkpoint_archives a
  WHERE (a.tenant_id,a.run_id,a.content_hash)=(NEW.tenant_id,NEW.run_id,NEW.checkpoint_hash) FOR KEY SHARE;
 SELECT to_jsonb(a) INTO body FROM public.agent_runtime_session_checkpoint_body_archives a
  WHERE (a.tenant_id,a.run_id,a.content_hash)=(NEW.tenant_id,NEW.run_id,NEW.body_hash) FOR KEY SHARE;
 IF checkpoint IS NULL OR body IS NULL OR
  (checkpoint->>'owner_session_id',checkpoint->>'owner_target_kind',checkpoint->>'owner_target_id',checkpoint->>'owner_end_user_id',
   checkpoint->>'source_session_revision',checkpoint->>'closed_epoch_second',checkpoint->>'closed_nano',checkpoint->>'identity_data') IS DISTINCT FROM
  (body->>'owner_session_id',body->>'owner_target_kind',body->>'owner_target_id',body->>'owner_end_user_id',
   body->>'source_session_revision',body->>'closed_epoch_second',body->>'closed_nano',body->>'identity_data')
  OR NOT EXISTS(SELECT 1 FROM public.agent_runtime_session_checkpoint_body_refs r
   WHERE (r.tenant_id,r.run_id,r.checkpoint_hash,r.body_hash)=(NEW.tenant_id,NEW.run_id,NEW.checkpoint_hash,NEW.body_hash))
 THEN RAISE EXCEPTION 'session_checkpoint_archive_reference_rejected'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER checkpoint_archive_reference_source BEFORE INSERT ON public.agent_runtime_session_checkpoint_archive_body_refs
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_archive_ref_guard();

-- A preparation copy stays protected by its hot edge until the exact cold edge exists.
-- Parent checkpoint deletion already requires this same proof; direct edge deletion must too.
CREATE FUNCTION public.agent_runtime_session_checkpoint_body_ref_retirement_guard() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
BEGIN
 IF OLD.tenant_id IS DISTINCT FROM current_setting('app.tenant_id',true)
 THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 PERFORM 1 FROM public.agent_runtime_session_checkpoint_archive_body_refs r
  WHERE (r.tenant_id,r.run_id,r.checkpoint_hash,r.body_hash)=
   (OLD.tenant_id,OLD.run_id,OLD.checkpoint_hash,OLD.body_hash) FOR KEY SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'session_checkpoint_archive_body_reference_required'; END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER checkpoint_body_ref_retirement_proof BEFORE DELETE ON public.agent_runtime_session_checkpoint_body_refs
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_body_ref_retirement_guard();
CREATE TRIGGER checkpoint_body_ref_no_update BEFORE UPDATE ON public.agent_runtime_session_checkpoint_body_refs
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();

CREATE FUNCTION public.agent_runtime_session_retire_owner(t text,s text,k text,id text,u bigint,maximum integer)
RETURNS TABLE(rows bigint,bytes bigint,more_work boolean,protected_reasons text[])
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE source public.agent_runtime_sessions; progress public.agent_runtime_session_owner_retirements;
 spec record; item record; run public.agent_runtime_runs; root bigint; selected jsonb; original jsonb; cold jsonb;
 page jsonb; run_key text; field text; key_where text; assign_sql text; source_columns text; all_columns text;
 count_work integer:=0; count_rows bigint:=0; count_bytes bigint:=0; work_bytes bigint:=0; previous_key text[];
 hot_bytes bigint; stored_bytes bigint; canonical text; affected bigint; p integer; cursor_key text[];
 reasons text[]:='{}'; has_reference boolean:=false; source_event public.agent_runtime_session_events;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_archive_tenant_required'; END IF;
 IF maximum NOT BETWEEN 1 AND 16 THEN RAISE EXCEPTION 'session_archive_object_limit'; END IF;
 SELECT o.end_user_id INTO root FROM public.agent_runtime_session_owners o
  WHERE (o.tenant_id,o.session_id,o.target_kind,o.target_id,o.end_user_id)=(t,s,k,id,u) FOR NO KEY UPDATE;
 SELECT h.* INTO source FROM public.agent_runtime_sessions h
  WHERE (h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=(t,s,k,id,u) FOR UPDATE;
 IF root IS NULL OR source.status IS DISTINCT FROM 'CLOSED'
  OR source.updated_epoch_second::numeric+source.updated_nano::numeric/1000000000
    >extract(epoch FROM clock_timestamp()-interval '30 days')
 THEN RETURN QUERY SELECT 0::bigint,0::bigint,false,ARRAY['SESSION_NOT_OLD_CLOSED']::text[]; RETURN; END IF;
 reasons:=public.agent_runtime_session_owner_archive_protection(t,s,k,id,u);
 IF cardinality(reasons)>0 THEN RETURN QUERY SELECT 0::bigint,0::bigint,true,reasons; RETURN; END IF;
 -- Only a bounded event page is examined for the per-event protection diagnostic.
 SELECT EXISTS(SELECT 1 FROM (SELECT e.event_id FROM public.agent_runtime_session_events e
  WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id)=(t,s,k,id,u)
  ORDER BY e.session_sequence LIMIT 16) finite
  WHERE public.agent_runtime_session_event_referenced(t,s,k,id,u,finite.event_id)) INTO has_reference;
 IF has_reference THEN reasons:=array_append(reasons,'EVENT_SOURCE_REFERENCE'); END IF;
 IF EXISTS(SELECT 1 FROM (SELECT e.run_id FROM public.agent_runtime_session_events e
  WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id)=(t,s,k,id,u)
   AND e.run_id IS NOT NULL ORDER BY e.session_sequence LIMIT 16) finite
  WHERE NOT EXISTS(SELECT 1 FROM public.agent_runtime_runs r
   WHERE (r.tenant_id,r.run_id,r.session_id,r.target_kind,r.target_id,r.end_user_id)=(t,finite.run_id,s,k,id,u)))
 THEN reasons:=array_append(reasons,'EVENT_RUN_SOURCE_MISSING'); END IF;
 INSERT INTO public.agent_runtime_session_owner_retirements(tenant_id,session_id,target_kind,target_id,end_user_id,
  source_session_revision,closed_epoch_second,closed_nano)
 VALUES(t,s,k,id,u,source.session_revision,source.updated_epoch_second,source.updated_nano)
 ON CONFLICT(tenant_id,target_kind,target_id,session_id) DO NOTHING;
 SELECT h.* INTO progress FROM public.agent_runtime_session_owner_retirements h
  WHERE (h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=(t,s,k,id,u) FOR UPDATE;
 IF (progress.source_session_revision,progress.closed_epoch_second,progress.closed_nano) IS DISTINCT FROM
  (source.session_revision,source.updated_epoch_second,source.updated_nano) THEN RAISE EXCEPTION 'session_archive_progress_source_changed'; END IF;
 p:=CASE WHEN progress.status='RETIRED' THEN 1 ELSE progress.phase END;
 cursor_key:=CASE WHEN progress.status='RETIRED' THEN '{}'::text[] ELSE progress.scan_after_key END;
 WHILE p<=12 AND count_work<maximum LOOP
  SELECT * INTO STRICT spec FROM public.agent_runtime_session_archive_spec(p);
  SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.sort_key),'[]'::jsonb) INTO page
   FROM public.agent_runtime_session_retirement_objects(t,s,k,id,u,p,cursor_key,maximum-count_work) o;
  IF jsonb_array_length(page)=0 THEN p:=p+1; cursor_key:='{}'; CONTINUE; END IF;
  -- Stable order locks only relevant Runs for the finite selected object page.
  FOR run_key IN SELECT DISTINCT value->>'source_run' FROM jsonb_array_elements(page)
   WHERE value->>'source_run' IS NOT NULL ORDER BY 1 LOOP
   PERFORM 1 FROM public.agent_runtime_runs r WHERE r.tenant_id=t AND r.run_id=run_key FOR NO KEY UPDATE;
  END LOOP;
  SELECT string_agg('h.'||quote_ident(x)||'=($2->>'||quote_literal(x)||')',' AND ')
   INTO key_where FROM unnest(spec.key_columns) x;
  FOR item IN SELECT value FROM jsonb_array_elements(page) LOOP
   previous_key:=cursor_key;
   count_work:=count_work+1;
   SELECT array_agg(value ORDER BY ordinality) INTO cursor_key
    FROM jsonb_array_elements_text(item.value->'sort_key') WITH ORDINALITY;
   hot_bytes:=(item.value->>'payload_bytes')::bigint;
   IF hot_bytes>8388608 THEN
    IF NOT 'PAYLOAD_BYTES_LIMIT'=ANY(reasons) THEN reasons:=array_append(reasons,'PAYLOAD_BYTES_LIMIT'); END IF;
    CONTINUE;
   END IF;
   IF work_bytes+hot_bytes>8388608 THEN
    -- Keep this object pending; do not advance its durable cursor until a later batch.
    cursor_key:=previous_key; EXIT;
   END IF;
   -- Run payload compaction changes no key. Preserve KEY SHARE compatibility for
   -- deferred checkpoint Run FKs while a concurrent freeze holds its body lock.
   EXECUTE 'SELECT to_jsonb(h) FROM public.'||quote_ident(spec.hot_table)||' h WHERE h.tenant_id=$1 AND '||key_where||
    CASE WHEN p=4 THEN ' FOR NO KEY UPDATE' ELSE ' FOR UPDATE' END
    INTO original USING t,item.value->'source_key';
   IF original IS NULL THEN CONTINUE; END IF;
   run:=NULL;
   IF item.value->>'source_run' IS NOT NULL THEN
    SELECT r.* INTO run FROM public.agent_runtime_runs r WHERE r.tenant_id=t AND r.run_id=item.value->>'source_run';
    IF (run.tenant_id,run.session_id,run.target_kind,run.target_id,run.end_user_id) IS DISTINCT FROM (t,s,k,id,u)
      OR run.status NOT IN('COMPLETED','TERMINATED','FAILED','CANCELLED')
      OR run.updated_epoch_second::numeric+run.updated_nano::numeric/1000000000
         >extract(epoch FROM clock_timestamp()-interval '30 days')
    THEN RAISE EXCEPTION 'session_archive_run_source_changed'; END IF;
   END IF;
   cold:=original||jsonb_build_object('owner_session_id',s,'owner_target_kind',k,'owner_target_id',id,'owner_end_user_id',u,
    'source_session_revision',source.session_revision,'closed_epoch_second',source.updated_epoch_second,'closed_nano',source.updated_nano,
    'source_run_id',run.run_id,'source_run_revision',run.run_revision,'source_run_updated_epoch_second',run.updated_epoch_second,
    'source_run_updated_nano',run.updated_nano,'original_row_sha256',encode(sha256(convert_to(original::text,'UTF8')),'hex'),
    'archived_at',clock_timestamp());
   IF p=9 THEN
    source_event:=jsonb_populate_record(NULL::public.agent_runtime_session_events,original);
    canonical:=public.agent_runtime_session_event_payload(source_event.tenant_id,source_event.session_id,source_event.target_kind,
     source_event.target_id,source_event.end_user_id,source_event.run_id,source_event.event_id,source_event.event_type,
     source_event.payload_schema_id,source_event.payload_canonical_json);
    IF octet_length(canonical)>8388608 THEN
     IF NOT 'PAYLOAD_BYTES_LIMIT'=ANY(reasons) THEN reasons:=array_append(reasons,'PAYLOAD_BYTES_LIMIT'); END IF; CONTINUE;
    END IF;
    IF work_bytes+greatest(hot_bytes,octet_length(canonical))>8388608 THEN cursor_key:=previous_key; EXIT; END IF;
    cold:=cold||jsonb_build_object('original_payload_canonical_json',original->>'payload_canonical_json',
     'original_event_digest',public.agent_runtime_session_event_digest(source_event),'payload_canonical_json',canonical);
    work_bytes:=work_bytes+greatest(hot_bytes,octet_length(canonical));
   ELSE work_bytes:=work_bytes+hot_bytes;
   END IF;
   FOREACH field IN ARRAY spec.payload_columns LOOP
    cold:=cold||jsonb_build_object(field||'_sha256',CASE WHEN field='payload_data'
     THEN encode(sha256(decode(substring(cold->>field FROM 3),'hex')),'hex')
     ELSE public.agent_runtime_session_archive_text_digest(cold->>field) END);
   END LOOP;
   IF p<12 THEN
    -- JSON is only the typed row constructor; payload_data remains bytea, original text is never parsed.
    EXECUTE format('INSERT INTO public.%I SELECT (jsonb_populate_record(NULL::public.%I,$1)).* ON CONFLICT DO NOTHING',
     spec.cold_table,spec.cold_table) USING cold;
    EXECUTE format('SELECT to_jsonb(h) FROM public.%I h WHERE h.tenant_id=$1 AND %s',spec.cold_table,key_where)
     INTO selected USING t,item.value->'source_key';
    IF public.agent_runtime_session_archive_original_row(selected,spec.payload_columns,p=9) IS DISTINCT FROM original
     THEN RAISE EXCEPTION 'session_archive_source_conflict'; END IF;
   END IF;
   IF p<=9 THEN
    SELECT string_agg(quote_ident(x)||' = public.agent_runtime_session_archive_marker($3->>'||quote_literal(x)||')',',')
     INTO assign_sql FROM unnest(spec.payload_columns) x;
    EXECUTE 'UPDATE public.'||quote_ident(spec.hot_table)||' h SET '||assign_sql||' WHERE h.tenant_id=$1 AND '||key_where
     USING t,item.value->'source_key',cold;
    GET DIAGNOSTICS affected=ROW_COUNT;
    SELECT coalesce(sum(octet_length(public.agent_runtime_session_archive_marker(cold->>x))),0) INTO stored_bytes FROM unnest(spec.payload_columns) x;
    count_rows:=count_rows+affected; count_bytes:=count_bytes+greatest(hot_bytes-stored_bytes,0)*affected;
    IF p=9 THEN DELETE FROM public.agent_runtime_session_event_body_refs r
     WHERE (r.tenant_id,r.target_kind,r.target_id,r.session_id,r.event_id)=(t,k,id,s,original->>'event_id'); END IF;
   ELSIF p=11 THEN
    INSERT INTO public.agent_runtime_session_checkpoint_archive_body_refs(tenant_id,run_id,checkpoint_hash,body_hash)
     SELECT r.tenant_id,r.run_id,r.checkpoint_hash,r.body_hash FROM public.agent_runtime_session_checkpoint_body_refs r
      WHERE (r.tenant_id,r.run_id,r.checkpoint_hash)=(t,original->>'run_id',original->>'content_hash')
     ON CONFLICT DO NOTHING;
    EXECUTE 'DELETE FROM public.'||quote_ident(spec.hot_table)||' h WHERE h.tenant_id=$1 AND '||key_where USING t,item.value->'source_key';
    GET DIAGNOSTICS affected=ROW_COUNT; count_rows:=count_rows+affected; count_bytes:=count_bytes+hot_bytes*affected;
   ELSIF p=12 THEN
    EXECUTE 'DELETE FROM public.'||quote_ident(spec.hot_table)||' h WHERE h.tenant_id=$1 AND '||key_where USING t,item.value->'source_key';
    GET DIAGNOSTICS affected=ROW_COUNT; count_rows:=count_rows+affected; count_bytes:=count_bytes+hot_bytes*affected;
   END IF;
   progress.scan_after_key:=cursor_key;
  END LOOP;
  IF work_bytes>=8388608 THEN EXIT; END IF;
 END LOOP;
 more_work:=public.agent_runtime_session_owner_retirement_pending(t,s,k,id,u);
 UPDATE public.agent_runtime_session_owner_retirements h SET phase=CASE WHEN p>12 THEN 1 ELSE p END,
  scan_after_key=CASE WHEN p>12 THEN '{}'::text[] ELSE cursor_key END,status=CASE WHEN more_work THEN 'PENDING' ELSE 'RETIRED' END,
  retired_rows=h.retired_rows+count_rows,retired_bytes=h.retired_bytes+count_bytes,updated_at=clock_timestamp()
  WHERE (h.tenant_id,h.session_id,h.target_kind,h.target_id,h.end_user_id)=(t,s,k,id,u);
 RETURN QUERY SELECT count_rows,count_bytes,more_work,reasons;
END $$;
COMMENT ON FUNCTION public.agent_runtime_session_retire_owner(text,text,text,text,bigint,integer) IS
 '每Owner最多16对象、8MiB正文工作预算；锁Owner/Session后仅锁有限页相关Run和对象，SQL持久游标续扫；rows/bytes仅实际热缩小或删除，不计复制/引用边';




-- Existing business guards retain their original transitions; only a proved one-way storage change is admitted.
CREATE OR REPLACE FUNCTION public.reject_agent_runtime_session_event_mutation()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE canonical text; data json; name text; operation text; expected text;
BEGIN
    IF TG_OP='UPDATE' AND public.agent_runtime_session_archive_update_valid(TG_TABLE_NAME,to_jsonb(OLD),to_jsonb(NEW)) THEN RETURN NEW; END IF;
 IF TG_OP<>'DELETE' THEN RAISE EXCEPTION 'agent runtime session event is append-only'; END IF;
 IF OLD.event_type NOT IN('agent.model-answer','agent.model-reasoning','agent.runtime-diagnostic')
    THEN RAISE EXCEPTION 'session_history_event_type_forbidden'; END IF;
 PERFORM public.agent_runtime_session_history_closed_guard(OLD);
 IF NOT EXISTS(SELECT 1 FROM public.agent_runtime_session_event_tombstones t
    WHERE (t.tenant_id,t.session_id,t.target_kind,t.target_id,t.end_user_id,t.event_id,t.session_sequence)=
        (OLD.tenant_id,OLD.session_id,OLD.target_kind,OLD.target_id,OLD.end_user_id,OLD.event_id,OLD.session_sequence)
      AND t.event_digest=public.agent_runtime_session_event_digest(OLD)) THEN RAISE EXCEPTION 'session_history_verified_tombstone_required'; END IF;
 canonical := public.agent_runtime_session_event_payload(OLD.tenant_id,OLD.session_id,OLD.target_kind,OLD.target_id,
    OLD.end_user_id,OLD.run_id,OLD.event_id,OLD.event_type,OLD.payload_schema_id,OLD.payload_canonical_json);
 IF OLD.event_type='agent.model-answer' THEN
    PERFORM public.agent_runtime_session_history_answer_guard(OLD);
 ELSIF OLD.event_type='agent.model-reasoning' THEN
    data:=public.agent_runtime_session_history_native_fields(canonical,
        ARRAY['attemptId','batchOrdinal','content','sourceSequenceFrom','sourceSequenceThrough']);
    IF OLD.event_version<>'1' OR OLD.payload_schema_id<>'skillforge.runtime.agent.model-reasoning.v2'
        OR OLD.run_id IS NULL OR OLD.action_id IS NULL OR NOT starts_with(OLD.action_id,'model-action:')
        THEN RAISE EXCEPTION 'session_history_reasoning_source_invalid'; END IF;
    IF btrim(data->>'attemptId')='' OR (data->>'content')='""' OR (data->>'batchOrdinal') !~ '^[1-9][0-9]*$'
        OR (data->>'sourceSequenceFrom') !~ '^[1-9][0-9]*$' OR (data->>'sourceSequenceThrough') !~ '^[1-9][0-9]*$'
        OR (data->>'sourceSequenceFrom')::bigint>(data->>'sourceSequenceThrough')::bigint
        THEN RAISE EXCEPTION 'session_history_reasoning_source_invalid'; END IF;
    operation := substring(OLD.action_id FROM length('model-action:')+1);
    expected := 'model-reasoning:'||encode(sha256(convert_to(
        'skillforge.runtime.agent.model-reasoning-batch-identity.v1'||chr(10)||'{"attemptId":'||to_json(data->>'attemptId')::text||
        ',"batchOrdinal":'||to_json(data->>'batchOrdinal')::text||',"operationKey":'||to_json(operation)::text||'}','UTF8')),'hex');
    IF OLD.event_id IS DISTINCT FROM expected OR canonical IS DISTINCT FROM
        '{"attemptId":'||to_json(data->>'attemptId')::text||',"batchOrdinal":'||to_json(data->>'batchOrdinal')::text||
        ',"content":'||(data->>'content')||',"sourceSequenceFrom":'||to_json(data->>'sourceSequenceFrom')::text||
        ',"sourceSequenceThrough":'||to_json(data->>'sourceSequenceThrough')::text||'}'
        THEN RAISE EXCEPTION 'session_history_reasoning_source_invalid'; END IF;
 ELSE
    data:=canonical::json;
    IF OLD.event_version<>'1' OR OLD.payload_schema_id<>'skillforge.runtime.agent.runtime-diagnostic.v1'
        OR OLD.run_id IS NULL OR OLD.execution_id IS NOT NULL OR OLD.action_id IS NOT NULL OR OLD.delegation_id IS NOT NULL
        OR NOT starts_with(OLD.event_id,'runtime-diagnostic:') OR json_typeof(data)<>'object'
        OR (SELECT count(*) FROM json_object_keys(data))<>3
        OR EXISTS(SELECT 1 FROM json_object_keys(data) key WHERE key NOT IN('kind','metrics','operationKey'))
        OR json_typeof(data->'metrics')<>'object'
        OR OLD.occurred_at_epoch_second::numeric+OLD.occurred_at_nano::numeric/1000000000 > extract(epoch FROM clock_timestamp()-interval '30 days')
        THEN RAISE EXCEPTION 'session_history_diagnostic_source_invalid'; END IF;
    PERFORM public.agent_runtime_session_history_string((data->'kind')::text),public.agent_runtime_session_history_string((data->'operationKey')::text);
    IF btrim(data->>'kind')='' OR btrim(data->>'operationKey')='' OR OLD.event_fingerprint IS DISTINCT FROM
        encode(sha256(convert_to(OLD.payload_schema_id||chr(10)||canonical,'UTF8')),'hex')
        OR canonical IS DISTINCT FROM '{"kind":'||to_json(data->>'kind')::text||',"metrics":'||(data->'metrics')::text||
        ',"operationKey":'||to_json(data->>'operationKey')::text||'}'
        THEN RAISE EXCEPTION 'session_history_diagnostic_source_invalid'; END IF;
 END IF;
 RETURN OLD;
END $$;

CREATE OR REPLACE FUNCTION public.reject_agent_runtime_session_private_journal_mutation()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND public.agent_runtime_session_archive_update_valid(TG_TABLE_NAME,to_jsonb(OLD),to_jsonb(NEW)) THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'agent runtime session private journal is append-only';
    RETURN NULL;
END;
$$;

-- Cold fixed-field rows retain their exact hot identity anchors. Checkpoint payload ownership remains the original Run.
ALTER TABLE public.agent_runtime_input_ledger_archives ADD CONSTRAINT input_ledger_archives_hot_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,input_id) REFERENCES public.agent_runtime_input_ledger(tenant_id,target_kind,target_id,session_id,input_id) ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_session_admission_prepare_archives ADD CONSTRAINT session_admission_prepare_archives_hot_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,input_id) REFERENCES public.agent_runtime_session_admission_prepares(tenant_id,target_kind,target_id,session_id,input_id) ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_session_commit_journal_archives ADD CONSTRAINT session_commit_journal_archives_hot_fk FOREIGN KEY(tenant_id,run_id,operation_hash) REFERENCES public.agent_runtime_session_commit_journals(tenant_id,run_id,operation_hash) ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_run_record_archives ADD CONSTRAINT run_record_archives_hot_fk FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id) ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_session_inbox_archives ADD CONSTRAINT session_inbox_archives_hot_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,input_id) REFERENCES public.agent_runtime_session_inbox(tenant_id,target_kind,target_id,session_id,input_id) ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_run_mutation_archives ADD CONSTRAINT run_mutation_archives_hot_fk FOREIGN KEY(tenant_id,run_id,mutation_key) REFERENCES public.agent_runtime_run_mutations(tenant_id,run_id,mutation_key) ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_session_inbox_mutation_archives ADD CONSTRAINT session_inbox_mutation_archives_hot_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,input_id,mutation_key) REFERENCES public.agent_runtime_inbox_mutations(tenant_id,target_kind,target_id,session_id,input_id,mutation_key) ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_run_publication_mutation_archives ADD CONSTRAINT run_publication_mutation_archives_hot_fk FOREIGN KEY(tenant_id,source_id,event_id,mutation_key) REFERENCES public.agent_runtime_run_publication_mutations(tenant_id,source_id,event_id,mutation_key) ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_session_event_payload_archives ADD CONSTRAINT session_event_payload_archives_hot_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,event_id) REFERENCES public.agent_runtime_session_events(tenant_id,target_kind,target_id,session_id,event_id) ON DELETE RESTRICT;
ALTER TABLE public.agent_runtime_session_checkpoint_body_archives ADD CONSTRAINT session_checkpoint_body_archives_run_fk FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.agent_runtime_session_checkpoint_archives ADD CONSTRAINT session_checkpoint_archives_run_fk FOREIGN KEY(tenant_id,run_id) REFERENCES public.agent_runtime_runs(tenant_id,run_id) DEFERRABLE INITIALLY DEFERRED;

COMMENT ON FUNCTION public.reject_agent_runtime_session_event_mutation() IS
 '保留原展示历史DELETE和墓碑核验；UPDATE仅允许同源归档证明、完整Owner/Run/关闭事实、无引用的单向正文存储表示替换';
COMMENT ON FUNCTION public.agent_runtime_session_checkpoint_retirement_guard() IS
 '热checkpoint删除要求精确冷证明；热正文已有cold必须匹配并无热引用，无cold且无热引用保留原孤儿GC；重复exact cold不复活热正文';
DO $comments$
DECLARE archive_name text; col record; index_row record;
BEGIN
 FOR archive_name IN SELECT DISTINCT cold_table FROM public.agent_runtime_session_archive_spec(1)
 UNION SELECT DISTINCT cold_table FROM public.agent_runtime_session_archive_spec(2)
 UNION SELECT DISTINCT cold_table FROM public.agent_runtime_session_archive_spec(3)
 UNION SELECT DISTINCT cold_table FROM public.agent_runtime_session_archive_spec(4)
 UNION SELECT DISTINCT cold_table FROM public.agent_runtime_session_archive_spec(5)
 UNION SELECT DISTINCT cold_table FROM public.agent_runtime_session_archive_spec(6)
 UNION SELECT DISTINCT cold_table FROM public.agent_runtime_session_archive_spec(7)
 UNION SELECT DISTINCT cold_table FROM public.agent_runtime_session_archive_spec(8)
 UNION SELECT DISTINCT cold_table FROM public.agent_runtime_session_archive_spec(9)
 UNION SELECT DISTINCT cold_table FROM public.agent_runtime_session_archive_spec(10)
 UNION SELECT DISTINCT cold_table FROM public.agent_runtime_session_archive_spec(11)
 UNION SELECT 'agent_runtime_session_owner_retirements'
 UNION SELECT 'agent_runtime_session_checkpoint_archive_body_refs' LOOP
  FOR col IN SELECT a.attname FROM pg_attribute a WHERE a.attrelid=('public.'||archive_name)::regclass AND a.attnum>0 AND NOT a.attisdropped LOOP
   EXECUTE format('COMMENT ON COLUMN public.%I.%I IS %L',archive_name,col.attname,
    CASE col.attname
     WHEN 'owner_session_id' THEN '原完整Owner Session身份；不可跨Owner还原'
     WHEN 'owner_target_kind' THEN '原Owner目标种类'
     WHEN 'owner_target_id' THEN '原Owner目标标识'
     WHEN 'owner_end_user_id' THEN '原可信最终用户身份'
     WHEN 'source_session_revision' THEN '原权威CLOSED Session修订，恢复和收缩共同核验'
     WHEN 'closed_epoch_second' THEN '原权威CLOSED更新时间秒，满30日后才可收缩'
     WHEN 'closed_nano' THEN '原权威CLOSED更新时间纳秒，不截断'
     WHEN 'source_run_id' THEN '相关原Run身份；Session级事实允许空'
     WHEN 'source_run_revision' THEN '相关终态Run精确修订'
     WHEN 'source_run_updated_epoch_second' THEN '相关终态Run更新时间秒，自身满30日才可收缩'
     WHEN 'source_run_updated_nano' THEN '相关终态Run更新时间纳秒'
     WHEN 'original_row_sha256' THEN '原完整物理行规范JSON SHA-256，包含所有原标量与原文本'
     WHEN 'original_payload_canonical_json' THEN '归档前原事件物理lexical文本，仅作原热行证明；恢复采用完整canonical正文'
     WHEN 'archived_at' THEN '首次冷复制数据库时间，immutable且不代表热收缩已完成'
     WHEN 'phase' THEN 'SQL持久固定对象阶段；独立于Java Owner轮转游标'
     WHEN 'scan_after_key' THEN '固定来源精确键有序续扫；完成轮次重启可发现后续合法冻结事实'
     WHEN 'retired_rows' THEN '实际热行正文表示替换或checkpoint/body热删除数量，不含冷复制和引用边'
     WHEN 'retired_bytes' THEN '实际热正文UTF8/bytea释放字节，不宣称净数据库或文件系统减少'
     ELSE CASE WHEN col.attname LIKE '%_sha256' THEN '原正文精确字节摘要，恢复必须核验'
      ELSE '固定来源原类型及原值；身份、修订、正文和引用保持精确' END
    END);
  END LOOP;
  FOR index_row IN SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
   WHERE i.indrelid=('public.'||archive_name)::regclass LOOP
   EXECUTE format('COMMENT ON INDEX public.%I IS %L',index_row.relname,'固定归档原身份唯一性或Owner定向读取索引');
  END LOOP;
 END LOOP;
END $comments$;


CREATE OR REPLACE FUNCTION public.guard_agent_runtime_input_ledger()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND public.agent_runtime_session_archive_update_valid(TG_TABLE_NAME,to_jsonb(OLD),to_jsonb(NEW)) THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'agent runtime input ledger is immutable';
END;
$$;

CREATE OR REPLACE FUNCTION public.guard_agent_runtime_session_inbox()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND public.agent_runtime_session_archive_update_valid(TG_TABLE_NAME,to_jsonb(OLD),to_jsonb(NEW)) THEN RETURN NEW; END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime inbox entry is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.session_id, NEW.target_kind, NEW.target_id, NEW.agent_id, NEW.end_user_id,
        NEW.input_id, NEW.inbox_sequence, NEW.created_epoch_second, NEW.created_nano)
       <> (OLD.tenant_id, OLD.session_id, OLD.target_kind, OLD.target_id, OLD.agent_id, OLD.end_user_id,
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

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_run_mutation_journaled()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND public.agent_runtime_session_archive_update_valid(TG_TABLE_NAME,to_jsonb(OLD),to_jsonb(NEW)) THEN RETURN NULL; END IF;
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

CREATE OR REPLACE FUNCTION public.agent_runtime_session_event_payload(requested_tenant text, requested_session text,
    requested_target_kind text, requested_target text, requested_end_user bigint, requested_run text,
    requested_event text, requested_type text, requested_schema text, stored text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE reference record; restored text := stored; expected_digest text; resolved integer := 0; token text;
BEGIN
    IF public.agent_runtime_session_archive_is_marker(stored) THEN
        RETURN public.agent_runtime_session_archived_event_payload(requested_tenant,requested_session,
            requested_target_kind,requested_target,requested_end_user,requested_event,stored);
    END IF;
    IF requested_type<>'memory.written' OR requested_schema<>'skillforge.runtime.memory.written.v1'
       OR NOT stored ~ '"canonicalJson":"@runtime-session-content:[a-f0-9]{64}"' THEN
        RETURN public.agent_runtime_session_output_payload(requested_tenant,requested_session,requested_target_kind,
            requested_target,requested_end_user,requested_run,requested_schema,stored);
    END IF;
    FOR reference IN SELECT body.content_hash,body.canonical_content,ref.original_payload_digest
        FROM public.agent_runtime_session_event_body_refs ref JOIN public.agent_runtime_session_event_bodies body
            ON (body.tenant_id,body.target_kind,body.target_id,body.session_id,body.content_hash)
                =(ref.tenant_id,ref.target_kind,ref.target_id,ref.session_id,ref.content_hash)
        WHERE ref.tenant_id=requested_tenant AND ref.target_kind=requested_target_kind AND ref.target_id=requested_target
            AND ref.session_id=requested_session AND ref.event_id=requested_event AND body.end_user_id=requested_end_user LOOP
        IF expected_digest IS NOT NULL AND expected_digest<>reference.original_payload_digest THEN
            RAISE EXCEPTION 'session_event_body_reference_rejected';
        END IF;
        expected_digest := reference.original_payload_digest;
        IF encode(sha256(convert_to('skillforge.runtime.session.checkpoint-text.v1','UTF8')||decode('00','hex')||convert_to(reference.canonical_content,'UTF8')),'hex')
           <>reference.content_hash THEN RAISE EXCEPTION 'session_event_body_reference_rejected'; END IF;
        token := '"canonicalJson":"@runtime-session-content:'||reference.content_hash||'"';
        IF strpos(restored,token)=0 THEN RAISE EXCEPTION 'session_event_body_reference_rejected'; END IF;
        restored := replace(restored,token,'"canonicalJson":'||reference.canonical_content);
        resolved := resolved+1;
    END LOOP;
    IF resolved=0 OR restored ~ '"canonicalJson":"@runtime-session-content:[a-f0-9]{64}"'
       OR encode(sha256(convert_to(requested_schema,'UTF8')||decode('00','hex')||convert_to(restored,'UTF8')),'hex')
          IS DISTINCT FROM expected_digest THEN RAISE EXCEPTION 'session_event_body_reference_rejected'; END IF;
    RETURN restored;
END;
$$;

CREATE OR REPLACE FUNCTION public.guard_agent_runtime_session_mutation_journal()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND public.agent_runtime_session_archive_update_valid(TG_TABLE_NAME,to_jsonb(OLD),to_jsonb(NEW)) THEN RETURN NEW; END IF;
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'agent runtime session mutation journal is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.agent_runtime_session_event_referenced(t text,s text,k text,id text,u bigint,eid text)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE referenced boolean; source_sequence bigint; source_run text;
BEGIN
 IF t IS DISTINCT FROM current_setting('app.tenant_id',true) THEN RAISE EXCEPTION 'session_history_tenant_required'; END IF;
 SELECT e.session_sequence,e.run_id INTO source_sequence,source_run FROM public.agent_runtime_session_events e
    WHERE ROW(e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.event_id)=ROW(t,s,k,id,u,eid);
 SELECT EXISTS(SELECT 1 FROM public.agent_runtime_session_events a WHERE a.tenant_id=t AND a.caused_by_event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_session_outbox a
    WHERE (a.tenant_id,a.session_id,a.target_kind,a.target_id,a.end_user_id,a.event_id)=(t,s,k,id,u,eid))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_conversation_task_operations a
    WHERE (a.tenant_id,a.session_id,a.target_kind,a.target_id,a.event_id)=(t,s,k,id,eid))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_conversation_tasks a WHERE a.tenant_id=t AND a.end_user_id=u
    AND (a.schema_id<>'skillforge.conversation-task.snapshot.v2'
        OR public.agent_runtime_session_task_snapshot_referenced(a.snapshot,t,s,k,id,u,eid,source_sequence)))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_session_events a WHERE a.tenant_id=t AND a.end_user_id=u
    AND a.payload_schema_id='skillforge.conversation-task.snapshot.v2'
    AND public.agent_runtime_session_task_snapshot_referenced(public.agent_runtime_session_archived_event_payload(a.tenant_id,a.session_id,a.target_kind,a.target_id,a.end_user_id,a.event_id,a.payload_canonical_json),t,s,k,id,u,eid,source_sequence))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_session_tool_projections a
    WHERE a.tenant_id=t AND a.run_id=source_run AND a.program_event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_memory_fact_sources a
    WHERE (a.tenant_id,a.source_session_id,a.source_target_kind,a.source_target_id,a.source_event_id)=(t,s,k,id,eid))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_memory_extraction_jobs a
    WHERE (a.tenant_id,a.source_session_id,a.source_target_kind,a.source_target_id,a.source_event_id)=(t,s,k,id,eid))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_memory_extraction_evidence a
    WHERE (a.tenant_id,a.source_session_id,a.source_target_kind,a.source_target_id,a.source_event_id)=(t,s,k,id,eid))
 OR EXISTS(SELECT 1 FROM public.agent_runtime_run_state_publications a WHERE a.tenant_id=t AND a.event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_execution_state_publications a WHERE a.tenant_id=t AND a.event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_action_state_publications a WHERE a.tenant_id=t AND a.event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_event_deliveries a WHERE a.tenant_id=t AND a.event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_event_conflict_audits a WHERE a.tenant_id=t AND a.event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_delegations a WHERE a.tenant_id=t AND a.parent_caused_by_event_id=eid)
 OR EXISTS(SELECT 1 FROM public.agent_runtime_delegation_waits a WHERE a.tenant_id=t AND a.parent_caused_by_event_id=eid)
 OR public.agent_runtime_session_event_host_referenced(t,s,eid)
 INTO referenced;
 RETURN referenced;
END $$;

CREATE OR REPLACE FUNCTION public.agent_runtime_session_history_answer_guard(event public.agent_runtime_session_events) RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY INVOKER AS $$
DECLARE first public.agent_runtime_session_events; last public.agent_runtime_session_events; adopted public.agent_runtime_session_events;
    a json; b json; c json; d json; raw text; operation text; attempt text; name text;
BEGIN
 b := public.agent_runtime_session_history_snapshot(event);
 SELECT e.* INTO first FROM public.agent_runtime_session_events e
    WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.run_id) =
        (event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id,event.run_id)
      AND e.event_type='agent.model-answer' AND substring(public.agent_runtime_session_archived_event_payload(e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.event_id,e.payload_canonical_json) FROM '"messageId":"([^"]+)"')=b->>'messageId'
    ORDER BY e.session_sequence LIMIT 1;
 SELECT e.* INTO last FROM public.agent_runtime_session_events e
    WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.run_id) =
        (event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id,event.run_id)
      AND e.event_type='agent.model-answer' AND substring(public.agent_runtime_session_archived_event_payload(e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.event_id,e.payload_canonical_json) FROM '"messageId":"([^"]+)"')=b->>'messageId'
    ORDER BY e.session_sequence DESC LIMIT 1;
 SELECT e.* INTO adopted FROM public.agent_runtime_session_events e
    WHERE (e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.run_id) =
        (event.tenant_id,event.session_id,event.target_kind,event.target_id,event.end_user_id,event.run_id)
      AND e.event_type='agent.model-answer-adopted' AND substring(public.agent_runtime_session_archived_event_payload(e.tenant_id,e.session_id,e.target_kind,e.target_id,e.end_user_id,e.event_id,e.payload_canonical_json) FROM '"messageId":"([^"]+)"')=b->>'messageId'
    ORDER BY e.session_sequence LIMIT 1;
 IF first.event_id IS NULL OR last.event_id IS NULL OR adopted.event_id IS NULL
    OR NOT (first.session_sequence<event.session_sequence AND event.session_sequence<last.session_sequence
        AND last.session_sequence<adopted.session_sequence) THEN RAISE EXCEPTION 'session_history_answer_endpoints_required'; END IF;
 IF ROW(first.agent_id,first.input_id,first.run_id,first.execution_id,first.action_id,first.delegation_id,first.trace_id,first.span_id,first.root_input_id,first.caused_by_event_id)
    IS DISTINCT FROM ROW(event.agent_id,event.input_id,event.run_id,event.execution_id,event.action_id,event.delegation_id,event.trace_id,event.span_id,event.root_input_id,event.caused_by_event_id)
    OR ROW(last.agent_id,last.input_id,last.run_id,last.execution_id,last.action_id,last.delegation_id,last.trace_id,last.span_id,last.root_input_id,last.caused_by_event_id)
    IS DISTINCT FROM ROW(event.agent_id,event.input_id,event.run_id,event.execution_id,event.action_id,event.delegation_id,event.trace_id,event.span_id,event.root_input_id,event.caused_by_event_id)
    OR ROW(adopted.agent_id,adopted.input_id,adopted.run_id,adopted.execution_id,adopted.action_id,adopted.delegation_id,adopted.trace_id,adopted.span_id,adopted.root_input_id,adopted.caused_by_event_id)
    IS DISTINCT FROM ROW(event.agent_id,event.input_id,event.run_id,event.execution_id,event.action_id,event.delegation_id,event.trace_id,event.span_id,event.root_input_id,event.caused_by_event_id)
    THEN RAISE EXCEPTION 'session_history_answer_source_mismatch'; END IF;
 a := public.agent_runtime_session_history_snapshot(first); c := public.agent_runtime_session_history_snapshot(last);
 raw := public.agent_runtime_session_event_payload(adopted.tenant_id,adopted.session_id,adopted.target_kind,adopted.target_id,
    adopted.end_user_id,adopted.run_id,adopted.event_id,adopted.event_type,adopted.payload_schema_id,adopted.payload_canonical_json);
 d := public.agent_runtime_session_history_native_fields(raw,
    ARRAY['adoptedFactRef','attemptId','content','messageId','modelResultEventId','operationKey','phase']);
 IF adopted.event_version<>'2' OR adopted.payload_schema_id<>'skillforge.runtime.agent.model-answer-adopted.v2'
    THEN RAISE EXCEPTION 'session_history_adoption_source_invalid'; END IF;
 operation := d->>'operationKey'; attempt := d->>'attemptId';
 IF btrim(d->>'adoptedFactRef')='' OR btrim(d->>'modelResultEventId')='' OR (d->>'phase') NOT IN('commentary','final')
    OR adopted.event_id IS DISTINCT FROM 'model-answer-adopted:'||encode(sha256(convert_to(
        'skillforge.runtime.agent.model-answer-adopted.v2'||chr(10)||'{"attemptId":'||to_json(attempt)::text||
        ',"operationKey":'||to_json(operation)::text||'}','UTF8')),'hex')
    OR a->>'messageId' IS DISTINCT FROM b->>'messageId' OR b->>'messageId' IS DISTINCT FROM c->>'messageId'
    OR c->>'messageId' IS DISTINCT FROM d->>'messageId'
    OR a->>'operationKey' IS DISTINCT FROM operation OR b->>'operationKey' IS DISTINCT FROM operation OR c->>'operationKey' IS DISTINCT FROM operation
    OR a->>'attemptId' IS DISTINCT FROM attempt OR b->>'attemptId' IS DISTINCT FROM attempt OR c->>'attemptId' IS DISTINCT FROM attempt
    OR NOT ((a->>'messageVersion')::bigint<(b->>'messageVersion')::bigint AND (b->>'messageVersion')::bigint<(c->>'messageVersion')::bigint)
    OR (a->>'sourceSequenceThrough')::bigint>(b->>'sourceSequenceThrough')::bigint OR (b->>'sourceSequenceThrough')::bigint>(c->>'sourceSequenceThrough')::bigint
    OR NOT starts_with(left((b->>'content'),length((b->>'content'))-1),left((a->>'content'),length((a->>'content'))-1))
    OR NOT starts_with(left((c->>'content'),length((c->>'content'))-1),left((b->>'content'),length((b->>'content'))-1))
    OR NOT starts_with(left((d->>'content'),length((d->>'content'))-1),left((c->>'content'),length((c->>'content'))-1))
    OR (a->>'phase'<>'pending' AND a->>'phase' IS DISTINCT FROM b->>'phase')
    OR (b->>'phase'<>'pending' AND b->>'phase' IS DISTINCT FROM c->>'phase')
    OR (c->>'phase'<>'pending' AND c->>'phase' IS DISTINCT FROM d->>'phase')
    THEN RAISE EXCEPTION 'session_history_adoption_source_mismatch'; END IF;
 IF raw IS DISTINCT FROM '{"adoptedFactRef":'||to_json(d->>'adoptedFactRef')::text||',"attemptId":'||to_json(attempt)::text||
    ',"content":'||(d->>'content')||',"messageId":'||to_json(d->>'messageId')::text||
    ',"modelResultEventId":'||to_json(d->>'modelResultEventId')::text||',"operationKey":'||to_json(operation)::text||
    ',"phase":'||to_json(d->>'phase')::text||'}' THEN RAISE EXCEPTION 'session_history_adoption_source_invalid'; END IF;
END $$;

CREATE OR REPLACE FUNCTION public.guard_agent_runtime_run()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND public.agent_runtime_session_archive_update_valid(TG_TABLE_NAME,to_jsonb(OLD),to_jsonb(NEW)) THEN RETURN NEW; END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'agent runtime run is not deletable';
    END IF;
    IF (NEW.tenant_id, NEW.session_id, NEW.target_kind, NEW.target_id, NEW.agent_id, NEW.end_user_id,
        NEW.run_id, NEW.causation_input_id, NEW.created_epoch_second, NEW.created_nano)
       <> (OLD.tenant_id, OLD.session_id, OLD.target_kind, OLD.target_id, OLD.agent_id, OLD.end_user_id,
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

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_inbox_mutation_journaled()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND public.agent_runtime_session_archive_update_valid(TG_TABLE_NAME,to_jsonb(OLD),to_jsonb(NEW)) THEN RETURN NULL; END IF;
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

