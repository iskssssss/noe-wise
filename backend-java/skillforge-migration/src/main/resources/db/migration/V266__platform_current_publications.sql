-- 平台资源采用稳定身份、可重复保存的工作草稿及不可变发布历史。
ALTER TABLE nw_platform_models ALTER COLUMN current_revision DROP NOT NULL;
ALTER TABLE nw_platform_models ADD COLUMN draft_revision bigint NOT NULL DEFAULT 1 CHECK (draft_revision > 0);
ALTER TABLE nw_platform_models ADD COLUMN draft jsonb NOT NULL CHECK (jsonb_typeof(draft) = 'object');
ALTER TABLE nw_platform_models ADD COLUMN draft_changed boolean NOT NULL DEFAULT true;
COMMENT ON COLUMN nw_platform_models.draft_revision IS '工作草稿 CAS 修订号，保存递增，不属于发布历史';
COMMENT ON COLUMN nw_platform_models.draft IS '平台模型工作草稿快照，凭据仅密文，发布前不对业务执行生效';
COMMENT ON COLUMN nw_platform_models.draft_changed IS '工作草稿存在未发布修改';
COMMENT ON COLUMN nw_platform_models.current_revision IS '当前不可变发布配置修订，首次发布前为空';

ALTER TABLE public.nw_agent_templates DROP CONSTRAINT skillforge_agent_templates_template_key_version_key;
ALTER TABLE public.nw_agent_templates ADD CONSTRAINT skillforge_agent_templates_template_key_key UNIQUE(template_key);
ALTER TABLE public.nw_agent_templates ADD COLUMN has_unpublished_changes boolean NOT NULL DEFAULT true;
COMMENT ON COLUMN public.nw_agent_templates.has_unpublished_changes IS '稳定模板工作草稿存在未发布修改';
COMMENT ON COLUMN public.nw_agent_templates.version IS '当前发布版本，首次发布前为待发布版本';
COMMENT ON COLUMN public.nw_agent_templates.definition IS '工作草稿定义，线上安装只读取独立发布快照';
CREATE TABLE public.nw_agent_template_publications (
    template_id bigint NOT NULL REFERENCES public.nw_agent_templates(id) ON DELETE RESTRICT,
    version varchar(50) NOT NULL,
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    published_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(template_id,version)
);
COMMENT ON TABLE public.nw_agent_template_publications IS '智能体模板不可变发布历史，安装按稳定模板身份读取当前发布版本';
COMMENT ON COLUMN public.nw_agent_template_publications.template_id IS '稳定模板身份';
COMMENT ON COLUMN public.nw_agent_template_publications.version IS '该次发布的版本号';
COMMENT ON COLUMN public.nw_agent_template_publications.snapshot IS '含定义和公开元数据的完整模板发布快照';
COMMENT ON COLUMN public.nw_agent_template_publications.published_at IS '发布记录写入时间';
CREATE TRIGGER agent_template_publication_immutable BEFORE UPDATE OR DELETE ON public.nw_agent_template_publications
    FOR EACH ROW EXECUTE FUNCTION reject_platform_configuration_version_change();

ALTER TABLE public.nw_platform_agent_tool_deployments ALTER COLUMN current_revision DROP NOT NULL;
ALTER TABLE public.nw_platform_agent_tool_deployments ADD COLUMN draft_revision bigint NOT NULL DEFAULT 1 CHECK(draft_revision > 0);
ALTER TABLE public.nw_platform_agent_tool_deployments ADD COLUMN draft jsonb NOT NULL CHECK(jsonb_typeof(draft) = 'object');
ALTER TABLE public.nw_platform_agent_tool_deployments ADD COLUMN draft_changed boolean NOT NULL DEFAULT true;
ALTER TABLE public.nw_platform_agent_tool_deployment_versions ADD COLUMN tool_definition_id bigint NOT NULL
    REFERENCES public.nw_capability_definitions(id) ON DELETE RESTRICT;
COMMENT ON COLUMN public.nw_platform_agent_tool_deployments.current_revision IS '当前发布工具部署修订，首次发布前为空';
COMMENT ON COLUMN public.nw_platform_agent_tool_deployments.draft_revision IS '工具部署工作草稿 CAS 修订号';
COMMENT ON COLUMN public.nw_platform_agent_tool_deployments.draft IS '稳定能力身份引用及工具执行配置的工作草稿';
COMMENT ON COLUMN public.nw_platform_agent_tool_deployments.draft_changed IS '工具部署工作草稿存在未发布修改';
COMMENT ON COLUMN public.nw_platform_agent_tool_deployment_versions.tool_definition_id IS '工具能力稳定身份，新的运行解析其当前发布版本';
