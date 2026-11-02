-- SaaS 基础 catalog：菜单、字典、展示配置和关系化功能授权；不写执行冻结配置。

ALTER TABLE public.nw_billing_plans DROP COLUMN features;

-- 现有机构资料入口仍拥有套餐切换；有效套餐变化必须推进catalog CAS，避免旧视图覆盖新套餐限额。
CREATE FUNCTION public.foundation_advance_tenant_plan_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.plan_id IS DISTINCT FROM OLD.plan_id THEN
        NEW.foundation_quota_revision := OLD.foundation_quota_revision + 1;
    END IF;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION public.foundation_advance_tenant_plan_revision() IS '套餐变更在同一租户行锁下推进资源配额修订';

ALTER TABLE public.nw_billing_plans ADD COLUMN quota_max_agents integer;

ALTER TABLE public.nw_billing_plans ADD CONSTRAINT skillforge_billing_plans_quota_max_agents_check CHECK (quota_max_agents >= 0);

COMMENT ON COLUMN public.nw_billing_plans.quota_max_agents IS '最大智能体数，NULL不限';

ALTER TABLE public.nw_billing_plans ADD COLUMN quota_max_workflows integer;

ALTER TABLE public.nw_billing_plans ADD CONSTRAINT skillforge_billing_plans_quota_max_workflows_check CHECK (quota_max_workflows >= 0);

COMMENT ON COLUMN public.nw_billing_plans.quota_max_workflows IS '最大Builder工作流容器数，NULL不限';

ALTER TABLE public.nw_billing_plans ADD COLUMN quota_file_bytes bigint;

ALTER TABLE public.nw_billing_plans ADD CONSTRAINT skillforge_billing_plans_quota_file_bytes_check CHECK (quota_file_bytes >= 0);

COMMENT ON COLUMN public.nw_billing_plans.quota_file_bytes IS '公共文件字节上限，NULL不限';

ALTER TABLE public.nw_billing_plans ADD COLUMN quota_max_admin_tasks integer;

ALTER TABLE public.nw_billing_plans ADD CONSTRAINT skillforge_billing_plans_quota_max_admin_tasks_check CHECK (quota_max_admin_tasks >= 0);

COMMENT ON COLUMN public.nw_billing_plans.quota_max_admin_tasks IS '并行管理任务数，NULL不限';

ALTER TABLE public.nw_billing_plans ADD COLUMN foundation_quota_revision bigint NOT NULL DEFAULT 1;

ALTER TABLE public.nw_billing_plans ADD CONSTRAINT skillforge_billing_plans_foundation_quota_revision_check CHECK (foundation_quota_revision > 0);

COMMENT ON COLUMN public.nw_billing_plans.foundation_quota_revision IS '配额乐观修订，正整数';

ALTER TABLE public.nw_tenants ADD COLUMN override_quota_max_agents integer;

ALTER TABLE public.nw_tenants ADD CONSTRAINT skillforge_tenants_override_quota_max_agents_check CHECK (override_quota_max_agents >= 0);

COMMENT ON COLUMN public.nw_tenants.override_quota_max_agents IS '智能体数量覆盖，NULL继承套餐';

ALTER TABLE public.nw_tenants ADD COLUMN override_quota_file_bytes bigint;

ALTER TABLE public.nw_tenants ADD CONSTRAINT skillforge_tenants_override_quota_file_bytes_check CHECK (override_quota_file_bytes >= 0);

COMMENT ON COLUMN public.nw_tenants.override_quota_file_bytes IS '文件字节覆盖，NULL继承套餐';

ALTER TABLE public.nw_tenants ADD COLUMN override_quota_max_admin_tasks integer;

ALTER TABLE public.nw_tenants ADD CONSTRAINT skillforge_tenants_override_quota_max_admin_tasks_check CHECK (override_quota_max_admin_tasks >= 0);

COMMENT ON COLUMN public.nw_tenants.override_quota_max_admin_tasks IS '并行管理任务覆盖，NULL继承套餐';

ALTER TABLE public.nw_tenants ADD COLUMN foundation_quota_revision bigint NOT NULL DEFAULT 1;

ALTER TABLE public.nw_tenants ADD CONSTRAINT skillforge_tenants_foundation_quota_revision_check CHECK (foundation_quota_revision > 0);

COMMENT ON COLUMN public.nw_tenants.foundation_quota_revision IS '租户配额覆盖修订，正整数';

CREATE TRIGGER foundation_tenant_plan_revision BEFORE UPDATE OF plan_id ON public.nw_tenants
    FOR EACH ROW EXECUTE FUNCTION public.foundation_advance_tenant_plan_revision();
COMMENT ON TRIGGER foundation_tenant_plan_revision ON public.nw_tenants IS '真实套餐切换使旧资源配额CAS视图失效；同值更新不推进';

CREATE TABLE public.nw_foundation_dictionaries (
    owner_key varchar(64) NOT NULL,
    tenant_id bigint REFERENCES public.nw_tenants(id),
    code varchar(64) NOT NULL,
    name varchar(128) NOT NULL,
    enabled boolean NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by varchar(128) NOT NULL,
    updated_by varchar(128) NOT NULL,
    CONSTRAINT skillforge_foundation_dictionaries_pk PRIMARY KEY(owner_key,code),
    CONSTRAINT skillforge_foundation_dictionaries_revision_ck CHECK(revision>0)
);

