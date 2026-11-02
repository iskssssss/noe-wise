-- 一次性接入：仅在已备份并清空 Runtime 历史的明确数据库上补齐 V118 当前结构。
-- psql -X --set=ON_ERROR_STOP=1 --set=target_database=明确数据库名 --file=此文件
-- 连接参数由调用方提供；本脚本不清理数据、不修改 Flyway 历史，也不启动服务。
\set ON_ERROR_STOP on

BEGIN;
SELECT set_config('skillforge.runtime_reset_target_database', :'target_database', true);

DO $$
DECLARE
    expected_database text := current_setting('skillforge.runtime_reset_target_database');
    highest_version integer;
BEGIN
    IF btrim(expected_database) = '' OR current_database() <> expected_database THEN
        RAISE EXCEPTION 'explicit database target mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolbypassrls)) THEN
        RAISE EXCEPTION 'unfiltered all-tenant empty checks require a superuser or BYPASSRLS maintenance role';
    END IF;
    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'Flyway history is required';
    END IF;
    IF EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE NOT success) THEN
        RAISE EXCEPTION 'failed Flyway migration must be resolved before schema preparation';
    END IF;
    SELECT max(version::integer) INTO highest_version
      FROM public.flyway_schema_history
     WHERE success AND version ~ '^[0-9]+$';
    IF highest_version IS DISTINCT FROM 261 THEN
        RAISE EXCEPTION 'this preparation is restricted to schema version 261';
    END IF;
    IF to_regclass('public.agent_runtime_session_checkpoints') IS NOT NULL THEN
        RAISE EXCEPTION 'V262 checkpoint storage already exists; use normal Flyway validation';
    END IF;
    IF EXISTS (SELECT 1 FROM public.agent_runtime_session_preparations LIMIT 1)
       OR EXISTS (SELECT 1 FROM public.agent_runtime_runs LIMIT 1)
       OR EXISTS (SELECT 1 FROM public.agent_runtime_session_commit_journals LIMIT 1)
       OR EXISTS (SELECT 1 FROM public.agent_runtime_run_mutations LIMIT 1)
       OR EXISTS (SELECT 1 FROM public.agent_runtime_run_state_publications LIMIT 1)
       OR EXISTS (SELECT 1 FROM public.agent_runtime_run_publication_mutations LIMIT 1)
       OR EXISTS (SELECT 1 FROM public.agent_runtime_input_ledger LIMIT 1)
       OR EXISTS (SELECT 1 FROM public.agent_runtime_session_admission_prepares LIMIT 1)
       OR EXISTS (SELECT 1 FROM public.agent_runtime_session_cancellation_requests LIMIT 1)
       OR EXISTS (SELECT 1 FROM public.agent_runtime_session_terminal_failures LIMIT 1) THEN
        RAISE EXCEPTION 'Runtime history reset must complete before schema preparation';
    END IF;
END;
$$;

-- 不使用历史数据默认值或回填：空表直接设置未来写入的强制二进制正文契约。
ALTER TABLE public.agent_runtime_session_preparations
    ADD COLUMN IF NOT EXISTS payload_data bytea NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'agent_runtime_session_preparations'
           AND column_name = 'payload_data' AND data_type = 'bytea' AND is_nullable = 'NO'
           AND column_default IS NULL
    ) THEN
        RAISE EXCEPTION 'preparation payload_data must be bytea NOT NULL without a fabricated default';
    END IF;
END;
$$;

ALTER TABLE public.agent_runtime_session_preparations
    ADD CONSTRAINT ck_session_preparation_payload_binary
    CHECK (octet_length(payload_data) > 0);
COMMENT ON COLUMN public.agent_runtime_session_preparations.payload_data IS
    '独立保存一次的模型准备正文规范二进制；checkpoint 只保存准备 key';
COMMENT ON COLUMN public.agent_runtime_session_preparations.prepared_data IS
    '冻结来源与计量元数据，sourceRun checkpoint 为不可变引用，正文保存于 payload_data';

COMMIT;

-- 下一步：核验 validate 的唯一 checksum 差异为 V118，再由官方 Flyway repair / migrate
-- 记录 V118 当前 checksum、执行正式 V262 并登记真实成功版本；禁止手写 schema_history。
