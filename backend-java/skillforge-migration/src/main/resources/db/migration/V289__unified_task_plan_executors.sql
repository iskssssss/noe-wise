-- 主步骤与成员共享准入事实；主步骤在原 Run 内按 admission/task 独立区分。
ALTER TABLE public.agent_runtime_dag_admissions
    ADD COLUMN executor_type varchar(10) NOT NULL DEFAULT 'MEMBER'
        CHECK (executor_type IN ('PRIMARY', 'MEMBER'));
ALTER TABLE public.agent_runtime_dag_admissions ALTER COLUMN executor_type DROP DEFAULT;
COMMENT ON COLUMN public.agent_runtime_dag_admissions.executor_type IS 'PRIMARY 复用控制 Run；MEMBER 独占成员子 Run';

-- 容量许可只属于 MEMBER；主步骤不向该表写入许可。
ALTER TABLE public.agent_runtime_dag_permits
    DROP CONSTRAINT agent_runtime_dag_permits_tenant_id_run_id_fkey;
ALTER TABLE public.agent_runtime_dag_admissions
    DROP CONSTRAINT agent_runtime_dag_admissions_tenant_id_run_id_key;
ALTER TABLE public.agent_runtime_dag_admissions ADD CONSTRAINT uq_dag_admission_run
    UNIQUE (tenant_id, admission_id, run_id);
CREATE UNIQUE INDEX agent_runtime_dag_member_run
    ON public.agent_runtime_dag_admissions(tenant_id, run_id) WHERE executor_type = 'MEMBER';

-- 成员许可仍通过稳定 admission 外键验证，避免失去原参照完整性。
ALTER TABLE public.agent_runtime_dag_permits ADD COLUMN admission_id varchar(240);
UPDATE public.agent_runtime_dag_permits p SET admission_id = a.admission_id
    FROM public.agent_runtime_dag_admissions a
    WHERE a.tenant_id = p.tenant_id AND a.run_id = p.run_id AND a.executor_type = 'MEMBER';
ALTER TABLE public.agent_runtime_dag_permits ALTER COLUMN admission_id SET NOT NULL;
ALTER TABLE public.agent_runtime_dag_permits ADD CONSTRAINT fk_dag_permit_admission
    FOREIGN KEY (tenant_id, admission_id, run_id) REFERENCES public.agent_runtime_dag_admissions(tenant_id, admission_id, run_id);

-- 稳定 Action 在派发前冻结到主步骤，不复制 Action 执行账本。
CREATE TABLE public.agent_runtime_dag_step_actions (
    tenant_id varchar(160) NOT NULL,
    action_id varchar(160) NOT NULL,
    admission_id varchar(240) NOT NULL,
    run_id varchar(240) NOT NULL,
    task_id varchar(240) NOT NULL,
    PRIMARY KEY (tenant_id, action_id),
    FOREIGN KEY (tenant_id, admission_id, run_id)
        REFERENCES public.agent_runtime_dag_admissions(tenant_id, admission_id, run_id)
);
COMMENT ON TABLE public.agent_runtime_dag_step_actions IS '原 Run 中主步骤与稳定 Action 身份的不可变归属';
ALTER TABLE public.agent_runtime_dag_step_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_dag_step_actions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_dag_step_actions
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
