-- V75: ControlOperation 账本状态迁移的租户更新策略。
-- 迁移仍由应用层以 tenant + integration + operationId + expected status 执行 CAS。
CREATE POLICY skillforge_control_operations_tenant_update ON public.nw_control_operations
    FOR UPDATE
    USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)))
    WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));
