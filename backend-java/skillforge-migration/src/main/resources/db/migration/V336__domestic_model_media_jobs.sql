-- core-model拥有的媒体调用与结果，不重写模型版本或历史调用。
CREATE TABLE public.nw_model_media_jobs (
    id bigint NOT NULL,
    owner_id varchar(160) NOT NULL,
    tenant_id bigint,
    operation_id varchar(36) NOT NULL,
    command_sha256 varchar(64) NOT NULL,
    model_config_id bigint,
    model_revision bigint NOT NULL,
    provider_id varchar(64) NOT NULL,
    service_id varchar(128),
    profile_version varchar(128),
    model_id varchar(256) NOT NULL,
    kind varchar(32) NOT NULL,
    endpoint text NOT NULL,
    credential_encrypted text NOT NULL,
    timeout_seconds integer NOT NULL,
    status varchar(32) NOT NULL,
    provider_request_id varchar(256),
    provider_task_id varchar(256),
    reason_code varchar(128),
    waiting_stopped boolean NOT NULL DEFAULT false,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    input_tokens bigint,
    output_tokens bigint,
    image_count bigint,
    video_count bigint,
    characters bigint,
    duration_seconds numeric,
    input_text text,
    negative_prompt text,
    size varchar(64),
    resolution varchar(64),
    ratio varchar(64),
    voice varchar(256),
    language varchar(64),
    output_count integer,
    video_duration_seconds integer,
    speaker_count integer,
    seed bigint,
    prompt_extend boolean,
    watermark boolean,
    audio_enabled boolean,
    diarization boolean,
    enable_itn boolean,
    CONSTRAINT model_media_jobs_pk PRIMARY KEY (id),
    CONSTRAINT model_media_jobs_operation_uq UNIQUE (owner_id,operation_id),
    CONSTRAINT model_media_jobs_model_fk FOREIGN KEY(model_config_id) REFERENCES public.nw_platform_models(id),
    CONSTRAINT model_media_jobs_identity_ck CHECK (id>0 AND btrim(owner_id)<>'' AND (tenant_id IS NULL OR tenant_id>0)
        AND command_sha256~'^[0-9a-f]{64}$' AND model_revision>=0 AND lock_version>=0 AND timeout_seconds BETWEEN 1 AND 600),
    CONSTRAINT model_media_jobs_kind_ck CHECK(kind IN ('IMAGE_GENERATION','VIDEO_GENERATION','SPEECH_RECOGNITION','SPEECH_SYNTHESIS')),
    CONSTRAINT model_media_jobs_status_ck CHECK(status IN ('NOT_SUBMITTED','PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
    CONSTRAINT model_media_jobs_usage_ck CHECK ((input_tokens IS NULL OR input_tokens>=0) AND (output_tokens IS NULL OR output_tokens>=0)
        AND (image_count IS NULL OR image_count>=0) AND (video_count IS NULL OR video_count>=0)
        AND (characters IS NULL OR characters>=0) AND (duration_seconds IS NULL OR duration_seconds>=0
            AND duration_seconds NOT IN ('NaN'::numeric,'Infinity'::numeric,'-Infinity'::numeric)))
);
CREATE INDEX model_media_jobs_owner_time_idx ON public.nw_model_media_jobs(owner_id,created_at DESC,id);

CREATE TABLE public.nw_model_media_assets (
    id bigint NOT NULL,
    job_id bigint NOT NULL,
    kind varchar(32) NOT NULL,
    source_encrypted text,
    media_type varchar(128),
    size_bytes bigint,
    sha256 varchar(64),
    name varchar(256),
    available boolean NOT NULL DEFAULT false,
    CONSTRAINT model_media_assets_pk PRIMARY KEY(id),
    CONSTRAINT model_media_assets_job_fk FOREIGN KEY(job_id) REFERENCES public.nw_model_media_jobs(id),
    CONSTRAINT model_media_assets_kind_ck CHECK(kind IN ('IMAGE','VIDEO','AUDIO','TRANSCRIPT')),
    CONSTRAINT model_media_assets_body_ck CHECK (id>0 AND (NOT available OR media_type IS NOT NULL
        AND size_bytes IS NOT NULL AND size_bytes>0 AND sha256 IS NOT NULL AND sha256~'^[0-9a-f]{64}$' AND name IS NOT NULL))
);
CREATE INDEX model_media_assets_job_idx ON public.nw_model_media_assets(job_id,id);

CREATE TABLE public.nw_model_media_input_sources (
    job_id bigint NOT NULL,
    ordinal integer NOT NULL,
    kind varchar(32) NOT NULL,
    source_encrypted text NOT NULL,
    CONSTRAINT model_media_input_sources_pk PRIMARY KEY(job_id,ordinal),
    CONSTRAINT model_media_input_sources_job_fk FOREIGN KEY(job_id) REFERENCES public.nw_model_media_jobs(id),
    CONSTRAINT model_media_input_sources_kind_ck CHECK(ordinal>=0 AND kind IN
        ('REFERENCE_IMAGE','FIRST_FRAME','LAST_FRAME','REFERENCE_VIDEO','REFERENCE_AUDIO','FILE','LINK','AUDIO_FILE'))
);
CREATE TABLE public.nw_model_media_input_languages (
    job_id bigint NOT NULL,
    ordinal integer NOT NULL,
    language varchar(64) NOT NULL,
    CONSTRAINT model_media_input_languages_pk PRIMARY KEY(job_id,ordinal),
    CONSTRAINT model_media_input_languages_job_fk FOREIGN KEY(job_id) REFERENCES public.nw_model_media_jobs(id),
    CONSTRAINT model_media_input_languages_value_ck CHECK(ordinal>=0 AND btrim(language)<>'')
);
CREATE TABLE public.nw_model_media_input_channels (
    job_id bigint NOT NULL,
    ordinal integer NOT NULL,
    channel integer NOT NULL,
    CONSTRAINT model_media_input_channels_pk PRIMARY KEY(job_id,ordinal),
    CONSTRAINT model_media_input_channels_job_fk FOREIGN KEY(job_id) REFERENCES public.nw_model_media_jobs(id),
    CONSTRAINT model_media_input_channels_value_ck CHECK(ordinal>=0 AND channel>=0)
);

ALTER TABLE public.nw_model_media_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_model_media_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY model_media_jobs_tenant_isolation ON public.nw_model_media_jobs
    USING(tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''))
    WITH CHECK(tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''));
