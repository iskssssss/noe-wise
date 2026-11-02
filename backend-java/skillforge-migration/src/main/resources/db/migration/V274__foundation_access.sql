-- SaaS 账号体系与组织权限：平台、机构分别拥有角色和组织，所有机构关系强制 RLS。
CREATE TABLE nw_foundation_access_scopes (
    scope_key varchar(128) PRIMARY KEY,
    tenant_id bigint REFERENCES nw_tenants(id) ON DELETE RESTRICT,
    authorization_version bigint NOT NULL DEFAULT 1 CHECK (authorization_version > 0),
    CHECK ((tenant_id IS NULL AND scope_key='platform') OR scope_key=tenant_id::text)
);
CREATE TABLE nw_foundation_departments (
    id bigint PRIMARY KEY, scope_key varchar(128) NOT NULL REFERENCES nw_foundation_access_scopes(scope_key),
    code varchar(64) NOT NULL, name varchar(128) NOT NULL, parent_id bigint,
    enabled boolean NOT NULL DEFAULT true, lock_version bigint NOT NULL DEFAULT 1 CHECK(lock_version>0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    created_by_id bigint NOT NULL, updated_by_id bigint NOT NULL,
    UNIQUE(scope_key,id), UNIQUE(scope_key,code),
    FOREIGN KEY(scope_key,parent_id) REFERENCES nw_foundation_departments(scope_key,id) ON DELETE RESTRICT,
    CHECK (parent_id IS NULL OR parent_id<>id)
);
CREATE TABLE nw_foundation_positions (
    id bigint PRIMARY KEY, scope_key varchar(128) NOT NULL REFERENCES nw_foundation_access_scopes(scope_key),
    code varchar(64) NOT NULL, name varchar(128) NOT NULL, enabled boolean NOT NULL DEFAULT true,
    lock_version bigint NOT NULL DEFAULT 1 CHECK(lock_version>0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    created_by_id bigint NOT NULL, updated_by_id bigint NOT NULL,
    UNIQUE(scope_key,id), UNIQUE(scope_key,code)
);
CREATE TABLE nw_foundation_member_profiles (
    scope_key varchar(128) NOT NULL REFERENCES nw_foundation_access_scopes(scope_key), member_id bigint NOT NULL,
    display_name varchar(128) NOT NULL, department_id bigint, position_id bigint,
    authorization_version bigint NOT NULL DEFAULT 1 CHECK(authorization_version>0),
    lock_version bigint NOT NULL DEFAULT 1 CHECK(lock_version>0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(scope_key,member_id),
    FOREIGN KEY(scope_key,department_id) REFERENCES nw_foundation_departments(scope_key,id) ON DELETE RESTRICT,
    FOREIGN KEY(scope_key,position_id) REFERENCES nw_foundation_positions(scope_key,id) ON DELETE RESTRICT
);
CREATE TABLE nw_foundation_roles (
    id bigint PRIMARY KEY, scope_key varchar(128) NOT NULL REFERENCES nw_foundation_access_scopes(scope_key),
    code varchar(64) NOT NULL, name varchar(128) NOT NULL,
    data_scope varchar(32) NOT NULL CHECK(data_scope IN('self','department','descendants','specified','all')),
    built_in boolean NOT NULL DEFAULT false, administrator boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true, lock_version bigint NOT NULL DEFAULT 1 CHECK(lock_version>0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(scope_key,id), UNIQUE(scope_key,code), CHECK(NOT administrator OR (built_in AND enabled AND data_scope='all'))
);
CREATE TABLE nw_foundation_role_permissions (
    scope_key varchar(128) NOT NULL, role_id bigint NOT NULL, permission_code varchar(128) NOT NULL,
    PRIMARY KEY(scope_key,role_id,permission_code),
    FOREIGN KEY(scope_key,role_id) REFERENCES nw_foundation_roles(scope_key,id) ON DELETE CASCADE,
    CHECK(permission_code ~ '^foundation\.[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$')
);
CREATE TABLE nw_foundation_member_roles (
    scope_key varchar(128) NOT NULL, member_id bigint NOT NULL, role_id bigint NOT NULL,
    PRIMARY KEY(scope_key,member_id,role_id),
    FOREIGN KEY(scope_key,member_id) REFERENCES nw_foundation_member_profiles(scope_key,member_id) ON DELETE CASCADE,
    FOREIGN KEY(scope_key,role_id) REFERENCES nw_foundation_roles(scope_key,id) ON DELETE RESTRICT
);
CREATE TABLE nw_foundation_role_departments (
    scope_key varchar(128) NOT NULL, role_id bigint NOT NULL, department_id bigint NOT NULL,
    PRIMARY KEY(scope_key,role_id,department_id),
    FOREIGN KEY(scope_key,role_id) REFERENCES nw_foundation_roles(scope_key,id) ON DELETE CASCADE,
    FOREIGN KEY(scope_key,department_id) REFERENCES nw_foundation_departments(scope_key,id) ON DELETE RESTRICT
);
CREATE TABLE nw_foundation_resource_ownership (
    scope_key varchar(128) NOT NULL CHECK(scope_key<>'platform'), kind varchar(32) NOT NULL CHECK(kind IN('agent','workflow','knowledge','session')),
    resource_id bigint NOT NULL, owner_member_id bigint NOT NULL, department_id bigint,
    lock_version bigint NOT NULL DEFAULT 1 CHECK(lock_version>0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(scope_key,kind,resource_id),
    FOREIGN KEY(scope_key,owner_member_id) REFERENCES nw_foundation_member_profiles(scope_key,member_id) ON DELETE RESTRICT,
    FOREIGN KEY(scope_key,department_id) REFERENCES nw_foundation_departments(scope_key,id) ON DELETE RESTRICT
);
CREATE TABLE nw_foundation_resource_grants (
    id bigint PRIMARY KEY, scope_key varchar(128) NOT NULL,
    kind varchar(32) NOT NULL, resource_id bigint NOT NULL,
    grantee_type varchar(32) NOT NULL CHECK(grantee_type IN('member','role','department')),
    grantee_id bigint NOT NULL, action varchar(32) NOT NULL CHECK(action IN('read','execute','manage')),
    created_at timestamptz NOT NULL DEFAULT now(), created_by_id bigint NOT NULL,
    FOREIGN KEY(scope_key,kind,resource_id) REFERENCES nw_foundation_resource_ownership(scope_key,kind,resource_id) ON DELETE CASCADE,
    UNIQUE(scope_key,kind,resource_id,grantee_type,grantee_id,action)
);
CREATE INDEX skillforge_foundation_member_roles_role_idx ON nw_foundation_member_roles(scope_key,role_id,member_id);
CREATE INDEX skillforge_foundation_member_profiles_department_idx ON nw_foundation_member_profiles(scope_key,department_id,member_id);
CREATE INDEX skillforge_foundation_departments_parent_idx ON nw_foundation_departments(scope_key,parent_id);
CREATE INDEX skillforge_foundation_resource_grants_target_idx ON nw_foundation_resource_grants(scope_key,grantee_type,grantee_id);

-- 迁移存量身份一次归属至正式多角色模型；后续授权只读取新关系，旧 role 列不再作为判权依据。
INSERT INTO nw_foundation_access_scopes(scope_key) VALUES('platform');
INSERT INTO nw_foundation_access_scopes(scope_key,tenant_id) SELECT id::text,id FROM nw_tenants;
INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name)
SELECT 'platform',id,coalesce(display_name,username) FROM nw_platform_members;
INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name)
SELECT tenant_id::text,id,username FROM nw_tenant_members;
INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope,built_in,administrator)
VALUES(274000000000000001,'platform','platform_admin','平台管理员','all',true,true);
INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope,built_in,administrator)
SELECT 274000000000000010+row_number() OVER(ORDER BY s.scope_key,r.code),s.scope_key,r.code,r.name,r.data_scope,true,r.administrator
FROM nw_foundation_access_scopes s CROSS JOIN (VALUES
('tenant_admin','机构管理员','all',true),('developer','开发成员','self',false),('auditor','审计成员','self',false)) r(code,name,data_scope,administrator)
WHERE s.tenant_id IS NOT NULL;
INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id)
SELECT 'platform',m.id,r.id FROM nw_platform_members m JOIN nw_foundation_roles r ON r.scope_key='platform' AND r.code='platform_admin';
INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id)
SELECT m.tenant_id::text,m.id,r.id FROM nw_tenant_members m JOIN nw_foundation_roles r ON r.scope_key=m.tenant_id::text
AND r.code=CASE WHEN m.role='admin' THEN 'tenant_admin' WHEN m.role='auditor' THEN 'auditor' ELSE 'developer' END;

