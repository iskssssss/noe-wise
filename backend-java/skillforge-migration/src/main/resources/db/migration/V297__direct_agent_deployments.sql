-- 平台直授替代应用授权；不迁移旧应用部署，不建立兼容格式。
DROP TABLE nw_application_agent_deployments;
DROP TABLE nw_application_agent_template_grants;

ALTER TABLE nw_agent_templates ADD COLUMN owner_kind varchar(16) NOT NULL DEFAULT 'platform',
    ADD COLUMN owner_tenant_id bigint REFERENCES nw_tenants(id),
    ADD COLUMN draft_revision bigint NOT NULL DEFAULT 1 CHECK(draft_revision>0);
ALTER TABLE nw_agent_templates ADD COLUMN create_actor_ref text,ADD COLUMN create_request_key varchar(256),ADD COLUMN create_fingerprint varchar(64);
CREATE UNIQUE INDEX agent_definition_create_request_idx ON nw_agent_templates(create_actor_ref,create_request_key) WHERE create_request_key IS NOT NULL;
ALTER TABLE nw_agent_templates ADD CONSTRAINT agent_definition_owner_check CHECK
    ((owner_kind='platform' AND owner_tenant_id IS NULL) OR (owner_kind='tenant' AND owner_tenant_id IS NOT NULL AND distribution_policy='AUTHORIZED_DEPLOY'));
COMMENT ON COLUMN nw_agent_templates.owner_kind IS '可信定义所有者域；GI-14仅平台创建，作者展示字段不授予所有权';
COMMENT ON COLUMN nw_agent_templates.owner_tenant_id IS '机构源所有者；平台来源为空';
COMMENT ON COLUMN nw_agent_templates.draft_revision IS '工作草稿CAS修订，修改后递增';

CREATE TABLE nw_agent_definition_grants (
    grant_id bigint PRIMARY KEY,
    definition_id bigint NOT NULL REFERENCES nw_agent_templates(id) ON DELETE RESTRICT,
    target_tenant_id bigint NOT NULL REFERENCES nw_tenants(id) ON DELETE RESTRICT,
    actions text NOT NULL CHECK(actions IN ('deploy,execute','execute,deploy')),
    expires_at timestamptz,
    status varchar(16) NOT NULL CHECK(status IN ('active','revoked')),
    revision bigint NOT NULL CHECK(revision>0),
    actor_ref text NOT NULL CHECK(length(btrim(actor_ref))>0),
    request_key varchar(256) NOT NULL CHECK(length(request_key)>0),
    fingerprint varchar(64) NOT NULL CHECK(length(fingerprint)=64),
    revoke_key varchar(256),
    revoke_fingerprint varchar(64),
    revoked_by text,
    created_at timestamptz NOT NULL,
    revoked_at timestamptz,
    UNIQUE(actor_ref,request_key),
    UNIQUE(grant_id,target_tenant_id,definition_id),
    CHECK ((status='active' AND revoked_at IS NULL AND revoke_key IS NULL AND revoke_fingerprint IS NULL AND revoked_by IS NULL)
        OR (status='revoked' AND revoked_at IS NOT NULL AND revoke_key IS NOT NULL AND revoke_fingerprint IS NOT NULL AND revoked_by IS NOT NULL))
);
COMMENT ON TABLE nw_agent_definition_grants IS '平台直授稳定事实；撤销保留创建幂等身份和终结回执，不复活原授权';
CREATE INDEX skillforge_agent_definition_grants_target_idx ON nw_agent_definition_grants(target_tenant_id,definition_id);

