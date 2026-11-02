-- 分发策略没有开放默认值；未明确分类的定义不能发布或消费。
ALTER TABLE public.nw_agent_templates ADD COLUMN distribution_policy varchar(32);
ALTER TABLE public.nw_agent_templates ADD CONSTRAINT skillforge_agent_templates_distribution_policy_check
    CHECK (distribution_policy IN ('OPEN_INSTALL', 'AUTHORIZED_DEPLOY'));
ALTER TABLE public.nw_agent_templates ADD CONSTRAINT skillforge_agent_templates_published_distribution_policy_check
    CHECK (published_at IS NULL OR distribution_policy IS NOT NULL);
COMMENT ON COLUMN public.nw_agent_templates.distribution_policy IS '定义消费策略：OPEN_INSTALL独立安装，AUTHORIZED_DEPLOY显式授权部署；无默认值，首次发布后不可变';

CREATE FUNCTION public.guard_agent_distribution_policy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.published_at IS NOT NULL AND NEW.distribution_policy IS DISTINCT FROM OLD.distribution_policy THEN
        RAISE EXCEPTION 'published agent definition distribution policy is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER skillforge_agent_templates_distribution_policy_immutable BEFORE UPDATE ON public.nw_agent_templates
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_distribution_policy();
COMMENT ON FUNCTION public.guard_agent_distribution_policy() IS '在权威定义行禁止首次发布后的分发策略切换；不修改历史发布快照';

ALTER TABLE public.nw_agent_templates DROP COLUMN application_managed;
