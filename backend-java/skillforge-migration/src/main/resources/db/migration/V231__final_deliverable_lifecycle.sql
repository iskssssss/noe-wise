-- 执行文件与正式成果分开：正文不可变，文件有效性由存续引用决定。
CREATE TABLE nw_execution_artifact_files (
    tenant_id bigint NOT NULL, id text NOT NULL, artifact_id text NOT NULL, path text NOT NULL,
    size bigint NOT NULL CHECK(size >= 0), sha256 text NOT NULL CHECK(length(sha256)=64),
    expires_at timestamptz NOT NULL, status text NOT NULL DEFAULT 'ready' CHECK(status IN ('ready','expired','deleted')),
    PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,artifact_id,path),
    FOREIGN KEY(tenant_id,artifact_id) REFERENCES nw_execution_artifacts(tenant_id,id) ON DELETE CASCADE
);
INSERT INTO nw_execution_artifact_files(tenant_id,id,artifact_id,path,size,sha256,expires_at,status)
SELECT a.tenant_id,a.id||':'||(f->>'path'),a.id,f->>'path',(f->>'size')::bigint,f->>'sha256',a.expires_at,
       CASE WHEN a.status='ready' THEN 'ready' ELSE 'expired' END
FROM nw_execution_artifacts a CROSS JOIN LATERAL jsonb_array_elements(a.manifest) f;

CREATE TABLE nw_task_output_plans (
    tenant_id bigint NOT NULL, run_id text NOT NULL, session_id text NOT NULL, owner_session_id text NOT NULL,
    run_identity jsonb NOT NULL, state text NOT NULL CHECK(state IN ('planned','finalizing','finalized','abandoned')),
    plan jsonb NOT NULL, frozen jsonb, frozen_hash text, abandonment_reason text,
    goal_completion_state text CHECK(goal_completion_state IN ('pending','settled','superseded')),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,run_id)
);
CREATE INDEX skillforge_task_output_plans_goal_completion_pending ON nw_task_output_plans(tenant_id,updated_at)
    WHERE goal_completion_state='pending';
