-- 用户端团队复用团队定义和发布版本，仅补充不可变的渠道与本机身份归属。
CREATE TABLE public.nw_chat_user_teams (
    tenant_id bigint NOT NULL,
    team_id varchar(100) NOT NULL,
    channel_id bigint NOT NULL,
    client_ref uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, team_id),
    FOREIGN KEY (tenant_id, team_id) REFERENCES public.nw_agent_teams(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, channel_id, client_ref)
        REFERENCES public.nw_chat_client_profiles(tenant_id, channel_id, client_ref) ON DELETE RESTRICT
);
CREATE INDEX skillforge_chat_user_teams_owner ON public.nw_chat_user_teams(tenant_id, channel_id, client_ref);
ALTER TABLE public.nw_chat_user_teams ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_chat_user_teams FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_chat_user_teams
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
COMMENT ON TABLE public.nw_chat_user_teams IS '渠道凭证范围内由本机聊天身份创建维护的团队；不是独立用户认证';
COMMENT ON COLUMN public.nw_chat_user_teams.tenant_id IS '所属机构';
COMMENT ON COLUMN public.nw_chat_user_teams.team_id IS '现有团队定义 ID';
COMMENT ON COLUMN public.nw_chat_user_teams.channel_id IS '成员授权来源渠道';
COMMENT ON COLUMN public.nw_chat_user_teams.client_ref IS '已登记的本机聊天身份';
COMMENT ON COLUMN public.nw_chat_user_teams.created_at IS '创建时间';
