-- V71: Open Platform 外部用户与内部 EndUser 的稳定映射。
ALTER TABLE public.nw_end_users
    ADD COLUMN external_integration_id varchar(128),
    ADD COLUMN external_user_ref varchar(512);

CREATE UNIQUE INDEX uq_skillforge_end_users_open_platform_external_ref
    ON public.nw_end_users (tenant_id, external_integration_id, external_user_ref)
    WHERE external_integration_id IS NOT NULL AND external_user_ref IS NOT NULL;

COMMENT ON COLUMN public.nw_end_users.external_integration_id IS
    'Open Platform 集成标识；与 external_user_ref 共同构成外部用户唯一键';
COMMENT ON COLUMN public.nw_end_users.external_user_ref IS
    'Open Platform 外部用户稳定引用，不可因会话重试覆盖';
