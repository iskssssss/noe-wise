-- Workflow 实例只绑定 Builder 发布的不可变 DefinitionVersion。

ALTER TABLE public.nw_workflow_instances
    ADD CONSTRAINT fk_skillforge_workflow_instances_definition_version
    FOREIGN KEY (definition_version_id)
    REFERENCES public.nw_workflow_definition_versions(id)
    ON DELETE RESTRICT;

CREATE INDEX idx_skillforge_workflow_instances_definition_version
    ON public.nw_workflow_instances (definition_version_id);

COMMENT ON COLUMN public.nw_workflow_instances.definition_version_id IS
    '安装或显式升级时冻结的 workflow_definition_versions.id；运行时唯一读取来源';