ALTER TABLE public.nw_model_media_assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_model_media_assets FORCE ROW LEVEL SECURITY;
CREATE POLICY model_media_assets_tenant_isolation ON public.nw_model_media_assets
    USING(EXISTS(SELECT 1 FROM public.nw_model_media_jobs job WHERE job.id=job_id))
    WITH CHECK(EXISTS(SELECT 1 FROM public.nw_model_media_jobs job WHERE job.id=job_id));
ALTER TABLE public.nw_model_media_input_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_model_media_input_sources FORCE ROW LEVEL SECURITY;
CREATE POLICY model_media_input_sources_tenant_isolation ON public.nw_model_media_input_sources
    USING(EXISTS(SELECT 1 FROM public.nw_model_media_jobs job WHERE job.id=job_id))
    WITH CHECK(EXISTS(SELECT 1 FROM public.nw_model_media_jobs job WHERE job.id=job_id));
ALTER TABLE public.nw_model_media_input_languages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_model_media_input_languages FORCE ROW LEVEL SECURITY;
CREATE POLICY model_media_input_languages_tenant_isolation ON public.nw_model_media_input_languages
    USING(EXISTS(SELECT 1 FROM public.nw_model_media_jobs job WHERE job.id=job_id))
    WITH CHECK(EXISTS(SELECT 1 FROM public.nw_model_media_jobs job WHERE job.id=job_id));
ALTER TABLE public.nw_model_media_input_channels ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_model_media_input_channels FORCE ROW LEVEL SECURITY;
CREATE POLICY model_media_input_channels_tenant_isolation ON public.nw_model_media_input_channels
    USING(EXISTS(SELECT 1 FROM public.nw_model_media_jobs job WHERE job.id=job_id))
    WITH CHECK(EXISTS(SELECT 1 FROM public.nw_model_media_jobs job WHERE job.id=job_id));
GRANT SELECT,INSERT,UPDATE ON public.nw_model_media_jobs,public.nw_model_media_assets TO skillforge;
GRANT SELECT,INSERT ON public.nw_model_media_input_sources,public.nw_model_media_input_languages,public.nw_model_media_input_channels TO skillforge;

