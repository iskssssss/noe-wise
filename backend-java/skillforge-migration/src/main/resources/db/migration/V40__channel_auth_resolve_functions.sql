-- V40: 渠道外部认证解析函数（SECURITY DEFINER）
-- 背景：channels 表 FORCE ROW LEVEL SECURITY（tenant_id 策略），而外部认证
-- （C 端渠道令牌 / OpenAPI API Key）在解析出租户之前必须先按令牌定位渠道行；
-- 受限数据库角色下该查询会被 RLS 拦截（app.tenant_id 未设置 → 策略不匹配）。
-- 方案：白名单化（仅接受哈希参数、WHERE 固定）的 SECURITY DEFINER 函数——函数
-- 属主（迁移执行者，须为超级用户或 BYPASSRLS 角色）绕过 RLS，应用角色仅获
-- EXECUTE 权限。开发环境 POSTGRES_USER=skillforge 即为超级用户，天然成立；
-- 生产环境由 DBA 以超级用户执行迁移，并按实际应用角色调整下方 GRANT。

-- 按渠道令牌哈希定位激活渠道（C 端渠道认证，接口契约 §2.2）
CREATE FUNCTION public.resolve_channel_by_token_hash(p_token_hash text)
    RETURNS SETOF public.nw_channels
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = public
AS $$
    SELECT * FROM public.nw_channels
    WHERE channel_token_hash = p_token_hash
      AND status = 'active';
$$;

COMMENT ON FUNCTION public.resolve_channel_by_token_hash(text) IS '渠道外部认证用：按渠道令牌哈希定位激活渠道（SECURITY DEFINER 绕过 RLS，仅接受哈希参数；属主须为超级用户/BYPASSRLS）';

-- 按 OpenAPI API Key 哈希定位激活渠道（OpenAPI 集成方认证，接口契约 §2.5）
CREATE FUNCTION public.resolve_channel_by_api_key_hash(p_api_key_hash text)
    RETURNS SETOF public.nw_channels
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = public
AS $$
    SELECT * FROM public.nw_channels
    WHERE api_key_hash = p_api_key_hash
      AND channel_type = 'openapi'
      AND status = 'active';
$$;

COMMENT ON FUNCTION public.resolve_channel_by_api_key_hash(text) IS '渠道外部认证用：按 OpenAPI API Key 哈希定位激活渠道（SECURITY DEFINER 绕过 RLS，仅接受哈希参数；属主须为超级用户/BYPASSRLS）';

REVOKE ALL ON FUNCTION public.resolve_channel_by_token_hash(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.resolve_channel_by_api_key_hash(text) FROM PUBLIC;

-- 应用角色执行权限（生产环境按实际应用角色执行等价 GRANT）
GRANT EXECUTE ON FUNCTION public.resolve_channel_by_token_hash(text) TO skillforge;
GRANT EXECUTE ON FUNCTION public.resolve_channel_by_api_key_hash(text) TO skillforge;
