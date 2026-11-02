-- Workflow 沿用自己的 Execution 状态；只将原 owner 投影扩成正式 USER/GROUP，
-- 不创建第二执行链。非空主体外键防止 GROUP 的 NULL end_user_id 跳过归属检查。
ALTER TABLE public.agent_runtime_execution_runs
    ADD CONSTRAINT execution_runs_workflow_subject_uq
    UNIQUE(tenant_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id);

DO $$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'agent_runtime_executions','agent_runtime_execution_mutations',
        'nw_runtime_workflow_node_preparations','agent_runtime_workflow_script_calls',
        'agent_runtime_workflow_child_preparations','agent_runtime_workflow_child_execution_sources']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ALTER COLUMN end_user_id DROP NOT NULL,
            ADD COLUMN subject_kind text GENERATED ALWAYS AS
                (CASE WHEN target_kind=''GROUP'' THEN ''GROUP'' ELSE ''USER'' END) STORED NOT NULL,
            ADD COLUMN subject_id text GENERATED ALWAYS AS
                (CASE WHEN target_kind=''GROUP'' THEN target_id ELSE end_user_id::text END) STORED NOT NULL,
            ADD CONSTRAINT %I CHECK (
                btrim(tenant_id)<>'''' AND btrim(session_id)<>'''' AND btrim(target_id)<>''''
                AND btrim(agent_id)<>'''' AND btrim(run_id)<>''''
                AND ((target_kind IN (''AGENT'',''TEAM'') AND end_user_id IS NOT NULL AND end_user_id>0)
                    OR (target_kind=''GROUP'' AND end_user_id IS NULL)))',
            table_name,table_name || '_subject_ck');
    END LOOP;
END $$;

ALTER TABLE public.agent_runtime_executions
    DROP CONSTRAINT agent_runtime_executions_target_check,
    DROP CONSTRAINT agent_runtime_execution_run_fkey,
    ADD CONSTRAINT workflow_execution_subject_uq
        UNIQUE(tenant_id,execution_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id),
    ADD CONSTRAINT workflow_execution_subject_run_fk
        FOREIGN KEY(tenant_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id)
        REFERENCES public.agent_runtime_execution_runs
            (tenant_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id);

ALTER TABLE public.agent_runtime_execution_mutations
    DROP CONSTRAINT agent_runtime_execution_mutations_target_check,
    DROP CONSTRAINT agent_runtime_execution_mutation_execution_fkey,
    ADD CONSTRAINT workflow_mutation_subject_execution_fk
        FOREIGN KEY(tenant_id,execution_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id)
        REFERENCES public.agent_runtime_executions
            (tenant_id,execution_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE public.nw_runtime_workflow_node_preparations
    DROP CONSTRAINT ck_agent_runtime_workflow_node_preparation_identity,
    DROP CONSTRAINT agent_runtime_workflow_node_preparation_execution_fkey,
    ADD CONSTRAINT workflow_node_source_ck CHECK (
        btrim(execution_id)<>'' AND btrim(node_id)<>'' AND visit>0 AND call_index>0),
    ADD CONSTRAINT workflow_node_subject_execution_fk
        FOREIGN KEY(tenant_id,execution_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id)
        REFERENCES public.agent_runtime_executions
            (tenant_id,execution_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id);

ALTER TABLE public.agent_runtime_workflow_script_calls
    DROP CONSTRAINT ck_agent_runtime_workflow_script_identity,
    DROP CONSTRAINT agent_runtime_workflow_script_execution_fkey,
    ADD CONSTRAINT workflow_script_source_ck CHECK (
        btrim(execution_id)<>'' AND btrim(node_id)<>'' AND visit>0 AND call_index>0),
    ADD CONSTRAINT workflow_script_subject_execution_fk
        FOREIGN KEY(tenant_id,execution_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id)
        REFERENCES public.agent_runtime_executions
            (tenant_id,execution_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id);

ALTER TABLE public.agent_runtime_workflow_child_preparations
    DROP CONSTRAINT ck_agent_runtime_workflow_child_preparation_identity,
    DROP CONSTRAINT agent_runtime_workflow_child_preparation_parent_fkey,
    ADD CONSTRAINT workflow_child_preparation_source_ck CHECK (
        btrim(parent_execution_id)<>'' AND btrim(node_id)<>'' AND visit>0 AND call_index>0),
    ADD CONSTRAINT workflow_child_preparation_subject_uq
        UNIQUE(tenant_id,parent_execution_id,node_id,visit,call_index,target_kind,target_id,
            agent_id,session_id,subject_kind,subject_id,run_id),
    ADD CONSTRAINT workflow_child_preparation_subject_parent_fk
        FOREIGN KEY(tenant_id,parent_execution_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id)
        REFERENCES public.agent_runtime_executions
            (tenant_id,execution_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id);

ALTER TABLE public.agent_runtime_workflow_child_execution_sources
    DROP CONSTRAINT ck_agent_runtime_workflow_child_source_identity,
    DROP CONSTRAINT agent_runtime_workflow_child_source_parent_fkey,
    DROP CONSTRAINT agent_runtime_workflow_child_source_child_fkey,
    ADD CONSTRAINT workflow_child_source_identity_ck CHECK (
        btrim(child_execution_id)<>'' AND btrim(parent_execution_id)<>''
        AND btrim(node_id)<>'' AND visit>0 AND call_index>0
        AND child_ordinal BETWEEN 0 AND 31 AND btrim(branch_id)<>''
        AND child_execution_id<>parent_execution_id),
    ADD CONSTRAINT workflow_child_source_subject_parent_fk
        FOREIGN KEY(tenant_id,parent_execution_id,node_id,visit,call_index,target_kind,target_id,
            agent_id,session_id,subject_kind,subject_id,run_id)
        REFERENCES public.agent_runtime_workflow_child_preparations
            (tenant_id,parent_execution_id,node_id,visit,call_index,target_kind,target_id,
            agent_id,session_id,subject_kind,subject_id,run_id),
    ADD CONSTRAINT workflow_child_source_subject_child_fk
        FOREIGN KEY(tenant_id,child_execution_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id)
        REFERENCES public.agent_runtime_executions
            (tenant_id,execution_id,target_kind,target_id,agent_id,session_id,subject_kind,subject_id,run_id);

COMMENT ON COLUMN public.agent_runtime_executions.end_user_id IS '仅 USER 主体保存真实用户；GROUP 以非空 subject/target 约束归属';
