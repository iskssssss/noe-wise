-- 节点准备不保存永久租约；每次 Action 领取单独绑定当前 Execution fence。
CREATE TABLE public.nw_runtime_workflow_node_action_grants (
    tenant_id text NOT NULL,
    action_id text NOT NULL,
    action_fence bigint NOT NULL CHECK (action_fence > 0),
    grant_data text NOT NULL CHECK (length(grant_data) > 0),
    PRIMARY KEY (tenant_id, action_id, action_fence),
    FOREIGN KEY (tenant_id, action_id) REFERENCES public.agent_runtime_actions(tenant_id, action_id)
);
ALTER TABLE public.nw_runtime_workflow_node_action_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_runtime_workflow_node_action_grants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.nw_runtime_workflow_node_action_grants
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
CREATE TRIGGER workflow_node_action_grant_immutable BEFORE UPDATE OR DELETE
    ON public.nw_runtime_workflow_node_action_grants FOR EACH ROW
    EXECUTE FUNCTION public.agent_runtime_workflow_node_preparation_immutable();
COMMENT ON TABLE public.nw_runtime_workflow_node_action_grants IS 'Workflow 节点 Action fence 与当次 Execution 写租约的不可变绑定';
COMMENT ON COLUMN public.nw_runtime_workflow_node_action_grants.tenant_id IS '所属租户';
COMMENT ON COLUMN public.nw_runtime_workflow_node_action_grants.action_id IS '节点外调的稳定 Action 身份';
COMMENT ON COLUMN public.nw_runtime_workflow_node_action_grants.action_fence IS 'Action 本次领取的 fencing token';
COMMENT ON COLUMN public.nw_runtime_workflow_node_action_grants.grant_data IS 'Execution 租约、核验 revision 和原准备引用的规范私有编码';
