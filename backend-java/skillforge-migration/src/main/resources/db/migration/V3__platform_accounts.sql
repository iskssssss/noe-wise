CREATE TABLE public.nw_platform_members (
    id bigint NOT NULL,
    username character varying(128) NOT NULL,
    password_hash character varying(256) NOT NULL,
    email character varying(256),
    display_name character varying(128),
    status character varying(32) DEFAULT 'active'::character varying NOT NULL,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT skillforge_platform_members_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_platform_members_username UNIQUE (username)
);

COMMENT ON TABLE public.nw_platform_members IS '平台运营后台账号，独立于租户账号体系，不启用租户 RLS';
COMMENT ON COLUMN public.nw_platform_members.id IS '主键雪花 ID';
COMMENT ON COLUMN public.nw_platform_members.username IS '登录用户名，全局唯一';
COMMENT ON COLUMN public.nw_platform_members.password_hash IS '密码哈希（BCrypt）';
COMMENT ON COLUMN public.nw_platform_members.email IS '邮箱，可空';
COMMENT ON COLUMN public.nw_platform_members.display_name IS '显示名称，可空';
COMMENT ON COLUMN public.nw_platform_members.status IS '账号状态：active / disabled';
COMMENT ON COLUMN public.nw_platform_members.last_login_at IS '最近登录时间，可空';
COMMENT ON COLUMN public.nw_platform_members.created_at IS '创建时间';
COMMENT ON COLUMN public.nw_platform_members.updated_at IS '更新时间';
