-- SDK 拥有供应商目录；宿主只保存租户选择、凭据和端点访问策略所需事实。

ALTER TABLE public.nw_tenants
    ADD COLUMN custom_llm_provider_id character varying(64),
    ADD COLUMN custom_llm_service_id character varying(64),
    ADD COLUMN custom_llm_profile_version character varying(64),
    ADD COLUMN custom_llm_protocol character varying(64),
    ADD COLUMN custom_llm_authentication_scheme character varying(64),
    ADD COLUMN custom_llm_dialect_version character varying(64);

ALTER TABLE public.nw_agents
    ADD COLUMN llm_provider_id character varying(64),
    ADD COLUMN llm_service_id character varying(64),
    ADD COLUMN llm_profile_version character varying(64),
    ADD COLUMN llm_protocol character varying(64),
    ADD COLUMN llm_authentication_scheme character varying(64),
    ADD COLUMN llm_dialect_version character varying(64);

ALTER TABLE public.nw_tenants
    ADD CONSTRAINT tenant_llm_provider_selection_complete CHECK (
        (custom_llm_provider_id IS NULL
         AND custom_llm_service_id IS NULL
         AND custom_llm_profile_version IS NULL
         AND custom_llm_protocol IS NULL
         AND custom_llm_authentication_scheme IS NULL
         AND custom_llm_dialect_version IS NULL)
        OR
        (custom_llm_provider_id IS NOT NULL
         AND custom_llm_service_id IS NOT NULL
         AND custom_llm_profile_version IS NOT NULL
         AND custom_llm_protocol IS NOT NULL
         AND custom_llm_authentication_scheme IS NOT NULL
         AND custom_llm_dialect_version IS NOT NULL
         AND length(custom_llm_provider_id) > 0
         AND length(custom_llm_service_id) > 0
         AND length(custom_llm_profile_version) > 0
         AND length(custom_llm_protocol) > 0
         AND length(custom_llm_authentication_scheme) > 0
         AND length(custom_llm_dialect_version) > 0));

ALTER TABLE public.nw_agents
    ADD CONSTRAINT agent_llm_provider_selection_complete CHECK (
        (llm_provider_id IS NULL
         AND llm_service_id IS NULL
         AND llm_profile_version IS NULL
         AND llm_protocol IS NULL
         AND llm_authentication_scheme IS NULL
         AND llm_dialect_version IS NULL)
        OR
        (llm_provider_id IS NOT NULL
         AND llm_service_id IS NOT NULL
         AND llm_profile_version IS NOT NULL
         AND llm_protocol IS NOT NULL
         AND llm_authentication_scheme IS NOT NULL
         AND llm_dialect_version IS NOT NULL
         AND length(llm_provider_id) > 0
         AND length(llm_service_id) > 0
         AND length(llm_profile_version) > 0
         AND length(llm_protocol) > 0
         AND length(llm_authentication_scheme) > 0
         AND length(llm_dialect_version) > 0));

ALTER TABLE public.agent_runtime_model_bindings
    ADD COLUMN binding_format_version smallint NOT NULL DEFAULT 3
        CHECK (binding_format_version = 3),
    ADD COLUMN provider_id character varying(64) NOT NULL,
    ADD COLUMN service_id character varying(64) NOT NULL,
    ADD COLUMN profile_version character varying(64) NOT NULL,
    ADD COLUMN protocol character varying(64) NOT NULL,
    ADD COLUMN authentication_scheme character varying(64) NOT NULL,
    ADD COLUMN dialect_version character varying(64) NOT NULL,
    ADD CONSTRAINT model_binding_provider_profile_complete CHECK (
        binding_format_version = 3
         AND length(provider_id) > 0
         AND length(service_id) > 0
         AND length(profile_version) > 0
         AND length(protocol) > 0
         AND length(authentication_scheme) > 0
         AND length(dialect_version) > 0);

COMMENT ON COLUMN public.nw_tenants.custom_llm_provider_id IS 'SDK 供应商目录中的 LLM 品牌标识';
COMMENT ON COLUMN public.nw_tenants.custom_llm_service_id IS 'SDK 供应商目录中的实际接入服务标识';
COMMENT ON COLUMN public.nw_tenants.custom_llm_profile_version IS '创建当前 LLM 选择时使用的 SDK ServiceProfile 版本';
COMMENT ON COLUMN public.nw_tenants.custom_llm_protocol IS 'SDK 模型协议标识';
COMMENT ON COLUMN public.nw_tenants.custom_llm_authentication_scheme IS 'SDK 认证方案标识';
COMMENT ON COLUMN public.nw_tenants.custom_llm_dialect_version IS 'SDK 协议方言版本';
COMMENT ON COLUMN public.nw_agents.llm_provider_id IS 'Agent 覆盖使用的 SDK LLM 品牌标识';
COMMENT ON COLUMN public.nw_agents.llm_service_id IS 'Agent 覆盖使用的 SDK LLM 接入服务标识';
COMMENT ON COLUMN public.nw_agents.llm_profile_version IS 'Agent 覆盖使用的 SDK ServiceProfile 版本';
COMMENT ON COLUMN public.nw_agents.llm_protocol IS 'Agent 覆盖使用的 SDK 模型协议标识';
COMMENT ON COLUMN public.nw_agents.llm_authentication_scheme IS 'Agent 覆盖使用的 SDK 认证方案标识';
COMMENT ON COLUMN public.nw_agents.llm_dialect_version IS 'Agent 覆盖使用的 SDK 协议方言版本';
COMMENT ON COLUMN public.agent_runtime_model_bindings.binding_format_version IS '冻结绑定摘要格式；当前只接受包含完整供应商 profile 的版本 3';
COMMENT ON COLUMN public.agent_runtime_model_bindings.provider_id IS '冻结的 SDK LLM 品牌标识；格式 3 必填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.service_id IS '冻结的 SDK LLM 实际接入服务标识；格式 3 必填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.profile_version IS '冻结的 SDK ServiceProfile 版本；格式 3 必填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.protocol IS '冻结的 SDK 模型协议标识；格式 3 必填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.authentication_scheme IS '冻结的 SDK 认证方案标识；格式 3 必填';
COMMENT ON COLUMN public.agent_runtime_model_bindings.dialect_version IS '冻结的 SDK 协议方言版本；格式 3 必填';
COMMENT ON CONSTRAINT tenant_llm_provider_selection_complete ON public.nw_tenants IS '租户 LLM 供应商选择为空或完整，禁止部分组合';
COMMENT ON CONSTRAINT agent_llm_provider_selection_complete ON public.nw_agents IS 'Agent LLM 供应商选择为空或完整，禁止部分组合';
COMMENT ON CONSTRAINT model_binding_provider_profile_complete ON public.agent_runtime_model_bindings IS '每个冻结绑定都必须使用版本 3 并包含完整 SDK 供应商 profile';
