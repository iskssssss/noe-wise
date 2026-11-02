-- 宿主衰减投影由权威 head 的状态变化驱动，和恢复事实共享 Runtime 的提交事务。
-- 使用调用者权限执行，租户 RLS 及写入权限不因触发器而被绕过。
CREATE FUNCTION public.reset_memory_decay_on_activation()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    previous_status text;
    current_status text;
BEGIN
    SELECT status INTO previous_status FROM public.agent_runtime_memory_fact_revisions
        WHERE tenant_id=OLD.tenant_id AND fact_id=OLD.fact_id AND revision=OLD.current_revision;
    SELECT status INTO current_status FROM public.agent_runtime_memory_fact_revisions
        WHERE tenant_id=NEW.tenant_id AND fact_id=NEW.fact_id AND revision=NEW.current_revision;
    IF previous_status IS NOT NULL AND previous_status <> 'ACTIVE' AND current_status = 'ACTIVE' THEN
        INSERT INTO public.agent_runtime_memory_usage_projection
            (tenant_id,fact_id,projected_decay_score,last_referenced_at,updated_at)
        VALUES (NEW.tenant_id,NEW.fact_id,0,now(),now())
        ON CONFLICT (tenant_id,fact_id) DO UPDATE
            SET projected_decay_score=0,last_referenced_at=now(),updated_at=now();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_memory_activation_decay_projection
    AFTER UPDATE OF current_revision ON public.agent_runtime_memory_fact_heads
    FOR EACH ROW WHEN (OLD.current_revision IS DISTINCT FROM NEW.current_revision)
    EXECUTE FUNCTION public.reset_memory_decay_on_activation();

COMMENT ON FUNCTION public.reset_memory_decay_on_activation() IS
    '宿主 Memory 状态恢复投影：仅非 ACTIVE 到 ACTIVE 的真实 head 推进重置衰减，事务失败整体回滚，幂等重放不重置';
COMMENT ON TRIGGER trg_memory_activation_decay_projection ON public.agent_runtime_memory_fact_heads IS
    '恢复事实与衰减投影原子提交，保持调用者 RLS，Runtime SDK 不依赖宿主投影表';