COMMENT ON TABLE public.nw_foundation_dictionaries IS '平台和机构稳定字典；停用保留历史引用';

COMMENT ON COLUMN public.nw_foundation_dictionaries.owner_key IS 'platform或tenant:ID的可信范围';

COMMENT ON COLUMN public.nw_foundation_dictionaries.tenant_id IS '机构归属，平台为空';

COMMENT ON COLUMN public.nw_foundation_dictionaries.code IS '稳定字典编码，创建后不可改名';

COMMENT ON COLUMN public.nw_foundation_dictionaries.name IS '显示名称';

COMMENT ON COLUMN public.nw_foundation_dictionaries.enabled IS '是否允许新选择';

COMMENT ON COLUMN public.nw_foundation_dictionaries.revision IS '正整数CAS修订';

COMMENT ON COLUMN public.nw_foundation_dictionaries.created_at IS '数据库创建时刻';

COMMENT ON COLUMN public.nw_foundation_dictionaries.updated_at IS '数据库最后修改时刻';

COMMENT ON COLUMN public.nw_foundation_dictionaries.created_by IS '范围内可信创建账号，seed表示迁移系统';

COMMENT ON COLUMN public.nw_foundation_dictionaries.updated_by IS '范围内可信最后修改账号';

COMMENT ON CONSTRAINT skillforge_foundation_dictionaries_pk ON public.nw_foundation_dictionaries IS '平台和机构稳定字典；停用保留历史引用的完整性保护';

COMMENT ON CONSTRAINT skillforge_foundation_dictionaries_revision_ck ON public.nw_foundation_dictionaries IS '平台和机构稳定字典；停用保留历史引用的完整性保护';

ALTER TABLE public.nw_foundation_dictionaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_dictionaries FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_foundation_dictionaries_scope ON public.nw_foundation_dictionaries USING((tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL))) WITH CHECK((tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL)));

GRANT SELECT,INSERT,UPDATE,DELETE ON public.nw_foundation_dictionaries TO skillforge;

CREATE TABLE public.nw_foundation_dictionary_values (
    owner_key varchar(64) NOT NULL,
    tenant_id bigint REFERENCES public.nw_tenants(id),
    dictionary_code varchar(64) NOT NULL,
    code varchar(64) NOT NULL,
    label varchar(128) NOT NULL,
    sort_order integer NOT NULL,
    enabled boolean NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by varchar(128) NOT NULL,
    updated_by varchar(128) NOT NULL,
    CONSTRAINT skillforge_foundation_dictionary_values_pk PRIMARY KEY(owner_key,dictionary_code,code),
    CONSTRAINT skillforge_foundation_dictionary_values_parent_fk FOREIGN KEY(owner_key,dictionary_code) REFERENCES public.nw_foundation_dictionaries(owner_key,code) ON DELETE CASCADE,
    CONSTRAINT skillforge_foundation_dictionary_values_order_ck CHECK(sort_order>=0),
    CONSTRAINT skillforge_foundation_dictionary_values_revision_ck CHECK(revision>0)
);

COMMENT ON TABLE public.nw_foundation_dictionary_values IS '字典稳定值；显示和排序可变，编码不可改名';

COMMENT ON COLUMN public.nw_foundation_dictionary_values.owner_key IS 'platform或tenant:ID的可信范围';

COMMENT ON COLUMN public.nw_foundation_dictionary_values.tenant_id IS '机构归属，平台为空';

COMMENT ON COLUMN public.nw_foundation_dictionary_values.dictionary_code IS '所属稳定字典';

COMMENT ON COLUMN public.nw_foundation_dictionary_values.code IS '稳定选项编码';

COMMENT ON COLUMN public.nw_foundation_dictionary_values.label IS '展示文本';

COMMENT ON COLUMN public.nw_foundation_dictionary_values.sort_order IS '非负排序';

COMMENT ON COLUMN public.nw_foundation_dictionary_values.enabled IS '新选择开关';

COMMENT ON COLUMN public.nw_foundation_dictionary_values.revision IS '正整数CAS修订';

COMMENT ON COLUMN public.nw_foundation_dictionary_values.created_at IS '数据库创建时刻';

COMMENT ON COLUMN public.nw_foundation_dictionary_values.updated_at IS '数据库最后修改时刻';

COMMENT ON COLUMN public.nw_foundation_dictionary_values.created_by IS '范围内可信创建账号，seed表示迁移系统';

COMMENT ON COLUMN public.nw_foundation_dictionary_values.updated_by IS '范围内可信最后修改账号';

COMMENT ON CONSTRAINT skillforge_foundation_dictionary_values_pk ON public.nw_foundation_dictionary_values IS '字典稳定值；显示和排序可变，编码不可改名的完整性保护';

COMMENT ON CONSTRAINT skillforge_foundation_dictionary_values_parent_fk ON public.nw_foundation_dictionary_values IS '字典稳定值；显示和排序可变，编码不可改名的完整性保护';

