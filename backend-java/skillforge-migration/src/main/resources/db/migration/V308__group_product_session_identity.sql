-- 群产品Session与个人执行Session分开准入；typed Runtime执行表在GI-03接通群Run时扩展。
ALTER TABLE public.nw_chat_sessions
    ADD COLUMN group_binding_id bigint,
    ALTER COLUMN end_user_id DROP NOT NULL,
    DROP CONSTRAINT ck_skillforge_chat_sessions_target_exactly_one,
    ADD CONSTRAINT skillforge_chat_sessions_subject_target CHECK (
        (group_binding_id IS NULL AND end_user_id IS NOT NULL
            AND ((agent_id IS NULL) <> (team_id IS NULL)))
        OR (group_binding_id IS NOT NULL AND group_binding_id > 0
            AND end_user_id IS NULL AND agent_id IS NULL AND team_id IS NULL
            AND channel_id IS NULL AND origin_kind = 'external'
            AND integration_id IS NOT NULL AND business_scope_id IS NOT NULL
            AND scope_type IS NOT NULL AND scope_id IS NOT NULL
            AND scope_type = 'group:' || integration_id AND scope_id = group_binding_id::text));
COMMENT ON COLUMN public.nw_chat_sessions.group_binding_id IS '群Session唯一subject与GROUP target；成员或服务Agent变化不改变Session，个人会话为空';
COMMENT ON CONSTRAINT skillforge_chat_sessions_subject_target ON public.nw_chat_sessions IS 'User只配AGENT/TEAM；Group没有owner EndUser或固定Agent/Team，并冻结可信integration和业务范围';

CREATE FUNCTION public.guard_session_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.tenant_id,NEW.session_id,NEW.end_user_id,NEW.group_binding_id,NEW.agent_id,NEW.team_id,
           NEW.channel_id,NEW.scope_type,NEW.scope_id,NEW.origin_kind,NEW.actor_ref,NEW.responsibility_ref,
           NEW.integration_id,NEW.business_scope_id)
       IS DISTINCT FROM
       ROW(OLD.tenant_id,OLD.session_id,OLD.end_user_id,OLD.group_binding_id,OLD.agent_id,OLD.team_id,
           OLD.channel_id,OLD.scope_type,OLD.scope_id,OLD.origin_kind,OLD.actor_ref,OLD.responsibility_ref,
           OLD.integration_id,OLD.business_scope_id) THEN
        RAISE EXCEPTION 'session identity is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.group_binding_id IS NOT NULL AND OLD.metadata ? 'closed_at'
       AND NOT COALESCE(NEW.metadata ? 'closed_at',false) THEN
        RAISE EXCEPTION 'closed group session cannot reopen' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
COMMENT ON FUNCTION public.guard_session_identity() IS 'Session首次写入固定subject、target、来源及责任；群关闭不能复活';
CREATE TRIGGER skillforge_chat_sessions_identity_immutable BEFORE UPDATE ON public.nw_chat_sessions
    FOR EACH ROW EXECUTE FUNCTION public.guard_session_identity();
