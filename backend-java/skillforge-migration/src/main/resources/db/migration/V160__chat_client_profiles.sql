-- 显式登记的本机身份按机构与渠道隔离，昵称继续由 end_users 唯一维护。
ALTER TABLE public.nw_channels ADD CONSTRAINT uq_skillforge_channels_tenant_id UNIQUE (tenant_id,id);
ALTER TABLE public.nw_end_users ADD CONSTRAINT uq_skillforge_end_users_tenant_id UNIQUE (tenant_id,id);
CREATE TABLE public.nw_chat_client_profiles (
    tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
    channel_id bigint NOT NULL,
    client_ref uuid NOT NULL,
    end_user_id bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_chat_client_profiles_pkey PRIMARY KEY (tenant_id,channel_id,client_ref),
    CONSTRAINT fk_chat_profile_channel FOREIGN KEY (tenant_id,channel_id) REFERENCES public.nw_channels(tenant_id,id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_profile_user FOREIGN KEY (tenant_id,end_user_id) REFERENCES public.nw_end_users(tenant_id,id) ON DELETE CASCADE,
    CONSTRAINT uq_chat_profile_user UNIQUE (tenant_id,end_user_id)
);
ALTER TABLE public.nw_chat_client_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_chat_client_profiles FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_chat_client_profiles_tenant_isolation ON public.nw_chat_client_profiles
    USING (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''));
COMMENT ON TABLE public.nw_chat_client_profiles IS '本机聊天身份映射，按租户和渠道幂等登记，不构成独立用户认证';
COMMENT ON COLUMN public.nw_chat_client_profiles.tenant_id IS '所属机构 ID';
COMMENT ON COLUMN public.nw_chat_client_profiles.channel_id IS '认证渠道 ID';
COMMENT ON COLUMN public.nw_chat_client_profiles.client_ref IS '客户端生成并持久化的随机 UUID';
COMMENT ON COLUMN public.nw_chat_client_profiles.end_user_id IS '首次登记创建的终端用户 ID';
COMMENT ON COLUMN public.nw_chat_client_profiles.created_at IS '首次登记时间';
COMMENT ON CONSTRAINT skillforge_chat_client_profiles_pkey ON public.nw_chat_client_profiles IS '同一租户渠道和本机引用只能登记一次';
COMMENT ON CONSTRAINT fk_chat_profile_channel ON public.nw_chat_client_profiles IS '渠道必须属于同一租户，删除渠道清理映射';
COMMENT ON CONSTRAINT fk_chat_profile_user ON public.nw_chat_client_profiles IS '终端用户必须属于同一租户，删除用户清理映射';
COMMENT ON CONSTRAINT uq_chat_profile_user ON public.nw_chat_client_profiles IS '每个登记终端用户只属于一个本机映射';
COMMENT ON CONSTRAINT uq_skillforge_channels_tenant_id ON public.nw_channels IS '支持本机身份映射的同租户复合外键';
COMMENT ON CONSTRAINT uq_skillforge_end_users_tenant_id ON public.nw_end_users IS '支持本机身份映射的同租户复合外键';