COMMENT ON TABLE public.nw_model_media_jobs IS 'core-model媒体调用原身份和冻结连接；创建未知不重发，查询只用原供应商任务；随原任务和结果保留，未提供自动删除';
COMMENT ON COLUMN public.nw_model_media_jobs.id IS '宿主任务雪花ID，公开使用十进制字符串';
COMMENT ON COLUMN public.nw_model_media_jobs.owner_id IS '可信宿主构造的平台actor或机构actor与Session摘要归属，公开请求不能自填，不以任务ID授予跨Session权限';
COMMENT ON COLUMN public.nw_model_media_jobs.tenant_id IS '真实机构；平台显式探测为NULL且只由平台特权连接读取';
COMMENT ON COLUMN public.nw_model_media_jobs.operation_id IS '调用方首次生成并持续保留的UUID，同归属同身份不能改输入';
COMMENT ON COLUMN public.nw_model_media_jobs.command_sha256 IS '首次类型化创建命令的规范摘要，核对原操作不读取后来修改的模型草稿';
COMMENT ON COLUMN public.nw_model_media_jobs.model_config_id IS '来源模型资源，未保存新建配置为NULL';
COMMENT ON COLUMN public.nw_model_media_jobs.model_revision IS '本次冻结草稿修订；未保存输入为0';
COMMENT ON COLUMN public.nw_model_media_jobs.provider_id IS '冻结供应商品牌';
COMMENT ON COLUMN public.nw_model_media_jobs.service_id IS '冻结境内接入服务';
COMMENT ON COLUMN public.nw_model_media_jobs.profile_version IS '冻结接入契约版本';
COMMENT ON COLUMN public.nw_model_media_jobs.model_id IS '实际发送的供应商模型标识，不随目录切换';
COMMENT ON COLUMN public.nw_model_media_jobs.kind IS '图片生成、视频生成、语音识别或语音合成';
COMMENT ON COLUMN public.nw_model_media_jobs.endpoint IS '冻结境内服务地址，仅供可信宿主查询原任务，不公开';
COMMENT ON COLUMN public.nw_model_media_jobs.credential_encrypted IS '既有CryptoService封装的冻结凭据密文，不存明文也不在查询时换用最新凭据';
COMMENT ON COLUMN public.nw_model_media_jobs.timeout_seconds IS '单次原创建或查询网络期限，单位秒';
COMMENT ON COLUMN public.nw_model_media_jobs.status IS '供应商事实；登记后到确定回执前为UNKNOWN，不能据此重发';
COMMENT ON COLUMN public.nw_model_media_jobs.provider_request_id IS '原供应商请求回执身份，缺失保持NULL供原操作核对';
COMMENT ON COLUMN public.nw_model_media_jobs.provider_task_id IS '供应商原异步任务ID，NULL时不能伪造查询或重新创建';
COMMENT ON COLUMN public.nw_model_media_jobs.reason_code IS '脱敏稳定诊断分类，不保存供应商敏感正文';
COMMENT ON COLUMN public.nw_model_media_jobs.waiting_stopped IS '仅停止本地等待，不表示供应商任务已取消';
COMMENT ON COLUMN public.nw_model_media_jobs.lock_version IS '供应商查询结果的比较更新版本，防止迟到结果覆盖已采纳新结果';
COMMENT ON COLUMN public.nw_model_media_jobs.created_at IS '原操作持久登记时间';
COMMENT ON COLUMN public.nw_model_media_jobs.updated_at IS '最近结果或等待状态保存时间';
COMMENT ON COLUMN public.nw_model_media_jobs.input_tokens IS '供应商报告输入Token，NULL表示未知';
COMMENT ON COLUMN public.nw_model_media_jobs.output_tokens IS '供应商报告输出Token，NULL表示未知';
COMMENT ON COLUMN public.nw_model_media_jobs.image_count IS '供应商报告图片数量，NULL表示未知';
COMMENT ON COLUMN public.nw_model_media_jobs.video_count IS '供应商报告视频数量，NULL表示未知';
COMMENT ON COLUMN public.nw_model_media_jobs.characters IS '供应商报告字符数，NULL表示未知';
COMMENT ON COLUMN public.nw_model_media_jobs.duration_seconds IS '供应商报告时长，单位秒，保留原始十进制精度；NULL表示未知';
COMMENT ON COLUMN public.nw_model_media_jobs.input_text IS '冻结生成提示词或待合成文字，不进入普通日志';
COMMENT ON COLUMN public.nw_model_media_jobs.negative_prompt IS '冻结图片负向提示词';
COMMENT ON COLUMN public.nw_model_media_jobs.size IS '供应商图片输出尺寸参数，NULL表示未指定';
COMMENT ON COLUMN public.nw_model_media_jobs.resolution IS '供应商视频分辨率参数';
COMMENT ON COLUMN public.nw_model_media_jobs.ratio IS '供应商视频宽高比参数';
COMMENT ON COLUMN public.nw_model_media_jobs.voice IS '显式语音音色标识，不按名称推测';
COMMENT ON COLUMN public.nw_model_media_jobs.language IS '语音合成语言参数';
COMMENT ON COLUMN public.nw_model_media_jobs.output_count IS '请求图片数量，NULL表示未指定';
COMMENT ON COLUMN public.nw_model_media_jobs.video_duration_seconds IS '请求视频秒数，-1仅由供应商协议解释';
COMMENT ON COLUMN public.nw_model_media_jobs.speaker_count IS '请求识别说话人数';
COMMENT ON COLUMN public.nw_model_media_jobs.seed IS '用户明确选择的生成随机种子';
COMMENT ON COLUMN public.nw_model_media_jobs.prompt_extend IS '是否启用供应商提示词扩展，NULL表示未指定';
COMMENT ON COLUMN public.nw_model_media_jobs.watermark IS '请求水印选项，NULL表示未指定';
COMMENT ON COLUMN public.nw_model_media_jobs.audio_enabled IS '视频生成音频选项，NULL表示未指定';
COMMENT ON COLUMN public.nw_model_media_jobs.diarization IS '语音识别说话人分离选项，NULL表示未指定';
COMMENT ON TABLE public.nw_model_media_assets IS '原媒体结果资产，供应商签名URL加密保存；正文收集完成后才能认证下载';
COMMENT ON COLUMN public.nw_model_media_assets.id IS '受控资产雪花ID';
COMMENT ON COLUMN public.nw_model_media_assets.job_id IS '拥有该结果的原媒体任务';
COMMENT ON COLUMN public.nw_model_media_assets.kind IS '实际结果类型';
COMMENT ON COLUMN public.nw_model_media_assets.source_encrypted IS '供应商临时签名URL密文，不作为公开读取票据；原生字节已直接持久化时为NULL';
COMMENT ON COLUMN public.nw_model_media_assets.media_type IS '实际正文内容类型';
COMMENT ON COLUMN public.nw_model_media_assets.size_bytes IS '已收集正文长度，字节，未完成为NULL';
COMMENT ON COLUMN public.nw_model_media_assets.sha256 IS '已收集正文SHA-256，未完成为NULL';
COMMENT ON COLUMN public.nw_model_media_assets.name IS '宿主生成的安全下载名';
COMMENT ON COLUMN public.nw_model_media_assets.available IS '不可变正文已持久化且可按任务归属读取';
COMMENT ON TABLE public.nw_model_media_input_sources IS '按原顺序保存输入媒体来源；URL或内联数据加密保存，不接受宿主路径';
COMMENT ON COLUMN public.nw_model_media_input_sources.job_id IS '原媒体任务';
COMMENT ON COLUMN public.nw_model_media_input_sources.ordinal IS '从0起的原输入顺序';
COMMENT ON COLUMN public.nw_model_media_input_sources.kind IS '明确的首尾帧、参考媒体或语音文件类型';
COMMENT ON COLUMN public.nw_model_media_input_sources.source_encrypted IS '原输入媒体位置或内联数据密文';
COMMENT ON TABLE public.nw_model_media_input_languages IS '原语音识别语言候选列表，保持用户顺序';
COMMENT ON COLUMN public.nw_model_media_input_languages.job_id IS '原媒体任务';
COMMENT ON COLUMN public.nw_model_media_input_languages.ordinal IS '从0起的语言候选顺序';
COMMENT ON COLUMN public.nw_model_media_input_languages.language IS '原语言提示值';
COMMENT ON TABLE public.nw_model_media_input_channels IS '原语音识别声道列表';
COMMENT ON COLUMN public.nw_model_media_input_channels.job_id IS '原媒体任务';
COMMENT ON COLUMN public.nw_model_media_input_channels.ordinal IS '从0起的声道顺序';
COMMENT ON COLUMN public.nw_model_media_input_channels.channel IS '请求识别的非负声道编号';
COMMENT ON CONSTRAINT model_media_jobs_pk ON public.nw_model_media_jobs IS '媒体任务稳定身份';
COMMENT ON CONSTRAINT model_media_jobs_operation_uq ON public.nw_model_media_jobs IS '同归属同操作只登记一次，重复不能新派发';
COMMENT ON CONSTRAINT model_media_jobs_model_fk ON public.nw_model_media_jobs IS '来源模型可空，临时模型不隐式创建草稿';
COMMENT ON CONSTRAINT model_media_jobs_identity_ck ON public.nw_model_media_jobs IS '原身份、摘要、版本和调用期限基本不变量';
COMMENT ON CONSTRAINT model_media_jobs_kind_ck ON public.nw_model_media_jobs IS '已实现媒体服务的封闭种类';
COMMENT ON CONSTRAINT model_media_jobs_status_ck ON public.nw_model_media_jobs IS '供应商任务状态，不将停止等待等同取消';
COMMENT ON CONSTRAINT model_media_jobs_usage_ck ON public.nw_model_media_jobs IS '用量缺报为NULL，非空必须非负有限';
COMMENT ON CONSTRAINT model_media_assets_pk ON public.nw_model_media_assets IS '受控结果资产身份';
COMMENT ON CONSTRAINT model_media_assets_job_fk ON public.nw_model_media_assets IS '资产归属原任务，不能独立授予读取权';
COMMENT ON CONSTRAINT model_media_assets_kind_ck ON public.nw_model_media_assets IS '结果资产封闭类型';
COMMENT ON CONSTRAINT model_media_assets_body_ck ON public.nw_model_media_assets IS '可读取正文必须已记录类型、长度、摘要与名称';
COMMENT ON CONSTRAINT model_media_input_sources_pk ON public.nw_model_media_input_sources IS '同任务媒体出现位置唯一';
COMMENT ON CONSTRAINT model_media_input_sources_job_fk ON public.nw_model_media_input_sources IS '输入媒体随原任务保留';
COMMENT ON CONSTRAINT model_media_input_sources_kind_ck ON public.nw_model_media_input_sources IS '媒体角色明确且保序';
COMMENT ON CONSTRAINT model_media_input_languages_pk ON public.nw_model_media_input_languages IS '同任务语言出现位置唯一';
COMMENT ON CONSTRAINT model_media_input_languages_job_fk ON public.nw_model_media_input_languages IS '语言列表归属原任务';
COMMENT ON CONSTRAINT model_media_input_languages_value_ck ON public.nw_model_media_input_languages IS '语言值必须存在且保序';
COMMENT ON CONSTRAINT model_media_input_channels_pk ON public.nw_model_media_input_channels IS '同任务声道出现位置唯一';
COMMENT ON CONSTRAINT model_media_input_channels_job_fk ON public.nw_model_media_input_channels IS '声道列表归属原任务';
COMMENT ON CONSTRAINT model_media_input_channels_value_ck ON public.nw_model_media_input_channels IS '声道为非负整数且保序';
COMMENT ON INDEX public.model_media_jobs_owner_time_idx IS '按真实归属查询任务';
COMMENT ON INDEX public.model_media_assets_job_idx IS '按任务读取结果资产';
COMMENT ON POLICY model_media_jobs_tenant_isolation ON public.nw_model_media_jobs IS '机构连接仅能访问绑定机构，平台探测仅平台特权连接可读';
COMMENT ON POLICY model_media_assets_tenant_isolation ON public.nw_model_media_assets IS '资产继承任务机构隔离';
COMMENT ON POLICY model_media_input_sources_tenant_isolation ON public.nw_model_media_input_sources IS '输入来源继承任务机构隔离';
COMMENT ON POLICY model_media_input_languages_tenant_isolation ON public.nw_model_media_input_languages IS '语言提示继承任务机构隔离';
COMMENT ON POLICY model_media_input_channels_tenant_isolation ON public.nw_model_media_input_channels IS '声道继承任务机构隔离';
