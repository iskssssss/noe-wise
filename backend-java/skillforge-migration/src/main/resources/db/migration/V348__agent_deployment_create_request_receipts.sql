-- 同一外部实例可由后续同参命令命中；每次已受理命令保留原操作者和命令身份。
CREATE TABLE nw_agent_deployment_create_requests (
    deployment_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    actor_ref text NOT NULL,
    command_ref text NOT NULL,
    PRIMARY KEY (deployment_id, actor_ref, command_ref),
    FOREIGN KEY (tenant_id, deployment_id) REFERENCES nw_agent_deployments(tenant_id, deployment_id) ON DELETE RESTRICT
);
COMMENT ON TABLE nw_agent_deployment_create_requests IS '成功的部署创建命令身份；回执复用该部署的首次响应';
ALTER TABLE nw_agent_deployment_create_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_agent_deployment_create_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_agent_deployment_create_requests_tenant ON nw_agent_deployment_create_requests
    USING (tenant_id::text=current_setting('app.tenant_id',true))
    WITH CHECK (tenant_id::text=current_setting('app.tenant_id',true));
CREATE TRIGGER agent_deployment_create_request_immutable BEFORE UPDATE OR DELETE ON nw_agent_deployment_create_requests
    FOR EACH ROW EXECUTE FUNCTION prevent_direct_deployment_revision_mutation();
GRANT SELECT, INSERT ON nw_agent_deployment_create_requests TO skillforge;
