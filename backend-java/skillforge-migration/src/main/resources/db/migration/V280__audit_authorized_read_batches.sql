-- 安全审计检索按同主体/授权版本/精确过滤的一分钟批次留事实；导出、拒绝和权限变更仍逐次记录。
CREATE TABLE public.nw_foundation_audit_read_batches (
    audit_id bigint PRIMARY KEY REFERENCES public.nw_audit_logs(id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    scope varchar(32) NOT NULL CHECK (scope = 'platform' OR scope ~ '^[1-9][0-9]{0,18}$'),
    actor_id varchar(128) NOT NULL,
    authorization_version bigint NOT NULL CHECK (authorization_version >= 0),
    query_digest varchar(64) NOT NULL CHECK (query_digest ~ '^[0-9a-f]{64}$'),
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL CHECK (window_end = window_start + interval '1 minute'),
    filter_action varchar(64),
    filter_actor_id varchar(128),
    filter_category varchar(32),
    filter_outcome varchar(32),
    filter_resource_type varchar(64),
    filter_trace_id varchar(64),
    filter_from timestamptz,
    filter_to timestamptz,
    filter_from_epoch_second bigint,
    filter_from_nano integer,
    filter_to_epoch_second bigint,
    filter_to_nano integer,
    UNIQUE (scope, actor_id, authorization_version, query_digest, window_start),
    CHECK ((filter_from IS NULL AND filter_from_epoch_second IS NULL AND filter_from_nano IS NULL)
        OR (filter_from IS NOT NULL AND filter_from_epoch_second IS NOT NULL AND filter_from_nano IS NOT NULL AND filter_from_nano BETWEEN 0 AND 999999999)),
    CHECK ((filter_to IS NULL AND filter_to_epoch_second IS NULL AND filter_to_nano IS NULL)
        OR (filter_to IS NOT NULL AND filter_to_epoch_second IS NOT NULL AND filter_to_nano IS NOT NULL AND filter_to_nano BETWEEN 0 AND 999999999)),
    CHECK (filter_from IS NULL OR filter_to IS NULL
        OR (filter_from_epoch_second,filter_from_nano) < (filter_to_epoch_second,filter_to_nano))
);
COMMENT ON TABLE public.nw_foundation_audit_read_batches IS '授权读取的不可变精确范围；一分钟内同授权过滤的分页刷新共用首次审计事实，随其按已批准保留策略回收';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.audit_id IS '首次成功受理的安全审计ID，和读取批次同事务提交；审计保护同时保护批次';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.scope IS '可信机构ID或platform，禁止请求自由指定';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.actor_id IS '实时鉴权的操作者身份';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.authorization_version IS '实时账号角色权限版本，变化即创建独立批次';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.query_digest IS '格式1长度前缀UTF-8精确过滤摘要，不含分页';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.window_start IS '一分钟批次包含的UTC起点，不是数据保留期限';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.window_end IS '一分钟批次不包含的UTC终点';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.filter_action IS '精确动作过滤，NULL表示未限定';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.filter_actor_id IS '精确事件操作者过滤，NULL表示未限定';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.filter_category IS '事件分类过滤，NULL表示未限定';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.filter_outcome IS '事件结果过滤，NULL表示未限定';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.filter_resource_type IS '资源类型过滤，NULL表示未限定';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.filter_trace_id IS '精确链路过滤，NULL表示未限定';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.filter_from IS '检索范围包含的事件时刻，NULL表示无起点';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.filter_to IS '检索范围不包含的事件时刻，NULL表示无终点';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.filter_from_epoch_second IS '原请求起点整秒，和nano无损保存过滤摘要的精确输入';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.filter_from_nano IS '原请求起点纳秒，避免数据库微秒舍入丢失审计范围';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.filter_to_epoch_second IS '原请求终点整秒，和nano无损保存过滤摘要的精确输入';
COMMENT ON COLUMN public.nw_foundation_audit_read_batches.filter_to_nano IS '原请求终点纳秒；范围顺序按精确秒/纳秒判定';

ALTER TABLE public.nw_foundation_audit_read_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_audit_read_batches FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_foundation_audit_read_batches
    USING (scope = COALESCE(NULLIF(current_setting('app.tenant_id', true), ''),
        CASE WHEN current_setting('app.platform_admin', true) = 'true' THEN 'platform' END))
    WITH CHECK (scope = COALESCE(NULLIF(current_setting('app.tenant_id', true), ''),
        CASE WHEN current_setting('app.platform_admin', true) = 'true' THEN 'platform' END));
GRANT SELECT, INSERT ON public.nw_foundation_audit_read_batches TO skillforge;

CREATE FUNCTION public.foundation_audit_read_batch_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.nw_audit_logs a WHERE a.id = NEW.audit_id
        AND COALESCE(a.tenant_id::text, 'platform') = NEW.scope AND a.actor_id = NEW.actor_id
        AND a.action = 'sensitive.audit.read' AND a.resource_type = 'audit_query'
        AND a.resource_id = NEW.query_digest AND a.outcome = 'success') THEN
        RAISE EXCEPTION 'audit_read_batch_reference_invalid';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER foundation_audit_read_batch_reference
    AFTER INSERT ON public.nw_foundation_audit_read_batches DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.foundation_audit_read_batch_reference();

CREATE FUNCTION public.foundation_audit_read_batch_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- 只允许父审计已被其年龄/调查保护守卫批准删除后的FK级联，标记本身不能独立删除范围事实。
    IF TG_OP = 'UPDATE' OR current_setting('app.audit_cleanup', true) IS DISTINCT FROM 'on'
        OR EXISTS (SELECT 1 FROM public.nw_audit_logs a WHERE a.id = OLD.audit_id) THEN
        RAISE EXCEPTION 'audit_read_batch_immutable';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER foundation_audit_read_batch_immutable
    BEFORE UPDATE OR DELETE ON public.nw_foundation_audit_read_batches
    FOR EACH ROW EXECUTE FUNCTION public.foundation_audit_read_batch_immutable();
