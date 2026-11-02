-- 群归属沿现有认证/冻结绑定/能力快照进入唯一 typed Runtime；NULL EndUser 只属于明确 GROUP 分支。
ALTER TABLE public.nw_runtime_authentication_facts
    ALTER COLUMN end_user_id DROP NOT NULL,
    DROP CONSTRAINT agent_runtime_authentication_target_check,
    ADD CONSTRAINT runtime_authentication_subject_ck CHECK (
        btrim(target_id)<>'' AND ((target_kind IN ('AGENT','TEAM') AND end_user_id IS NOT NULL AND end_user_id>0)
          OR (target_kind='GROUP' AND end_user_id IS NULL)));

ALTER TABLE public.nw_runtime_agent_run_bindings
    ALTER COLUMN end_user_id DROP NOT NULL,
    DROP CONSTRAINT nw_runtime_agent_run_bindings_target_kind_check,
    ADD COLUMN requester_end_user_id bigint,
    ADD CONSTRAINT agent_run_binding_subject_ck CHECK (
        (target_kind IN ('AGENT','TEAM') AND end_user_id IS NOT NULL AND end_user_id>0)
        OR (target_kind='GROUP' AND end_user_id IS NULL)),
    ADD CONSTRAINT agent_run_binding_requester_ck CHECK (requester_end_user_id IS NULL OR requester_end_user_id>0);
COMMENT ON COLUMN public.nw_runtime_agent_run_bindings.requester_end_user_id IS
    '原Run真实EndUser请求者；群每次派发与控制按此核原群成员，System请求者为空且不得冒充EndUser';

ALTER TABLE public.nw_runtime_session_capabilities
    ALTER COLUMN end_user_id DROP NOT NULL,
    DROP CONSTRAINT nw_runtime_session_capabilities_target_check,
    DROP CONSTRAINT nw_runtime_session_capabilities_target_owner_fkey,
    ADD COLUMN subject_kind text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN 'GROUP' ELSE 'USER' END) STORED NOT NULL,
    ADD COLUMN subject_id text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN target_id ELSE end_user_id::text END) STORED NOT NULL,
    ADD CONSTRAINT session_capabilities_subject_ck CHECK (
        (target_kind IN ('AGENT','TEAM') AND end_user_id IS NOT NULL AND end_user_id>0)
        OR (target_kind='GROUP' AND end_user_id IS NULL)),
    ADD CONSTRAINT session_capabilities_subject_owner_fk
        FOREIGN KEY(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id)
        REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id);
COMMENT ON CONSTRAINT session_capabilities_subject_owner_fk ON public.nw_runtime_session_capabilities IS
    '非空主体键维持完整会话归属，群NULL用户不会绕过owner外键';
