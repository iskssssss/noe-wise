-- 公共定义授权属于应用治理；实例仍持有独立机构身份，Run复用现有冻结配置。
ALTER TABLE nw_agent_templates ADD COLUMN application_managed boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN nw_agent_templates.application_managed IS '受控应用公共定义标记；首次应用授权设置后禁止普通模板目录和独立复制安装旁路';
CREATE TABLE nw_application_agent_template_grants (
    application_id varchar(64) NOT NULL REFERENCES nw_integration_applications(application_id) ON DELETE CASCADE,
    template_id bigint NOT NULL REFERENCES nw_agent_templates(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(application_id,template_id)
);
COMMENT ON TABLE nw_application_agent_template_grants IS '平台授予指定应用的公共Agent定义授权；撤销立即限制执行';
COMMENT ON COLUMN nw_application_agent_template_grants.application_id IS '被授权的稳定业务应用身份';
COMMENT ON COLUMN nw_application_agent_template_grants.template_id IS '公共Agent稳定模板身份，版本按发布指针解析';
COMMENT ON COLUMN nw_application_agent_template_grants.created_at IS '授权建立时间';

CREATE TABLE nw_application_agent_deployments (
    application_id varchar(64) NOT NULL,
    tenant_id bigint NOT NULL,
    agent_id bigint NOT NULL,
    template_id bigint NOT NULL REFERENCES nw_agent_templates(id) ON DELETE RESTRICT,
    external_agent_ref varchar(128) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    bindings jsonb NOT NULL,
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,agent_id),
    FOREIGN KEY(tenant_id,agent_id) REFERENCES nw_agents(tenant_id,id) ON DELETE RESTRICT,
    UNIQUE(application_id,tenant_id,external_agent_ref),
    FOREIGN KEY(application_id,tenant_id) REFERENCES nw_integration_application_tenants(application_id,tenant_id) ON DELETE RESTRICT,
    CHECK(jsonb_typeof(bindings)='object')
);
COMMENT ON TABLE nw_application_agent_deployments IS '公共Agent定义的机构独立稳定部署；采用FOLLOW_PUBLISHED且Run冻结原定义和本地绑定';
COMMENT ON COLUMN nw_application_agent_deployments.application_id IS '拥有外部实例映射的应用，不授予跨机构执行权限';
COMMENT ON COLUMN nw_application_agent_deployments.tenant_id IS 'Agent执行、资源和用量归属机构';
COMMENT ON COLUMN nw_application_agent_deployments.agent_id IS '机构独立稳定Agent身份，Session始终引用此ID';
COMMENT ON COLUMN nw_application_agent_deployments.template_id IS '当前跟随的公共Agent定义身份';
COMMENT ON COLUMN nw_application_agent_deployments.external_agent_ref IS '应用内机构隔离的外部Agent标识';
COMMENT ON COLUMN nw_application_agent_deployments.fingerprint IS '首次创建请求SHA256，绑定更新不修改创建幂等身份';
COMMENT ON COLUMN nw_application_agent_deployments.bindings IS '动态槽位引用和标量提示词参数快照，不含资源正文或凭据';
COMMENT ON COLUMN nw_application_agent_deployments.revision IS '本地绑定CAS修订，每次成功保存递增';
COMMENT ON COLUMN nw_application_agent_deployments.created_at IS '机构实例首次部署时间';
COMMENT ON COLUMN nw_application_agent_deployments.updated_at IS '最近本地绑定保存时间';
CREATE INDEX idx_skillforge_application_agent_deployments_template ON nw_application_agent_deployments(template_id,application_id);
COMMENT ON INDEX idx_skillforge_application_agent_deployments_template IS '公共定义下应用部署查询';
ALTER TABLE nw_application_agent_deployments ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_application_agent_deployments FORCE ROW LEVEL SECURITY;
CREATE POLICY application_agent_deployment_tenant ON nw_application_agent_deployments
    USING (tenant_id::text=current_setting('app.tenant_id',true))
    WITH CHECK (tenant_id::text=current_setting('app.tenant_id',true));
GRANT SELECT,INSERT,UPDATE ON nw_application_agent_deployments TO skillforge;
GRANT SELECT,INSERT,DELETE ON nw_application_agent_template_grants TO skillforge;