COMMENT ON CONSTRAINT skillforge_foundation_dictionary_values_order_ck ON public.nw_foundation_dictionary_values IS '字典稳定值；显示和排序可变，编码不可改名的完整性保护';

COMMENT ON CONSTRAINT skillforge_foundation_dictionary_values_revision_ck ON public.nw_foundation_dictionary_values IS '字典稳定值；显示和排序可变，编码不可改名的完整性保护';

ALTER TABLE public.nw_foundation_dictionary_values ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_dictionary_values FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_foundation_dictionary_values_scope ON public.nw_foundation_dictionary_values USING((tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL))) WITH CHECK((tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL)));

GRANT SELECT,INSERT,UPDATE,DELETE ON public.nw_foundation_dictionary_values TO skillforge;

CREATE TABLE public.nw_foundation_dictionary_references (
    owner_key varchar(64) NOT NULL,
    tenant_id bigint REFERENCES public.nw_tenants(id),
    dictionary_code varchar(64) NOT NULL,
    value_code varchar(64) NOT NULL,
    resource_type varchar(64) NOT NULL,
    resource_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_foundation_dictionary_references_pk PRIMARY KEY(owner_key,dictionary_code,value_code,resource_type,resource_id),
    CONSTRAINT skillforge_foundation_dictionary_references_value_fk FOREIGN KEY(owner_key,dictionary_code,value_code) REFERENCES public.nw_foundation_dictionary_values(owner_key,dictionary_code,code) ON DELETE RESTRICT
);

COMMENT ON TABLE public.nw_foundation_dictionary_references IS '可信消费者同事务维护引用；FK禁止删除仍被引用的值';

COMMENT ON COLUMN public.nw_foundation_dictionary_references.owner_key IS 'platform或tenant:ID的可信范围';

COMMENT ON COLUMN public.nw_foundation_dictionary_references.tenant_id IS '机构归属，平台为空';

COMMENT ON COLUMN public.nw_foundation_dictionary_references.dictionary_code IS '字典编码';

COMMENT ON COLUMN public.nw_foundation_dictionary_references.value_code IS '稳定值编码';

COMMENT ON COLUMN public.nw_foundation_dictionary_references.resource_type IS '受控资源种类';

COMMENT ON COLUMN public.nw_foundation_dictionary_references.resource_id IS '可信内部资源身份';

COMMENT ON COLUMN public.nw_foundation_dictionary_references.created_at IS '首次引用时刻';

COMMENT ON CONSTRAINT skillforge_foundation_dictionary_references_pk ON public.nw_foundation_dictionary_references IS '可信消费者同事务维护引用；FK禁止删除仍被引用的值的完整性保护';

COMMENT ON CONSTRAINT skillforge_foundation_dictionary_references_value_fk ON public.nw_foundation_dictionary_references IS '可信消费者同事务维护引用；FK禁止删除仍被引用的值的完整性保护';

ALTER TABLE public.nw_foundation_dictionary_references ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_dictionary_references FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_foundation_dictionary_references_scope ON public.nw_foundation_dictionary_references USING((tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL))) WITH CHECK((tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL)));

GRANT SELECT,INSERT,UPDATE,DELETE ON public.nw_foundation_dictionary_references TO skillforge;

CREATE TABLE public.nw_foundation_navigation_nodes (
    owner_key varchar(64) NOT NULL,
    tenant_id bigint REFERENCES public.nw_tenants(id),
    code varchar(64) NOT NULL,
    parent_code varchar(64),
    title varchar(128) NOT NULL,
    route_key varchar(64),
    sort_order integer NOT NULL,
    enabled boolean NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by varchar(128) NOT NULL,
    updated_by varchar(128) NOT NULL,
    CONSTRAINT skillforge_foundation_navigation_nodes_pk PRIMARY KEY(owner_key,code),
    CONSTRAINT foundation_navigation_parent_fk FOREIGN KEY(owner_key,parent_code) REFERENCES public.nw_foundation_navigation_nodes(owner_key,code) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT foundation_navigation_order_ck CHECK(sort_order>=0),
    CONSTRAINT foundation_navigation_revision_ck CHECK(revision>0),
    CONSTRAINT foundation_navigation_self_ck CHECK(parent_code IS DISTINCT FROM code)
);

COMMENT ON TABLE public.nw_foundation_navigation_nodes IS '受控路由键的可编辑菜单树；tenant_template为只读机构模板';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.owner_key IS 'platform或tenant:ID的可信范围';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.tenant_id IS '机构归属，平台为空';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.code IS '稳定菜单节点编码';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.parent_code IS 'NULL表示根节点';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.title IS '显示标题';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.route_key IS 'NULL表示菜单组，非空须匹配代码目录';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.sort_order IS '非负排序';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.enabled IS '节点及后代可见开关';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.revision IS '正整数CAS修订';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.created_at IS '数据库创建时刻';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.updated_at IS '数据库最后修改时刻';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.created_by IS '范围内可信创建账号，seed表示迁移系统';

COMMENT ON COLUMN public.nw_foundation_navigation_nodes.updated_by IS '范围内可信最后修改账号';

