-- GROUP 只进入当前 typed 执行主链；保留的个人日志/存储/记忆表不放宽为群。
-- 主体键从原归属列唯一推出，现有个人写入不需要第二套归属值。
ALTER TABLE public.agent_runtime_session_owners
    ALTER COLUMN end_user_id DROP NOT NULL,
    DROP CONSTRAINT ck_agent_runtime_session_owner_identity,
    DROP CONSTRAINT agent_runtime_session_owners_target_check,
    ADD COLUMN subject_kind text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN 'GROUP' ELSE 'USER' END) STORED NOT NULL,
    ADD COLUMN subject_id text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN target_id ELSE end_user_id::text END) STORED NOT NULL,
    ADD CONSTRAINT session_owners_subject_ck CHECK (
        btrim(tenant_id)<>'' AND btrim(session_id)<>'' AND btrim(target_id)<>''
        AND ((target_kind IN ('AGENT','TEAM') AND end_user_id IS NOT NULL AND end_user_id>0)
          OR (target_kind='GROUP' AND end_user_id IS NULL))),
    ADD CONSTRAINT session_owners_subject_uq UNIQUE(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id);

ALTER TABLE public.agent_runtime_execution_runs DROP CONSTRAINT execution_runs_owner_fk;
ALTER TABLE public.agent_runtime_execution_sessions
    ALTER COLUMN end_user_id DROP NOT NULL,
    DROP CONSTRAINT execution_sessions_identity_ck,
    DROP CONSTRAINT execution_sessions_kind_ck,
    ADD COLUMN subject_kind text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN 'GROUP' ELSE 'USER' END) STORED NOT NULL,
    ADD COLUMN subject_id text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN target_id ELSE end_user_id::text END) STORED NOT NULL,
    ADD CONSTRAINT execution_sessions_subject_ck CHECK (
        btrim(tenant_id)<>'' AND btrim(session_id)<>'' AND btrim(target_id)<>''
        AND ((target_kind IN ('AGENT','TEAM') AND end_user_id IS NOT NULL AND end_user_id>0)
          OR (target_kind='GROUP' AND end_user_id IS NULL))),
    ADD CONSTRAINT execution_sessions_subject_uq UNIQUE(tenant_id,session_id,target_kind,target_id,subject_kind,subject_id),
    ADD CONSTRAINT execution_sessions_subject_root_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id)
        REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id);

ALTER TABLE public.agent_runtime_execution_runs
    ALTER COLUMN end_user_id DROP NOT NULL,
    ADD COLUMN subject_kind text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN 'GROUP' ELSE 'USER' END) STORED NOT NULL,
    ADD COLUMN subject_id text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN target_id ELSE end_user_id::text END) STORED NOT NULL,
    ADD COLUMN requester_kind text,
    ADD COLUMN requester_end_user_id bigint,
    ADD COLUMN requester_system_principal_id text,
    ADD COLUMN source_session_id text,
    ADD COLUMN source_message_id text,
    ADD COLUMN origin_ref text;
-- 旧 typed 个人 Run 的真实 owner 与已保存输入材料提供可证明的请求者和来源，不猜测群作者。
UPDATE public.agent_runtime_execution_runs
    SET requester_kind='END_USER', requester_end_user_id=end_user_id, origin_ref=input_ref;
ALTER TABLE public.agent_runtime_execution_runs
    ALTER COLUMN requester_kind SET NOT NULL,
    ALTER COLUMN origin_ref SET NOT NULL,
    ADD CONSTRAINT execution_runs_subject_ck CHECK (
        (target_kind IN ('AGENT','TEAM') AND end_user_id IS NOT NULL AND end_user_id>0)
        OR (target_kind='GROUP' AND end_user_id IS NULL)),
    ADD CONSTRAINT execution_runs_subject_owner_fk FOREIGN KEY(tenant_id,session_id,target_kind,target_id,subject_kind,subject_id)
        REFERENCES public.agent_runtime_execution_sessions(tenant_id,session_id,target_kind,target_id,subject_kind,subject_id),
    ADD CONSTRAINT execution_runs_requester_ck CHECK (
        (requester_kind='END_USER' AND requester_end_user_id IS NOT NULL AND requester_end_user_id>0 AND requester_system_principal_id IS NULL
            AND (subject_kind='GROUP' OR requester_end_user_id=end_user_id))
        OR (requester_kind='SYSTEM' AND requester_end_user_id IS NULL AND requester_system_principal_id IS NOT NULL AND btrim(requester_system_principal_id)<>'')),
    ADD CONSTRAINT execution_runs_source_ck CHECK (
        (source_session_id IS NULL AND source_message_id IS NULL)
        OR (source_session_id IS NOT NULL AND btrim(source_session_id)<>'' AND source_message_id IS NOT NULL AND btrim(source_message_id)<>''
            AND (subject_kind='GROUP' OR source_session_id=session_id))),
    ADD CONSTRAINT execution_runs_origin_ck CHECK(btrim(origin_ref)<>'');
COMMENT ON COLUMN public.agent_runtime_execution_runs.requester_kind IS '原始请求者END_USER或SYSTEM；当前操作者由独立验证事实保存';
COMMENT ON COLUMN public.agent_runtime_execution_runs.source_session_id IS '已提交原消息Session；派生群Run继承原Session引用';
COMMENT ON COLUMN public.agent_runtime_execution_runs.source_message_id IS '原已提交消息引用；不复制作者或序号';
COMMENT ON COLUMN public.agent_runtime_execution_runs.origin_ref IS '宿主可信trigger或个人输入来源引用；不创建第二来源图';
COMMENT ON CONSTRAINT execution_runs_subject_owner_fk ON public.agent_runtime_execution_runs IS '非空主体完整归属外键，群NULL用户不能跳过检查';