CREATE TABLE nw_agent_deployments (
    deployment_id bigint PRIMARY KEY,
    tenant_id bigint NOT NULL,
    integration_id text,
    agent_id bigint NOT NULL,
    definition_id bigint NOT NULL REFERENCES nw_agent_templates(id) ON DELETE RESTRICT,
    grant_id bigint NOT NULL,
    grant_revision bigint NOT NULL CHECK(grant_revision>0),
    external_agent_ref varchar(128) NOT NULL CHECK(length(btrim(external_agent_ref))>0),
    fingerprint varchar(64) NOT NULL CHECK(length(fingerprint)=64),
    bindings jsonb NOT NULL CHECK(jsonb_typeof(bindings)='object'),
    revision bigint NOT NULL CHECK(revision>0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,agent_id),
    UNIQUE(tenant_id,deployment_id),
    UNIQUE NULLS NOT DISTINCT(tenant_id,integration_id,external_agent_ref),
    FOREIGN KEY(tenant_id,agent_id) REFERENCES nw_agents(tenant_id,id) ON DELETE RESTRICT,
    FOREIGN KEY(grant_id,tenant_id,definition_id) REFERENCES nw_agent_definition_grants(grant_id,target_tenant_id,definition_id) ON DELETE RESTRICT
);
COMMENT ON TABLE nw_agent_deployments IS '目标机构独立稳定实例及固定平台授权依据；本地绑定沿FOLLOW_PUBLISHED解析';
COMMENT ON COLUMN nw_agent_deployments.integration_id IS '外部实例所属固定执行域；原生机构管理部署为空';
COMMENT ON COLUMN nw_agent_deployments.bindings IS '已声明槽位的类型化资源引用及开放提示词标量；身份与授权字段不放入JSON';
CREATE INDEX skillforge_agent_deployments_definition_idx ON nw_agent_deployments(definition_id,tenant_id);

CREATE TABLE nw_agent_deployment_revisions (
    deployment_id bigint NOT NULL,
    revision bigint NOT NULL CHECK(revision>0),
    tenant_id bigint NOT NULL,
    grant_id bigint NOT NULL REFERENCES nw_agent_definition_grants(grant_id) ON DELETE RESTRICT,
    grant_revision bigint NOT NULL CHECK(grant_revision>0),
    bindings jsonb NOT NULL CHECK(jsonb_typeof(bindings)='object'),
    actor_ref text NOT NULL,
    command_ref text NOT NULL,
    fingerprint varchar(64),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(deployment_id,revision),
    UNIQUE(deployment_id,actor_ref,command_ref),
    FOREIGN KEY(tenant_id,deployment_id) REFERENCES nw_agent_deployments(tenant_id,deployment_id) ON DELETE RESTRICT
);
COMMENT ON TABLE nw_agent_deployment_revisions IS '受控实例的不可变本地绑定和授权修订；Run冻结精确引用而非重读当前绑定';
CREATE FUNCTION prevent_direct_deployment_revision_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'agent deployment revision is immutable'; END $$;
CREATE TRIGGER skillforge_agent_deployment_revisions_immutable BEFORE UPDATE OR DELETE ON nw_agent_deployment_revisions
FOR EACH ROW EXECUTE FUNCTION prevent_direct_deployment_revision_mutation();