-- 普通内置角色首次授权明细；请求时只读取关系表，不按旧角色或名称兜底。
INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code)
SELECT r.scope_key,r.id,p.permission FROM nw_foundation_roles r CROSS JOIN (VALUES
('foundation.access.read'),('foundation.messaging.inbox'),('foundation.preferences.manage'),('foundation.security.self')) p(permission)
WHERE r.code IN('developer','auditor');
INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code)
SELECT r.scope_key,r.id,'foundation.resource.'||k.kind||'.'||a.action
FROM nw_foundation_roles r CROSS JOIN (VALUES('agent'),('workflow'),('knowledge')) k(kind)
CROSS JOIN (VALUES('read'),('execute'),('manage')) a(action) WHERE r.code='developer';
INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code)
SELECT r.scope_key,r.id,'foundation.'||d.area||'.'||a.action
FROM nw_foundation_roles r CROSS JOIN (VALUES('agents'),('workflows'),('knowledge'),('capabilities'),('skills'),('channels'),('connectors'),('tool_deployments'),('applications'),('sessions'),('memory'),('handoff'),('teams'),('browser'),('artifacts'),('open_platform'),('webhooks')) d(area)
CROSS JOIN (VALUES('read'),('create'),('update'),('delete'),('publish'),('run'),('cancel'),('export')) a(action) WHERE r.code='developer';
INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code)
SELECT r.scope_key,r.id,p.permission FROM nw_foundation_roles r CROSS JOIN (VALUES
('foundation.audit.read'),('foundation.usage.read'),('foundation.billing.read'),('foundation.operations.audit_read'),('foundation.operations.audit_export')) p(permission)
WHERE r.code='auditor';
INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) SELECT scope_key,id,'foundation.sessions.write' FROM nw_foundation_roles WHERE code='developer';
DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['nw_foundation_access_scopes','nw_foundation_departments','nw_foundation_positions',
        'nw_foundation_member_profiles','nw_foundation_roles','nw_foundation_role_permissions','nw_foundation_member_roles',
        'nw_foundation_role_departments','nw_foundation_resource_ownership','nw_foundation_resource_grants'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO skillforge',table_name);
        EXECUTE format('CREATE POLICY foundation_scope_isolation ON %I USING (scope_key=coalesce(nullif(current_setting(''app.tenant_id'',true),''''),''platform'')) WITH CHECK (scope_key=coalesce(nullif(current_setting(''app.tenant_id'',true),''''),''platform''))',table_name);
    END LOOP;
END $$;
COMMENT ON TABLE nw_foundation_roles IS '平台与机构隔离的正式角色；内置管理员不可删除、停用或降级';
COMMENT ON TABLE nw_foundation_member_profiles IS '正式组织归属及授权版本，账号密码和状态仍由对应成员表拥有';
COMMENT ON TABLE nw_foundation_resource_ownership IS '私有资源授权归属，真实对象存在性在所属领域表校验，未登记的资源只允许全租户范围';
COMMENT ON TABLE nw_foundation_resource_grants IS '同机构成员、角色与部门的明确资源共享动作；不扩张主体动作权限';