CREATE TABLE nw_task_results (
    tenant_id bigint NOT NULL, run_id text NOT NULL, session_id text NOT NULL,
    status text NOT NULL CHECK(status IN ('pending','published','failed','cancelled')),
    reason text, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
    PRIMARY KEY(tenant_id,run_id), FOREIGN KEY(tenant_id,run_id) REFERENCES nw_task_output_plans(tenant_id,run_id)
);
CREATE TABLE nw_deliverables (
    tenant_id bigint NOT NULL, id text NOT NULL, session_id text NOT NULL, logical_key text NOT NULL,
    current_revision_id text, reserved_run_id text, created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,session_id,logical_key)
);
CREATE TABLE nw_deliverable_revisions (
    tenant_id bigint NOT NULL, id text NOT NULL, deliverable_id text NOT NULL, revision_no integer NOT NULL CHECK(revision_no>0),
    run_id text NOT NULL, previous_revision_id text, title text NOT NULL, kind text NOT NULL,
    fingerprint text NOT NULL, change_type text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,deliverable_id,revision_no),
    FOREIGN KEY(tenant_id,deliverable_id) REFERENCES nw_deliverables(tenant_id,id),
    FOREIGN KEY(tenant_id,run_id) REFERENCES nw_task_results(tenant_id,run_id),
    FOREIGN KEY(tenant_id,previous_revision_id) REFERENCES nw_deliverable_revisions(tenant_id,id)
);
ALTER TABLE nw_deliverables ADD FOREIGN KEY(tenant_id,current_revision_id) REFERENCES nw_deliverable_revisions(tenant_id,id);
CREATE TABLE nw_deliverable_representations (
    tenant_id bigint NOT NULL, id text NOT NULL, revision_id text NOT NULL, role text NOT NULL CHECK(role IN ('source','export','bundle')),
    format text NOT NULL, primary_preview boolean NOT NULL, entry_path text, source_hash text,
    PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,revision_id) REFERENCES nw_deliverable_revisions(tenant_id,id)
);
CREATE UNIQUE INDEX deliverable_one_primary ON nw_deliverable_representations(tenant_id,revision_id) WHERE primary_preview;
CREATE TABLE nw_deliverable_representation_files (
    tenant_id bigint NOT NULL, representation_id text NOT NULL, file_id text NOT NULL, relative_path text NOT NULL,
    download_name text NOT NULL, ordinal integer NOT NULL,
    PRIMARY KEY(tenant_id,representation_id,relative_path),
    FOREIGN KEY(tenant_id,representation_id) REFERENCES nw_deliverable_representations(tenant_id,id),
    FOREIGN KEY(tenant_id,file_id) REFERENCES nw_execution_artifact_files(tenant_id,id)
);
CREATE TABLE nw_deliverable_representation_sources (
    tenant_id bigint NOT NULL, representation_id text NOT NULL, file_id text NOT NULL, sha256 text NOT NULL,
    PRIMARY KEY(tenant_id,representation_id,file_id),
    FOREIGN KEY(tenant_id,representation_id) REFERENCES nw_deliverable_representations(tenant_id,id),
    FOREIGN KEY(tenant_id,file_id) REFERENCES nw_execution_artifact_files(tenant_id,id)
);
CREATE TABLE nw_task_result_deliverables (
    tenant_id bigint NOT NULL, run_id text NOT NULL, revision_id text NOT NULL, ordinal integer NOT NULL,
    PRIMARY KEY(tenant_id,run_id,revision_id),
    FOREIGN KEY(tenant_id,run_id) REFERENCES nw_task_results(tenant_id,run_id),
    FOREIGN KEY(tenant_id,revision_id) REFERENCES nw_deliverable_revisions(tenant_id,id)
);
CREATE TABLE nw_task_evidence (
    tenant_id bigint NOT NULL, id text NOT NULL, run_id text NOT NULL, file_id text NOT NULL, summary text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL DEFAULT now()+interval '7 days',
    PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,run_id) REFERENCES nw_task_results(tenant_id,run_id),
    FOREIGN KEY(tenant_id,file_id) REFERENCES nw_execution_artifact_files(tenant_id,id)
);
CREATE TABLE nw_deliverable_file_holds (
    tenant_id bigint NOT NULL, run_id text NOT NULL, file_id text NOT NULL,
    PRIMARY KEY(tenant_id,run_id,file_id),
    FOREIGN KEY(tenant_id,run_id) REFERENCES nw_task_output_plans(tenant_id,run_id),
    FOREIGN KEY(tenant_id,file_id) REFERENCES nw_execution_artifact_files(tenant_id,id)
);
CREATE INDEX skillforge_task_output_plans_pending ON nw_task_output_plans(state,updated_at) WHERE state IN ('planned','finalizing');
CREATE INDEX deliverable_file_references ON nw_deliverable_representation_files(tenant_id,file_id);
CREATE INDEX artifact_file_expiry ON nw_execution_artifact_files(tenant_id,expires_at) WHERE status<>'deleted';
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['nw_execution_artifact_files','nw_task_output_plans','nw_task_results','nw_deliverables','nw_deliverable_revisions',
      'nw_deliverable_representations','nw_deliverable_representation_files','nw_deliverable_representation_sources','nw_task_result_deliverables','nw_task_evidence','nw_deliverable_file_holds'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
        EXECUTE format('CREATE POLICY tenant_scope ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::bigint) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::bigint)',t);
    END LOOP;
END $$;

-- 已公开的正文关系禁止原地更新；删除仍由按引用的会话清理流程负责。
CREATE FUNCTION reject_deliverable_content_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'published_deliverable_content_is_immutable';
END $$;
CREATE TRIGGER deliverable_revision_immutable BEFORE UPDATE ON nw_deliverable_revisions
FOR EACH ROW EXECUTE FUNCTION reject_deliverable_content_update();
CREATE TRIGGER deliverable_representation_immutable BEFORE UPDATE ON nw_deliverable_representations
FOR EACH ROW EXECUTE FUNCTION reject_deliverable_content_update();
CREATE TRIGGER deliverable_file_reference_immutable BEFORE UPDATE ON nw_deliverable_representation_files
FOR EACH ROW EXECUTE FUNCTION reject_deliverable_content_update();
CREATE TRIGGER deliverable_source_reference_immutable BEFORE UPDATE ON nw_deliverable_representation_sources
FOR EACH ROW EXECUTE FUNCTION reject_deliverable_content_update();
CREATE INDEX deliverable_source_references ON nw_deliverable_representation_sources(tenant_id,file_id);

-- 文件集合也是版本正文；已发布后不能通过追加成员偷偷改版。
CREATE FUNCTION guard_deliverable_content_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE revision_key text; result_state text;
BEGIN
    IF TG_TABLE_NAME='nw_deliverable_representations' THEN
        revision_key := NEW.revision_id;
    ELSE
        SELECT revision_id INTO revision_key FROM nw_deliverable_representations
        WHERE tenant_id=NEW.tenant_id AND id=NEW.representation_id;
    END IF;
    SELECT t.status INTO result_state FROM nw_deliverable_revisions r JOIN nw_task_results t
      ON t.tenant_id=r.tenant_id AND t.run_id=r.run_id
      WHERE r.tenant_id=NEW.tenant_id AND r.id=revision_key;
    IF result_state IS DISTINCT FROM 'pending' THEN
        RAISE EXCEPTION 'published_deliverable_content_is_immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deliverable_representation_insert BEFORE INSERT ON nw_deliverable_representations
FOR EACH ROW EXECUTE FUNCTION guard_deliverable_content_insert();
CREATE TRIGGER deliverable_file_reference_insert BEFORE INSERT ON nw_deliverable_representation_files
FOR EACH ROW EXECUTE FUNCTION guard_deliverable_content_insert();
CREATE TRIGGER deliverable_source_reference_insert BEFORE INSERT ON nw_deliverable_representation_sources
FOR EACH ROW EXECUTE FUNCTION guard_deliverable_content_insert();