ALTER TABLE nw_agent_deployments ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_agent_deployments FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_agent_deployments_tenant ON nw_agent_deployments USING(tenant_id::text=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
ALTER TABLE nw_agent_deployment_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_agent_deployment_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_agent_deployment_revisions_tenant ON nw_agent_deployment_revisions USING(tenant_id::text=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
GRANT SELECT,INSERT,UPDATE ON nw_agent_definition_grants,nw_agent_deployments TO skillforge;
GRANT SELECT,INSERT ON nw_agent_deployment_revisions TO skillforge;

COMMENT ON COLUMN nw_agent_definition_grants.grant_id IS '稳定平台原授身份';
COMMENT ON COLUMN nw_agent_definition_grants.definition_id IS '平台源稳定定义引用';
COMMENT ON COLUMN nw_agent_definition_grants.target_tenant_id IS '获准执行的目标机构';
COMMENT ON COLUMN nw_agent_definition_grants.actions IS '明确部署和执行动作集合；不包含转授';
COMMENT ON COLUMN nw_agent_definition_grants.expires_at IS '授权到期时刻，NULL表示无到期';
COMMENT ON COLUMN nw_agent_definition_grants.status IS 'active可用或revoked已终结撤销';
COMMENT ON COLUMN nw_agent_definition_grants.revision IS '授权权威修订，与受理冻结引用一致';
COMMENT ON COLUMN nw_agent_definition_grants.actor_ref IS '原授的可信平台操作者';
COMMENT ON COLUMN nw_agent_definition_grants.request_key IS '原创建的稳定幂等键';
COMMENT ON COLUMN nw_agent_definition_grants.fingerprint IS '原创建完整内容SHA-256摘要';
COMMENT ON COLUMN nw_agent_definition_grants.revoke_key IS '撤销命令幂等键，未撤销为空';
COMMENT ON COLUMN nw_agent_definition_grants.revoke_fingerprint IS '撤销命令内容摘要，未撤销为空';
COMMENT ON COLUMN nw_agent_definition_grants.revoked_by IS '撤销的可信操作者，未撤销为空';
COMMENT ON COLUMN nw_agent_definition_grants.created_at IS '原授实际创建时间';
COMMENT ON COLUMN nw_agent_definition_grants.revoked_at IS '持久撤销时间，未撤销为空';
COMMENT ON COLUMN nw_agent_deployments.deployment_id IS '独立稳定部署身份';
COMMENT ON COLUMN nw_agent_deployments.tenant_id IS '实际执行和资源所属机构';
COMMENT ON COLUMN nw_agent_deployments.agent_id IS '目标机构稳定Agent实例';
COMMENT ON COLUMN nw_agent_deployments.definition_id IS '唯一受控源定义';
COMMENT ON COLUMN nw_agent_deployments.grant_id IS '明确选定的平台授权；不自动换链';
COMMENT ON COLUMN nw_agent_deployments.grant_revision IS '该部署修订采用的精确原授修订';
COMMENT ON COLUMN nw_agent_deployments.external_agent_ref IS '固定Tenant与integration中的外部实例键';
COMMENT ON COLUMN nw_agent_deployments.fingerprint IS '首次创建完整请求摘要；绑定更新不修改';
COMMENT ON COLUMN nw_agent_deployments.revision IS '本地绑定和授权依据的当前部署修订';
COMMENT ON COLUMN nw_agent_deployments.created_at IS '首次创建时间';
COMMENT ON COLUMN nw_agent_deployments.updated_at IS '最后成功保存时间';
COMMENT ON COLUMN nw_agent_deployment_revisions.deployment_id IS '所属稳定部署';
COMMENT ON COLUMN nw_agent_deployment_revisions.revision IS '不可变部署修订号';
COMMENT ON COLUMN nw_agent_deployment_revisions.tenant_id IS '所属执行机构，强制RLS';
COMMENT ON COLUMN nw_agent_deployment_revisions.grant_id IS '该修订明确选择的平台授权';
COMMENT ON COLUMN nw_agent_deployment_revisions.grant_revision IS '该修订采用的精确授权修订';
COMMENT ON COLUMN nw_agent_deployment_revisions.bindings IS '声明槽位资源引用与提示词标量的冻结内容';
COMMENT ON COLUMN nw_agent_deployment_revisions.actor_ref IS '该修订可信操作者';
COMMENT ON COLUMN nw_agent_deployment_revisions.command_ref IS '该修订的稳定创建或绑定命令';
COMMENT ON COLUMN nw_agent_deployment_revisions.fingerprint IS '绑定命令完整摘要；初始修订沿部署首次指纹，故为空';
COMMENT ON COLUMN nw_agent_deployment_revisions.recorded_at IS '修订提交记录时间';
COMMENT ON COLUMN nw_agent_templates.create_actor_ref IS '首次创建的可信平台人员；既有初始化定义为空';
COMMENT ON COLUMN nw_agent_templates.create_request_key IS '首次创建稳定幂等键；既有初始化定义为空';
COMMENT ON COLUMN nw_agent_templates.create_fingerprint IS '首次创建完整请求摘要；既有初始化定义为空';
