-- 自然语言交付要求由主Agent与适用Skill执行，不再创建独立语义解释或强制契约。
-- 专属用途退役但保留其真实系统Run、发布版本、审计、外调、用量及模型材料。
UPDATE public.nw_platform_system_agents
   SET status='archived',revision=revision+1,updated_at=clock_timestamp()
 WHERE purpose='output-requirements' AND status<>'archived';

-- 完整专属闭包显式列出；不级联删除任何真实业务成果、文件或Runtime事实。
DROP TABLE public.nw_output_requirement_run_bindings,
           public.nw_output_requirement_changes,
           public.nw_output_requirement_evidence,
           public.nw_output_requirement_nodes,
           public.nw_output_requirement_items,
           public.nw_output_requirement_artifact_context,
           public.nw_output_requirement_sources,
           public.nw_output_requirement_contracts;
DROP FUNCTION public.output_requirement_immutable();