COMMENT ON CONSTRAINT skillforge_foundation_navigation_nodes_pk ON public.nw_foundation_navigation_nodes IS '受控路由键的可编辑菜单树；tenant_template为只读机构模板的完整性保护';

COMMENT ON CONSTRAINT foundation_navigation_parent_fk ON public.nw_foundation_navigation_nodes IS '受控路由键的可编辑菜单树；tenant_template为只读机构模板的完整性保护';

COMMENT ON CONSTRAINT foundation_navigation_order_ck ON public.nw_foundation_navigation_nodes IS '受控路由键的可编辑菜单树；tenant_template为只读机构模板的完整性保护';

COMMENT ON CONSTRAINT foundation_navigation_revision_ck ON public.nw_foundation_navigation_nodes IS '受控路由键的可编辑菜单树；tenant_template为只读机构模板的完整性保护';

COMMENT ON CONSTRAINT foundation_navigation_self_ck ON public.nw_foundation_navigation_nodes IS '受控路由键的可编辑菜单树；tenant_template为只读机构模板的完整性保护';

ALTER TABLE public.nw_foundation_navigation_nodes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_navigation_nodes FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_foundation_navigation_nodes_read ON public.nw_foundation_navigation_nodes FOR SELECT USING(owner_key='tenant_template' OR (tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL)));

CREATE POLICY skillforge_foundation_navigation_nodes_write ON public.nw_foundation_navigation_nodes FOR ALL USING((tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL))) WITH CHECK((tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL)));

GRANT SELECT,INSERT,UPDATE,DELETE ON public.nw_foundation_navigation_nodes TO skillforge;

CREATE TABLE public.nw_foundation_tenant_branding (
    tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
    display_name varchar(128) NOT NULL,
    logo_url varchar(2048),
    primary_color varchar(7) NOT NULL,
    contact_email varchar(254),
    support_url varchar(2048),
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by varchar(128) NOT NULL,
    updated_by varchar(128) NOT NULL,
    CONSTRAINT skillforge_foundation_tenant_branding_pk PRIMARY KEY(tenant_id),
    CONSTRAINT foundation_branding_color_ck CHECK(primary_color ~ '^#[0-9a-f]{6}$'),
    CONSTRAINT foundation_branding_revision_ck CHECK(revision>0)
);

COMMENT ON TABLE public.nw_foundation_tenant_branding IS '机构实时UI品牌；不修改运营策略、凭据或Run冻结配置';

COMMENT ON COLUMN public.nw_foundation_tenant_branding.tenant_id IS '可信所属机构';

COMMENT ON COLUMN public.nw_foundation_tenant_branding.display_name IS '公开展示品牌名';

COMMENT ON COLUMN public.nw_foundation_tenant_branding.logo_url IS '公开HTTPS链接，NULL不显示logo';

COMMENT ON COLUMN public.nw_foundation_tenant_branding.primary_color IS '六位十六进制CSS色';

COMMENT ON COLUMN public.nw_foundation_tenant_branding.contact_email IS '公开联系邮箱，NULL不显示';

COMMENT ON COLUMN public.nw_foundation_tenant_branding.support_url IS '公开支持HTTPS链接，NULL不显示';

COMMENT ON COLUMN public.nw_foundation_tenant_branding.revision IS '正整数CAS修订';

COMMENT ON COLUMN public.nw_foundation_tenant_branding.created_at IS '数据库创建时刻';

COMMENT ON COLUMN public.nw_foundation_tenant_branding.updated_at IS '数据库最后修改时刻';

COMMENT ON COLUMN public.nw_foundation_tenant_branding.created_by IS '范围内可信创建账号，seed表示迁移系统';

COMMENT ON COLUMN public.nw_foundation_tenant_branding.updated_by IS '范围内可信最后修改账号';

COMMENT ON CONSTRAINT skillforge_foundation_tenant_branding_pk ON public.nw_foundation_tenant_branding IS '机构实时UI品牌；不修改运营策略、凭据或Run冻结配置的完整性保护';

COMMENT ON CONSTRAINT foundation_branding_color_ck ON public.nw_foundation_tenant_branding IS '机构实时UI品牌；不修改运营策略、凭据或Run冻结配置的完整性保护';

COMMENT ON CONSTRAINT foundation_branding_revision_ck ON public.nw_foundation_tenant_branding IS '机构实时UI品牌；不修改运营策略、凭据或Run冻结配置的完整性保护';

ALTER TABLE public.nw_foundation_tenant_branding ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_tenant_branding FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_foundation_tenant_branding_scope ON public.nw_foundation_tenant_branding USING((tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL))) WITH CHECK((tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL)));

GRANT SELECT,INSERT,UPDATE,DELETE ON public.nw_foundation_tenant_branding TO skillforge;

