-- 用量事件补齐 Workflow 实例关联列；CostEventDO 已按目标模型读取该字段。
ALTER TABLE public.nw_usage_events
    ADD COLUMN workflow_instance_id bigint;

CREATE INDEX idx_skillforge_usage_events_workflow
    ON public.nw_usage_events (tenant_id, workflow_instance_id, occurred_at)
    WHERE workflow_instance_id IS NOT NULL;

COMMENT ON COLUMN public.nw_usage_events.workflow_instance_id IS
    '工作流实例 ID；可空，仅 Workflow 外部动作或工作流内调用写入';
