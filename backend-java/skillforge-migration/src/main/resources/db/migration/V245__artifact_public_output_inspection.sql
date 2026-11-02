-- 公开文件须有绑定实际字节与检查范围的不可变凭据；既有未检查文件不补写通过结论。
CREATE TABLE nw_execution_artifact_public_inspections (
    tenant_id bigint NOT NULL,
    artifact_id text NOT NULL,
    path text NOT NULL,
    size bigint NOT NULL CHECK(size >= 0),
    sha256 text NOT NULL CHECK(length(sha256) = 64),
    scope_digest text NOT NULL CHECK(length(scope_digest) = 64),
    policy_version text NOT NULL,
    inspected_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,artifact_id,path,sha256,scope_digest,policy_version),
    FOREIGN KEY(tenant_id,artifact_id,path) REFERENCES nw_execution_artifact_files(tenant_id,artifact_id,path) ON DELETE CASCADE
);
ALTER TABLE nw_execution_artifact_public_inspections ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_execution_artifact_public_inspections FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON nw_execution_artifact_public_inspections
USING (tenant_id = nullif(current_setting('app.tenant_id',true),'')::bigint)
WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id',true),'')::bigint);
CREATE TRIGGER artifact_public_inspection_immutable BEFORE UPDATE ON nw_execution_artifact_public_inspections
FOR EACH ROW EXECUTE FUNCTION reject_deliverable_content_update();