CREATE TABLE public.nw_foundation_personal_preferences (
    owner_key varchar(64) NOT NULL,
    tenant_id bigint REFERENCES public.nw_tenants(id),
    member_id varchar(19) NOT NULL,
    theme varchar(16) NOT NULL,
    locale varchar(16) NOT NULL,
    page_size integer NOT NULL,
    compact_navigation boolean NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_foundation_personal_preferences_pk PRIMARY KEY(owner_key,member_id),
    CONSTRAINT foundation_preferences_theme_ck CHECK(theme IN('light','dark','system')),
    CONSTRAINT foundation_preferences_locale_ck CHECK(locale='zh-CN'),
    CONSTRAINT foundation_preferences_page_ck CHECK(page_size IN(10,20,50,100)),
    CONSTRAINT foundation_preferences_revision_ck CHECK(revision>0)
);

COMMENT ON TABLE public.nw_foundation_personal_preferences IS '平台和机构本人UI偏好；账号身份不由HTTP请求提供';

COMMENT ON COLUMN public.nw_foundation_personal_preferences.owner_key IS 'platform或tenant:ID的可信范围';

COMMENT ON COLUMN public.nw_foundation_personal_preferences.tenant_id IS '机构归属，平台为空';

COMMENT ON COLUMN public.nw_foundation_personal_preferences.member_id IS '范围内本人账号ID';

COMMENT ON COLUMN public.nw_foundation_personal_preferences.theme IS 'light/dark/system';

COMMENT ON COLUMN public.nw_foundation_personal_preferences.locale IS '当前后台仅提供zh-CN中文资源';

COMMENT ON COLUMN public.nw_foundation_personal_preferences.page_size IS '10/20/50/100';

COMMENT ON COLUMN public.nw_foundation_personal_preferences.compact_navigation IS '紧凑导航偏好';

COMMENT ON COLUMN public.nw_foundation_personal_preferences.revision IS '正整数CAS修订';

COMMENT ON COLUMN public.nw_foundation_personal_preferences.created_at IS '首次保存时刻';

COMMENT ON COLUMN public.nw_foundation_personal_preferences.updated_at IS '最后保存时刻';

COMMENT ON CONSTRAINT skillforge_foundation_personal_preferences_pk ON public.nw_foundation_personal_preferences IS '平台和机构本人UI偏好；账号身份不由HTTP请求提供的完整性保护';

COMMENT ON CONSTRAINT foundation_preferences_theme_ck ON public.nw_foundation_personal_preferences IS '平台和机构本人UI偏好；账号身份不由HTTP请求提供的完整性保护';

COMMENT ON CONSTRAINT foundation_preferences_locale_ck ON public.nw_foundation_personal_preferences IS '平台和机构本人UI偏好；账号身份不由HTTP请求提供的完整性保护';

COMMENT ON CONSTRAINT foundation_preferences_page_ck ON public.nw_foundation_personal_preferences IS '平台和机构本人UI偏好；账号身份不由HTTP请求提供的完整性保护';

COMMENT ON CONSTRAINT foundation_preferences_revision_ck ON public.nw_foundation_personal_preferences IS '平台和机构本人UI偏好；账号身份不由HTTP请求提供的完整性保护';

ALTER TABLE public.nw_foundation_personal_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_personal_preferences FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_foundation_personal_preferences_scope ON public.nw_foundation_personal_preferences USING((tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL))) WITH CHECK((tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint) OR (tenant_id IS NULL AND NULLIF(current_setting('app.tenant_id',true),'') IS NULL)));

GRANT SELECT,INSERT,UPDATE,DELETE ON public.nw_foundation_personal_preferences TO skillforge;

CREATE TABLE public.nw_foundation_features (
    code varchar(64) NOT NULL,
    name varchar(128) NOT NULL,
    description varchar(512) NOT NULL,
    enabled boolean NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by varchar(128) NOT NULL,
    updated_by varchar(128) NOT NULL,
    CONSTRAINT skillforge_foundation_features_pk PRIMARY KEY(code),
    CONSTRAINT skillforge_foundation_features_revision_ck CHECK(revision>0)
);

COMMENT ON TABLE public.nw_foundation_features IS '可管理稳定功能目录；功能授权与主体动作权限分离';

COMMENT ON COLUMN public.nw_foundation_features.code IS '稳定功能编码';

COMMENT ON COLUMN public.nw_foundation_features.name IS '显示名称';

COMMENT ON COLUMN public.nw_foundation_features.description IS '功能说明';

COMMENT ON COLUMN public.nw_foundation_features.enabled IS '全局功能可用开关';

COMMENT ON COLUMN public.nw_foundation_features.revision IS '正整数CAS修订';

COMMENT ON COLUMN public.nw_foundation_features.created_at IS '数据库创建时刻';

COMMENT ON COLUMN public.nw_foundation_features.updated_at IS '数据库最后修改时刻';

COMMENT ON COLUMN public.nw_foundation_features.created_by IS '范围内可信创建账号，seed表示迁移系统';

COMMENT ON COLUMN public.nw_foundation_features.updated_by IS '范围内可信最后修改账号';

COMMENT ON CONSTRAINT skillforge_foundation_features_pk ON public.nw_foundation_features IS '可管理稳定功能目录；功能授权与主体动作权限分离的完整性保护';

COMMENT ON CONSTRAINT skillforge_foundation_features_revision_ck ON public.nw_foundation_features IS '可管理稳定功能目录；功能授权与主体动作权限分离的完整性保护';

