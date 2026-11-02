-- Workflow 市场模板元数据。

ALTER TABLE public.nw_workflow_templates
    ADD COLUMN connector_requirements jsonb,
    ADD COLUMN tools jsonb;

COMMENT ON COLUMN public.nw_workflow_templates.connector_requirements IS '安装时必须满足的 Connector 能力声明';
COMMENT ON COLUMN public.nw_workflow_templates.tools IS 'Workflow Tool 节点引用的工具名集合';
COMMENT ON COLUMN public.nw_workflow_templates.type IS 'Workflow 引擎形态';
