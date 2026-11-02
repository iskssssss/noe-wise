-- 应用来源独立于动态消息 context；Session 首次绑定后所有 Run/委派复用冻结事实。
ALTER TABLE public.nw_chat_sessions ALTER COLUMN scope_type TYPE varchar(160);
ALTER TABLE public.nw_chat_sessions ALTER COLUMN scope_id TYPE varchar(512);
ALTER TABLE public.nw_chat_sessions ADD COLUMN application_id varchar(64);
ALTER TABLE public.nw_chat_sessions ADD COLUMN integration_id varchar(128);
ALTER TABLE public.nw_chat_sessions ADD COLUMN business_scope_id varchar(64);
ALTER TABLE public.nw_chat_sessions ADD CONSTRAINT ck_skillforge_chat_sessions_application_source CHECK(application_id IS NULL OR integration_id IS NOT NULL);
ALTER TABLE public.nw_chat_sessions ADD CONSTRAINT ck_skillforge_chat_sessions_business_scope_source CHECK(business_scope_id IS NULL OR application_id IS NOT NULL);
COMMENT ON COLUMN public.nw_chat_sessions.application_id IS '从已认证 application/integration 关联冻结的应用数字标识';
COMMENT ON COLUMN public.nw_chat_sessions.integration_id IS '已认证执行凭证冻结的 integration，不由用户消息覆盖';
COMMENT ON COLUMN public.nw_chat_sessions.business_scope_id IS '应用已声明授权的机构内业务范围标识';
ALTER TABLE public.nw_chat_sessions ADD CONSTRAINT uq_skillforge_chat_sessions_tenant_business_id UNIQUE (tenant_id, session_id);
ALTER TABLE public.nw_chat_sessions ADD CONSTRAINT fk_skillforge_chat_sessions_application_tenant FOREIGN KEY(application_id,tenant_id)
    REFERENCES public.nw_integration_application_tenants(application_id,tenant_id);
ALTER TABLE public.nw_chat_sessions ADD CONSTRAINT fk_skillforge_chat_sessions_business_scope FOREIGN KEY(application_id,tenant_id,business_scope_id)
    REFERENCES public.nw_integration_business_scopes(application_id,tenant_id,scope_id);
CREATE UNIQUE INDEX uq_skillforge_chat_sessions_integration_conversation_agent ON public.nw_chat_sessions(tenant_id,integration_id,scope_id,agent_id)
    WHERE integration_id IS NOT NULL;
COMMENT ON INDEX public.uq_skillforge_chat_sessions_integration_conversation_agent IS '一个机构外部聊天室按 Agent 实例稳定分别映射 Session';

CREATE TABLE public.nw_integration_run_sources (
    tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id) ON DELETE CASCADE,
    run_id varchar(512) NOT NULL,
    parent_run_id varchar(512),
    application_id varchar(64),
    integration_id varchar(128) NOT NULL,
    business_scope_id varchar(64),
    agent_id bigint NOT NULL REFERENCES public.nw_agents(id),
    session_id varchar(512) NOT NULL,
    PRIMARY KEY (tenant_id,run_id),
    FOREIGN KEY (tenant_id,session_id) REFERENCES public.nw_chat_sessions(tenant_id,session_id) ON DELETE CASCADE,
    FOREIGN KEY (application_id,tenant_id) REFERENCES public.nw_integration_application_tenants(application_id,tenant_id),
    FOREIGN KEY (application_id,tenant_id,business_scope_id) REFERENCES public.nw_integration_business_scopes(application_id,tenant_id,scope_id),
    FOREIGN KEY (tenant_id,parent_run_id) REFERENCES public.nw_integration_run_sources(tenant_id,run_id)
    ,CHECK(business_scope_id IS NULL OR application_id IS NOT NULL)
);
COMMENT ON TABLE public.nw_integration_run_sources IS '主 Run 和委派子 Run 的不可变应用用量来源；在 Run 绑定事务中写入';
COMMENT ON COLUMN public.nw_integration_run_sources.tenant_id IS '执行机构隔离边界';
COMMENT ON COLUMN public.nw_integration_run_sources.run_id IS '即将受理的真实 Runtime Run 稳定标识';
COMMENT ON COLUMN public.nw_integration_run_sources.parent_run_id IS '委派父 Run 来源；主 Run 可空';
COMMENT ON COLUMN public.nw_integration_run_sources.application_id IS '应用关联数字标识；独立 integration 可空';
COMMENT ON COLUMN public.nw_integration_run_sources.integration_id IS '首次受理的可信 integration 标识';
COMMENT ON COLUMN public.nw_integration_run_sources.business_scope_id IS '从产品 Session 继承的业务范围标识';
COMMENT ON COLUMN public.nw_integration_run_sources.agent_id IS '实际执行的机构 Agent 实例标识';
COMMENT ON COLUMN public.nw_integration_run_sources.session_id IS '来源产品 Session；委派保留根产品 Session';
ALTER TABLE public.nw_integration_run_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_integration_run_sources FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_integration_run_sources_tenant ON public.nw_integration_run_sources
    USING (tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK (tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''));

ALTER TABLE public.nw_control_operations ADD COLUMN application_id varchar(64);
ALTER TABLE public.nw_control_operations ADD CONSTRAINT fk_control_operation_application_tenant FOREIGN KEY(application_id,tenant_id)
    REFERENCES public.nw_integration_application_tenants(application_id,tenant_id);
COMMENT ON COLUMN public.nw_control_operations.application_id IS '控制操作受理时从认证 integration 关联冻结的应用数字标识';