ALTER TABLE public.nw_foundation_features ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_features FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_foundation_features_read ON public.nw_foundation_features FOR SELECT USING(true);

CREATE POLICY skillforge_foundation_features_write ON public.nw_foundation_features FOR ALL USING(NULLIF(current_setting('app.tenant_id',true),'') IS NULL) WITH CHECK(NULLIF(current_setting('app.tenant_id',true),'') IS NULL);

GRANT SELECT,INSERT,UPDATE,DELETE ON public.nw_foundation_features TO skillforge;

CREATE TABLE public.nw_foundation_plan_features (
    plan_id bigint NOT NULL REFERENCES public.nw_billing_plans(id),
    feature_code varchar(64) NOT NULL REFERENCES public.nw_foundation_features(code),
    enabled boolean NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by varchar(128) NOT NULL,
    CONSTRAINT skillforge_foundation_plan_features_pk PRIMARY KEY(plan_id,feature_code),
    CONSTRAINT skillforge_foundation_plan_features_revision_ck CHECK(revision>0)
);

COMMENT ON TABLE public.nw_foundation_plan_features IS '现有套餐的关系化功能授权；没有关系即不授权';

COMMENT ON COLUMN public.nw_foundation_plan_features.plan_id IS '现有套餐ID';

COMMENT ON COLUMN public.nw_foundation_plan_features.feature_code IS '功能目录编码';

COMMENT ON COLUMN public.nw_foundation_plan_features.enabled IS '明确套餐授权';

COMMENT ON COLUMN public.nw_foundation_plan_features.revision IS '正整数CAS修订';

COMMENT ON COLUMN public.nw_foundation_plan_features.updated_at IS '最后修改时刻';

COMMENT ON COLUMN public.nw_foundation_plan_features.updated_by IS '可信平台账号或seed';

COMMENT ON CONSTRAINT skillforge_foundation_plan_features_pk ON public.nw_foundation_plan_features IS '现有套餐的关系化功能授权；没有关系即不授权的完整性保护';

COMMENT ON CONSTRAINT skillforge_foundation_plan_features_revision_ck ON public.nw_foundation_plan_features IS '现有套餐的关系化功能授权；没有关系即不授权的完整性保护';

ALTER TABLE public.nw_foundation_plan_features ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_plan_features FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_foundation_plan_features_read ON public.nw_foundation_plan_features FOR SELECT USING(true);

CREATE POLICY skillforge_foundation_plan_features_write ON public.nw_foundation_plan_features FOR ALL USING(NULLIF(current_setting('app.tenant_id',true),'') IS NULL) WITH CHECK(NULLIF(current_setting('app.tenant_id',true),'') IS NULL);

GRANT SELECT,INSERT,UPDATE,DELETE ON public.nw_foundation_plan_features TO skillforge;

CREATE TABLE public.nw_foundation_tenant_features (
    tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
    feature_code varchar(64) NOT NULL REFERENCES public.nw_foundation_features(code),
    enabled boolean NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by varchar(128) NOT NULL,
    CONSTRAINT skillforge_foundation_tenant_features_pk PRIMARY KEY(tenant_id,feature_code),
    CONSTRAINT skillforge_foundation_tenant_features_revision_ck CHECK(revision>0)
);

COMMENT ON TABLE public.nw_foundation_tenant_features IS '机构功能显式覆盖；删除关系恢复当前套餐继承';

COMMENT ON COLUMN public.nw_foundation_tenant_features.tenant_id IS '受控目标机构';

COMMENT ON COLUMN public.nw_foundation_tenant_features.feature_code IS '功能目录编码';

COMMENT ON COLUMN public.nw_foundation_tenant_features.enabled IS 'true授权/false拒绝，不使用NULL双义';

COMMENT ON COLUMN public.nw_foundation_tenant_features.revision IS '正整数CAS修订';

COMMENT ON COLUMN public.nw_foundation_tenant_features.updated_at IS '最后修改时刻';

COMMENT ON COLUMN public.nw_foundation_tenant_features.updated_by IS '可信平台账号';

COMMENT ON CONSTRAINT skillforge_foundation_tenant_features_pk ON public.nw_foundation_tenant_features IS '机构功能显式覆盖；删除关系恢复当前套餐继承的完整性保护';

COMMENT ON CONSTRAINT skillforge_foundation_tenant_features_revision_ck ON public.nw_foundation_tenant_features IS '机构功能显式覆盖；删除关系恢复当前套餐继承的完整性保护';

ALTER TABLE public.nw_foundation_tenant_features ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_tenant_features FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_foundation_tenant_features_scope ON public.nw_foundation_tenant_features USING(tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint)) WITH CHECK(tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint));

GRANT SELECT,INSERT,UPDATE,DELETE ON public.nw_foundation_tenant_features TO skillforge;

INSERT INTO public.nw_foundation_features(code,name,description,enabled,created_by,updated_by) VALUES('agents','智能体','智能体受理授权',true,'seed','seed');

INSERT INTO public.nw_foundation_features(code,name,description,enabled,created_by,updated_by) VALUES('workflows','工作流','工作流受理授权',true,'seed','seed');

