CREATE TABLE nw_execution_artifacts (
    id varchar(36) PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES nw_tenants(id),
    session_id varchar(128) NOT NULL,
    run_id varchar(256) NOT NULL,
    invocation_id varchar(256) NOT NULL,
    type varchar(16) NOT NULL CHECK (type IN ('file','website')),
    name text NOT NULL,
    size bigint NOT NULL CHECK (size >= 0),
    sha256 char(64) NOT NULL,
    manifest jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ready','failed','expired')),
    UNIQUE(tenant_id, invocation_id)
);
COMMENT ON TABLE nw_execution_artifacts IS '代码执行产物完整集合；文件落盘后原子发布，Run与Session来源由可信调用校验，不依赖跨事务Runtime外键';
COMMENT ON COLUMN nw_execution_artifacts.id IS '不透明产物标识，不公开实际路径';
COMMENT ON COLUMN nw_execution_artifacts.tenant_id IS '产物所属租户';
COMMENT ON COLUMN nw_execution_artifacts.session_id IS '可信调用所属Session';
COMMENT ON COLUMN nw_execution_artifacts.run_id IS '可信调用所属Run';
COMMENT ON COLUMN nw_execution_artifacts.invocation_id IS '执行调用身份，同一调用只发布一次';
COMMENT ON COLUMN nw_execution_artifacts.type IS 'file文件集合或website静态网站';
COMMENT ON COLUMN nw_execution_artifacts.name IS '安全展示名称';
COMMENT ON COLUMN nw_execution_artifacts.size IS '文件总字节数，不设字节配额';
COMMENT ON COLUMN nw_execution_artifacts.sha256 IS '有序文件清单SHA-256';
COMMENT ON COLUMN nw_execution_artifacts.manifest IS '不可变文件清单快照：相对路径、字节数、SHA-256';
COMMENT ON COLUMN nw_execution_artifacts.created_at IS 'ready发布时间';
COMMENT ON COLUMN nw_execution_artifacts.expires_at IS '先禁读后异步清理的到期时间';
COMMENT ON COLUMN nw_execution_artifacts.status IS 'ready可用、failed失败或expired到期';
CREATE INDEX skillforge_execution_artifacts_session ON nw_execution_artifacts(tenant_id,session_id,created_at);
CREATE INDEX skillforge_execution_artifacts_expiry ON nw_execution_artifacts(expires_at);
ALTER TABLE nw_execution_artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_execution_artifacts FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_execution_artifacts_tenant ON nw_execution_artifacts USING (tenant_id = nullif(current_setting('app.tenant_id',true),'')::bigint) WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id',true),'')::bigint);
