-- Agent 自身拥有的原生委派配置；关闭时仍保留名单，避免把开关状态写入 Runtime 或团队定义。
ALTER TABLE public.nw_agents
    ADD COLUMN delegation_config jsonb NOT NULL
        DEFAULT '{"enabled":false,"target_agent_ids":[]}'::jsonb,
    ADD CONSTRAINT skillforge_agents_delegation_config_shape CHECK (
        jsonb_typeof(delegation_config) = 'object'
        AND delegation_config ? 'enabled'
        AND jsonb_typeof(delegation_config->'enabled') = 'boolean'
        AND delegation_config ? 'target_agent_ids'
        AND jsonb_typeof(delegation_config->'target_agent_ids') = 'array'
    );

COMMENT ON COLUMN public.nw_agents.delegation_config IS
    'Agent 原生委派配置：enabled 开关和 target_agent_ids 允许目标名单';
