ALTER TABLE nw_execution_artifacts
    ADD COLUMN inputs_sha256 char(64) NOT NULL;
COMMENT ON COLUMN nw_execution_artifacts.inputs_sha256 IS '按来源产物ID与路径排序的artifact_inputs关系摘要，用于调用重放冲突校验';
ALTER TABLE nw_execution_artifacts
    ADD CONSTRAINT skillforge_execution_artifacts_tenant_id_id_unique UNIQUE (tenant_id, id);

CREATE TABLE nw_execution_artifact_inputs (
    tenant_id bigint NOT NULL REFERENCES nw_tenants(id),
    artifact_id varchar(36) NOT NULL,
    input_index integer NOT NULL CHECK (input_index >= 0),
    source_artifact_id varchar(36) NOT NULL,
    source_path text NOT NULL,
    source_size bigint NOT NULL CHECK (source_size >= 0),
    source_sha256 char(64) NOT NULL,
    PRIMARY KEY (artifact_id, input_index),
    UNIQUE (artifact_id, source_artifact_id, source_path),
    FOREIGN KEY (tenant_id, artifact_id) REFERENCES nw_execution_artifacts(tenant_id, id),
    FOREIGN KEY (tenant_id, source_artifact_id) REFERENCES nw_execution_artifacts(tenant_id, id)
);
COMMENT ON TABLE nw_execution_artifact_inputs IS '不可变产物版本的显式多父来源图；父产物到期不改变已发布子文件';
COMMENT ON COLUMN nw_execution_artifact_inputs.tenant_id IS '子产物与来源产物共同所属租户';
COMMENT ON COLUMN nw_execution_artifact_inputs.artifact_id IS '已发布子产物ID';
COMMENT ON COLUMN nw_execution_artifact_inputs.input_index IS '规范排序后的稳定位置';
COMMENT ON COLUMN nw_execution_artifact_inputs.source_artifact_id IS '执行输入引用的父产物ID';
COMMENT ON COLUMN nw_execution_artifact_inputs.source_path IS '父产物清单内的相对路径';
COMMENT ON COLUMN nw_execution_artifact_inputs.source_size IS '执行时已核验的父文件字节数';
COMMENT ON COLUMN nw_execution_artifact_inputs.source_sha256 IS '执行时已核验的父文件SHA-256';
CREATE INDEX skillforge_execution_artifact_inputs_source ON nw_execution_artifact_inputs(tenant_id,source_artifact_id);
ALTER TABLE nw_execution_artifact_inputs ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_execution_artifact_inputs FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_execution_artifact_inputs_tenant ON nw_execution_artifact_inputs
    USING (tenant_id = nullif(current_setting('app.tenant_id',true),'')::bigint)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id',true),'')::bigint);
