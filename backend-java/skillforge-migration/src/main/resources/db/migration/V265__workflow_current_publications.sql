-- 稳定 Workflow 身份只有一个工作草稿；发布快照独立保存，多次保存只推进并发游标。
ALTER TABLE nw_workflow_definition_versions DROP CONSTRAINT uq_skillforge_workflow_definition_versions_source_draft;
ALTER TABLE nw_workflow_draft_revisions ADD CONSTRAINT uq_workflow_current_draft UNIQUE(container_id);
ALTER TABLE nw_workflow_builder_snapshots ADD CONSTRAINT uq_workflow_current_builder_snapshot UNIQUE(container_id);

COMMENT ON TABLE nw_workflow_draft_revisions IS '稳定工作流身份下的唯一可变工作草稿；revision 是保存并发游标，不是发布历史';
COMMENT ON COLUMN nw_workflow_draft_revisions.revision IS '工作草稿 CAS 并发游标，每次保存递增，正文原地替换';
COMMENT ON COLUMN nw_workflow_definition_versions.source_draft_id IS '生成发布快照的稳定工作草稿身份；后续修改草稿不改变本发布定义';
COMMENT ON COLUMN nw_workflow_containers.active_definition_version_id IS '当前生效的不可变发布快照；创建或保存工作草稿不改变此指针';
COMMENT ON TABLE nw_workflow_builder_snapshots IS '唯一工作草稿的布局与视口；保存定义时沿用布局并推进 CAS 游标，不属于运行定义';
COMMENT ON COLUMN nw_workflow_instances.definition_version_id IS '安装记录的定义锚点，用于确定稳定工作流容器；新执行解析容器当前发布定义';
