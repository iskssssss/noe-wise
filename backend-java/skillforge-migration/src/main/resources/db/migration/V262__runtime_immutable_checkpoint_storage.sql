-- 新模型用于新建或明确授权重建的数据库；不转换历史冻结正文。
CREATE TABLE public.agent_runtime_session_checkpoints (
    tenant_id text NOT NULL CHECK (btrim(tenant_id) <> ''),
    run_id text NOT NULL CHECK (btrim(run_id) <> ''),
    content_hash text NOT NULL CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    identity_data text NOT NULL CHECK (btrim(identity_data) <> ''),
    schema_id text NOT NULL CHECK (btrim(schema_id) <> ''),
    payload_data bytea NOT NULL CHECK (octet_length(payload_data) > 0),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, run_id, content_hash),
    FOREIGN KEY (tenant_id, run_id) REFERENCES public.agent_runtime_runs (tenant_id, run_id) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE public.agent_runtime_session_checkpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_session_checkpoints FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.agent_runtime_session_checkpoints
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
CREATE FUNCTION public.agent_runtime_session_checkpoint_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'session_checkpoint_immutable'; END;
$$;
CREATE TRIGGER session_checkpoint_immutable BEFORE UPDATE ON public.agent_runtime_session_checkpoints
    FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_session_checkpoint_immutable();
COMMENT ON TABLE public.agent_runtime_session_checkpoints IS '同一 Run 内按内容摘要冻结一次的恢复 checkpoint 正文；提交日志与回执保存引用';
COMMENT ON COLUMN public.agent_runtime_session_checkpoints.tenant_id IS 'checkpoint 所属租户，受强制 RLS 约束';
COMMENT ON COLUMN public.agent_runtime_session_checkpoints.run_id IS 'checkpoint 所属原 Run';
COMMENT ON COLUMN public.agent_runtime_session_checkpoints.content_hash IS 'schema 与规范 JSON 的域分离摘要；读取同时核验正文和完整身份';
COMMENT ON COLUMN public.agent_runtime_session_checkpoints.identity_data IS '完整 RunIdentity 规范编码，防止同 Run 标识下来源替换';
COMMENT ON COLUMN public.agent_runtime_session_checkpoints.schema_id IS '原 checkpoint schema';
COMMENT ON COLUMN public.agent_runtime_session_checkpoints.payload_data IS '原 TypedPayload 规范二进制，不使用 Base64 包装';
COMMENT ON COLUMN public.agent_runtime_session_checkpoints.recorded_at IS '首次冻结的数据库时间';
COMMENT ON INDEX public.agent_runtime_session_checkpoints_pkey IS '同租户 Run 内容唯一，更新不可覆盖';
COMMENT ON FUNCTION public.agent_runtime_session_checkpoint_immutable() IS '禁止覆盖已冻结恢复正文';

-- 回执只保存小型结果值；数据库仍要求同一次 publication 变更完整提交原结果。
ALTER TABLE public.agent_runtime_run_state_publications ADD COLUMN mutation_result_data text;
COMMENT ON COLUMN public.agent_runtime_run_state_publications.mutation_result_data IS
    '最近 ACK/RETRY 的精确规范结果，与 mutation journal 原结果逐字节闭合；领取时清空';
CREATE OR REPLACE FUNCTION public.assert_agent_runtime_run_publication_mutation_journaled()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'CLAIMED' AND NEW.status IN ('PENDING', 'ACKNOWLEDGED')
       AND NOT EXISTS (
        SELECT 1 FROM public.agent_runtime_run_publication_mutations operation
         WHERE operation.tenant_id = NEW.tenant_id
           AND operation.source_id = NEW.source_id
           AND operation.event_id = NEW.event_id
           AND operation.output_revision = NEW.publication_revision
           AND operation.output_status = NEW.status
           AND operation.output_data = NEW.mutation_result_data) THEN
        RAISE EXCEPTION 'run publication mutation must commit its journal';
    END IF;
    RETURN NULL;
END;
$$;
CREATE FUNCTION public.guard_agent_runtime_run_publication_body()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.publication_data IS DISTINCT FROM OLD.publication_data THEN
        RAISE EXCEPTION 'run publication immutable body changed';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_runtime_run_publication_body_guard
    BEFORE UPDATE ON public.agent_runtime_run_state_publications
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_runtime_run_publication_body();
COMMENT ON FUNCTION public.guard_agent_runtime_run_publication_body() IS
    '禁止领取、ACK 或重试替换不可变事件正文；结果闭包由延迟 journal 触发器核验';