INSERT INTO public.nw_foundation_features(code,name,description,enabled,created_by,updated_by) VALUES('knowledge','知识库','知识库受理授权',true,'seed','seed');

INSERT INTO public.nw_foundation_features(code,name,description,enabled,created_by,updated_by) VALUES('members','机构成员','机构成员受理授权',true,'seed','seed');

INSERT INTO public.nw_foundation_features(code,name,description,enabled,created_by,updated_by) VALUES('channels','渠道','渠道受理授权',true,'seed','seed');

INSERT INTO public.nw_foundation_features(code,name,description,enabled,created_by,updated_by) VALUES('foundation.dictionary','字典管理','字典管理受理授权',true,'seed','seed');

INSERT INTO public.nw_foundation_features(code,name,description,enabled,created_by,updated_by) VALUES('foundation.files','公共文件','公共文件受理授权',true,'seed','seed');

INSERT INTO public.nw_foundation_features(code,name,description,enabled,created_by,updated_by) VALUES('foundation.jobs','管理任务','管理任务受理授权',true,'seed','seed');

INSERT INTO public.nw_foundation_plan_features(plan_id,feature_code,enabled,updated_by) SELECT p.id,f.code,true,'seed' FROM public.nw_billing_plans p CROSS JOIN public.nw_foundation_features f;












CREATE TABLE public.nw_foundation_navigation_owners (
    owner_key varchar(64) CONSTRAINT skillforge_foundation_navigation_owners_pk PRIMARY KEY,
    tenant_id bigint NOT NULL CONSTRAINT skillforge_foundation_navigation_owners_tenant_fk REFERENCES public.nw_tenants(id),
    initialized_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_foundation_navigation_owners_owner_ck CHECK(owner_key='tenant:' || tenant_id::text)
);
COMMENT ON TABLE public.nw_foundation_navigation_owners IS '机构菜单已初始化标记；空树保持为空，不复活默认模板';
COMMENT ON COLUMN public.nw_foundation_navigation_owners.owner_key IS '可信机构菜单所有者';
COMMENT ON COLUMN public.nw_foundation_navigation_owners.tenant_id IS '所属机构';
COMMENT ON COLUMN public.nw_foundation_navigation_owners.initialized_at IS '首次建立自有菜单的数据库时刻';
COMMENT ON CONSTRAINT skillforge_foundation_navigation_owners_pk ON public.nw_foundation_navigation_owners IS '每个机构菜单仅初始化一次';
COMMENT ON CONSTRAINT skillforge_foundation_navigation_owners_tenant_fk ON public.nw_foundation_navigation_owners IS '初始化标记必须属于真实机构';
COMMENT ON CONSTRAINT skillforge_foundation_navigation_owners_owner_ck ON public.nw_foundation_navigation_owners IS 'owner与租户一致';
ALTER TABLE public.nw_foundation_navigation_owners ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_navigation_owners FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_foundation_navigation_owners_scope ON public.nw_foundation_navigation_owners
    USING(tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint))
    WITH CHECK(tenant_id=CAST(NULLIF(current_setting('app.tenant_id',true),'') AS bigint));
GRANT SELECT,INSERT,UPDATE,DELETE ON public.nw_foundation_navigation_owners TO skillforge;

ALTER TABLE public.nw_foundation_dictionaries ADD CONSTRAINT skillforge_foundation_dictionaries_owner_ck CHECK((tenant_id IS NULL AND owner_key='platform') OR (tenant_id IS NOT NULL AND owner_key='tenant:' || tenant_id::text));
COMMENT ON CONSTRAINT skillforge_foundation_dictionaries_owner_ck ON public.nw_foundation_dictionaries IS 'owner稳定范围与tenant归属一致';

ALTER TABLE public.nw_foundation_dictionary_values ADD CONSTRAINT skillforge_foundation_dictionary_values_owner_ck CHECK((tenant_id IS NULL AND owner_key='platform') OR (tenant_id IS NOT NULL AND owner_key='tenant:' || tenant_id::text));
COMMENT ON CONSTRAINT skillforge_foundation_dictionary_values_owner_ck ON public.nw_foundation_dictionary_values IS 'owner稳定范围与tenant归属一致';

ALTER TABLE public.nw_foundation_dictionary_references ADD CONSTRAINT skillforge_foundation_dictionary_references_owner_ck CHECK((tenant_id IS NULL AND owner_key='platform') OR (tenant_id IS NOT NULL AND owner_key='tenant:' || tenant_id::text));
COMMENT ON CONSTRAINT skillforge_foundation_dictionary_references_owner_ck ON public.nw_foundation_dictionary_references IS 'owner稳定范围与tenant归属一致';

ALTER TABLE public.nw_foundation_personal_preferences ADD CONSTRAINT skillforge_foundation_personal_preferences_owner_ck CHECK((tenant_id IS NULL AND owner_key='platform') OR (tenant_id IS NOT NULL AND owner_key='tenant:' || tenant_id::text));
COMMENT ON CONSTRAINT skillforge_foundation_personal_preferences_owner_ck ON public.nw_foundation_personal_preferences IS 'owner稳定范围与tenant归属一致';

