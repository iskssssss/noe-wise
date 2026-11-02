-- 实时连接和普通媒体生成任务分别保存各自事实，不把实时会话恢复变成重新调用供应商。
CREATE TABLE public.nw_model_realtime_sessions (
    id bigint PRIMARY KEY,
    owner_id varchar(160) NOT NULL,
    tenant_id bigint,
    operation_id varchar(36) NOT NULL,
    command_sha256 varchar(64) NOT NULL,
    kind varchar(32) NOT NULL,
    model_config_id bigint REFERENCES public.nw_platform_models(id),
    model_revision bigint NOT NULL,
    provider_id varchar(64) NOT NULL,
    service_id varchar(128) NOT NULL,
    model_id varchar(256) NOT NULL,
    endpoint text NOT NULL,
    credential_encrypted text NOT NULL,
    provider_task_id varchar(256),
    timeout_seconds integer NOT NULL,
    sample_rate_hz integer NOT NULL,
    language varchar(64),
    input_text text,
    voice varchar(256),
    status varchar(32) NOT NULL,
    partial_text text,
    final_text text,
    reason_code varchar(128),
    finishing boolean NOT NULL DEFAULT false,
    next_audio_sequence bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deadline timestamptz NOT NULL,
    input_tokens bigint,
    output_tokens bigint,
    characters bigint,
    duration_seconds numeric,
    asset_id bigint,
    asset_source_encrypted text,
    asset_media_type varchar(128),
    asset_size_bytes bigint,
    asset_sha256 varchar(64),
    asset_name varchar(256),
    asset_available boolean NOT NULL DEFAULT false,
    CONSTRAINT model_realtime_operation_uq UNIQUE(owner_id,operation_id),
    CONSTRAINT model_realtime_identity_ck CHECK(id>0 AND btrim(owner_id)<>'' AND (tenant_id IS NULL OR tenant_id>0)
      AND model_revision>=0 AND command_sha256~'^[0-9a-f]{64}$' AND next_audio_sequence>=0
      AND timeout_seconds BETWEEN 1 AND 600 AND deadline>created_at),
    CONSTRAINT model_realtime_kind_ck CHECK(kind IN ('SPEECH_RECOGNITION','SPEECH_SYNTHESIS')),
    CONSTRAINT model_realtime_status_ck CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','NOT_SUBMITTED','UNKNOWN')),
    CONSTRAINT model_realtime_pcm_ck CHECK(kind<>'SPEECH_RECOGNITION' OR sample_rate_hz IN (8000,16000)
      AND provider_task_id IS NOT NULL AND btrim(provider_task_id)<>''),
    CONSTRAINT model_realtime_usage_ck CHECK((input_tokens IS NULL OR input_tokens>=0)
      AND (output_tokens IS NULL OR output_tokens>=0) AND (characters IS NULL OR characters>=0)
      AND (duration_seconds IS NULL OR duration_seconds>=0 AND duration_seconds NOT IN ('NaN'::numeric,'Infinity'::numeric,'-Infinity'::numeric))),
    CONSTRAINT model_realtime_asset_ck CHECK(NOT asset_available OR asset_id>0
      AND asset_media_type IS NOT NULL AND asset_size_bytes>0 AND asset_sha256~'^[0-9a-f]{64}$' AND asset_name IS NOT NULL)
);
CREATE INDEX model_realtime_owner_created_idx ON public.nw_model_realtime_sessions(owner_id,created_at DESC,id);
ALTER TABLE public.nw_model_realtime_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_model_realtime_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY model_realtime_tenant_isolation ON public.nw_model_realtime_sessions
  USING(tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''))
  WITH CHECK(tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''));
