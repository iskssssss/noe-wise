-- 只为当前知识库运营配置追加新契约版本；历史版本和冻结材料保持原样。
-- 两个字段从未参与实际模型超时控制，真实调用期限由模型目录版本冻结。
DO $$
DECLARE
    source_revision bigint;
    source_values jsonb;
BEGIN
    SELECT g.current_revision, v.values
      INTO source_revision, source_values
      FROM public.nw_platform_operational_setting_groups g
      JOIN public.nw_platform_operational_setting_versions v
        ON v.group_key = g.group_key AND v.revision = g.current_revision
     WHERE g.group_key = 'knowledge'
       FOR UPDATE OF g;

    IF NOT FOUND OR NOT (source_values ?| ARRAY['embedding_timeout_seconds', 'rerank_timeout_seconds']) THEN
        RETURN;
    END IF;

    INSERT INTO public.nw_platform_operational_setting_versions
        (group_key, revision, values, actor_id, publication_source)
    VALUES
        ('knowledge', source_revision + 1,
         source_values - ARRAY['embedding_timeout_seconds', 'rerank_timeout_seconds'],
         NULL, 'system_release');

    UPDATE public.nw_platform_operational_setting_groups
       SET current_revision = source_revision + 1,
           updated_by = NULL,
           updated_at = now()
     WHERE group_key = 'knowledge' AND current_revision = source_revision;
END;
$$;