ALTER TABLE public.nw_foundation_navigation_nodes ADD CONSTRAINT skillforge_foundation_navigation_nodes_owner_ck CHECK((tenant_id IS NULL AND owner_key IN('platform','tenant_template')) OR (tenant_id IS NOT NULL AND owner_key='tenant:' || tenant_id::text));
COMMENT ON CONSTRAINT skillforge_foundation_navigation_nodes_owner_ck ON public.nw_foundation_navigation_nodes IS 'owner稳定范围与tenant归属一致';

ALTER TABLE public.nw_foundation_dictionaries ADD CONSTRAINT skillforge_foundation_dictionaries_code_ck CHECK(code ~ '^[a-z][a-z0-9_.-]{0,63}$');
COMMENT ON CONSTRAINT skillforge_foundation_dictionaries_code_ck ON public.nw_foundation_dictionaries IS '稳定编码格式与应用输入一致';

ALTER TABLE public.nw_foundation_dictionary_values ADD CONSTRAINT skillforge_foundation_dictionary_values_code_ck CHECK(code ~ '^[a-z][a-z0-9_.-]{0,63}$');
COMMENT ON CONSTRAINT skillforge_foundation_dictionary_values_code_ck ON public.nw_foundation_dictionary_values IS '稳定编码格式与应用输入一致';

ALTER TABLE public.nw_foundation_dictionary_values ADD CONSTRAINT skillforge_foundation_dictionary_values_dictionary_code_ck CHECK(dictionary_code ~ '^[a-z][a-z0-9_.-]{0,63}$');
COMMENT ON CONSTRAINT skillforge_foundation_dictionary_values_dictionary_code_ck ON public.nw_foundation_dictionary_values IS '稳定编码格式与应用输入一致';

ALTER TABLE public.nw_foundation_features ADD CONSTRAINT skillforge_foundation_features_code_ck CHECK(code ~ '^[a-z][a-z0-9_.-]{0,63}$');
COMMENT ON CONSTRAINT skillforge_foundation_features_code_ck ON public.nw_foundation_features IS '稳定编码格式与应用输入一致';

ALTER TABLE public.nw_foundation_navigation_nodes ADD CONSTRAINT skillforge_foundation_navigation_nodes_code_ck CHECK(code ~ '^[a-z][a-z0-9_.-]{0,63}$');
COMMENT ON CONSTRAINT skillforge_foundation_navigation_nodes_code_ck ON public.nw_foundation_navigation_nodes IS '稳定编码格式与应用输入一致';
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','home','工作台','home',0,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','dashboard','系统状态','dashboard',10,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','tenants','机构管理','tenants',20,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','applications','应用管理','applications',30,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','models','模型管理','models',40,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','runtime_settings','运行配置','runtime_settings',50,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','system_agents','系统智能体','system_agents',60,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','system_agent_tools','系统工具','system_agent_tools',70,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','agent_templates','智能体模板','agent_templates',80,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','capabilities','能力目录','capabilities',90,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','skills','技能工作台','skills',100,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','sessions','会话观察','sessions',110,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','billing','账务管理','billing',120,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','audit','运维审计','audit',130,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','marketplace','市场审核','marketplace',140,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','foundation_access','组织与权限','foundation.access',150,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','foundation_security','账号安全','foundation.security',160,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','foundation_catalog','基础目录与设置','foundation.catalog',170,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','foundation_messaging','公告与通知','foundation.messaging',180,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','foundation_assets','文件与管理任务','foundation.assets',190,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','foundation_operations','日志与运维','foundation.operations_foundation',200,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','home','工作台','home',0,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','agents','智能体','agents',10,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','agent_deployments','公共智能体实例','agent_deployments',20,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','teams','团队','teams',30,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','skills','技能工作台','skills',40,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','workflows','工作流','workflows',50,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','capabilities','能力目录','capabilities',60,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','tool_deployments','工具部署','tool_deployments',70,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','connectors','MCP连接器','connectors',80,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','knowledge','知识库','knowledge',90,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','memory','记忆','memory',100,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','chat','调试会话','chat',110,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','sessions','会话记录','sessions',120,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','conversation_tasks','任务进展','conversation_tasks',130,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','handoff','人工接管','handoff',140,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','channels','渠道','channels',150,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','api_docs','开放API','api_docs',160,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','dashboard','仪表盘','dashboard',170,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','billing_records','计费记录','billing_records',180,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','application_usage','应用用量','application_usage',190,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','governance','治理','governance',200,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','marketplace','市场','marketplace',210,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','settings','运行设置','settings',220,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','foundation_access','组织与权限','foundation.access',230,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','foundation_security','账号安全','foundation.security',240,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','foundation_catalog','基础目录与设置','foundation.catalog',250,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','foundation_messaging','公告与通知','foundation.messaging',260,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','foundation_assets','文件与管理任务','foundation.assets',270,true,'seed','seed');
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','foundation_operations','日志与运维','foundation.operations_foundation',280,true,'seed','seed');

INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('platform','foundation_jobs','管理任务','foundation.jobs',1000,true,'seed','seed');

INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by) VALUES('tenant_template','foundation_jobs','管理任务','foundation.jobs',1000,true,'seed','seed');
