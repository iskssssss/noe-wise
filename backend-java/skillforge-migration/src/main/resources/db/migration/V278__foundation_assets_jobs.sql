-- 公共后台文件与管理任务独立于 Runtime、Session 附件和成果；归属为空只代表平台私有作用域。
CREATE TABLE public.nw_foundation_files (
 id varchar(36) PRIMARY KEY,
 tenant_id bigint REFERENCES public.nw_tenants(id),
 scope_key text GENERATED ALWAYS AS (COALESCE(tenant_id::text,'platform')) STORED NOT NULL,
 created_by varchar(128) NOT NULL,
 name varchar(255) NOT NULL,
 mime_type varchar(64) NOT NULL,
 size_bytes bigint NOT NULL CHECK(size_bytes>0 AND size_bytes<=104857600),
 sha256 char(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 blob_key varchar(36) NOT NULL UNIQUE,
 state varchar(16) NOT NULL CHECK(state IN('ready','deleting','deleted')),
 source_kind varchar(24) NOT NULL CHECK(source_kind IN('upload','job_result')),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(scope_key,id)
);
COMMENT ON TABLE public.nw_foundation_files IS '后台公共文件元数据，物理正文由公共文件领域持有；不迁移 Session 附件/Artifact 权威';
COMMENT ON COLUMN public.nw_foundation_files.id IS '服务端生成 UUID 身份';
COMMENT ON COLUMN public.nw_foundation_files.tenant_id IS '可信机构归属，NULL 为平台私有文件';
COMMENT ON COLUMN public.nw_foundation_files.scope_key IS '数据库生成的同归属复合关联键，平台固定 platform';
COMMENT ON COLUMN public.nw_foundation_files.created_by IS '可信受理主体身份，不由请求写入';
COMMENT ON COLUMN public.nw_foundation_files.name IS '不包含路径或控制字符的下载文件名';
COMMENT ON COLUMN public.nw_foundation_files.mime_type IS '服务端根据正文识别的白名单 MIME';
COMMENT ON COLUMN public.nw_foundation_files.size_bytes IS '精确原始字节数，ready/deleting 均占用配额';
COMMENT ON COLUMN public.nw_foundation_files.sha256 IS '精确原始正文 SHA-256，不作为访问令牌';
COMMENT ON COLUMN public.nw_foundation_files.blob_key IS '不可由客户端指定的随机物理对象键';
COMMENT ON COLUMN public.nw_foundation_files.state IS 'ready 可用，deleting 已持久化清理意图，deleted 清理完成墓碑';
COMMENT ON COLUMN public.nw_foundation_files.source_kind IS '通用上传或业务任务结果；结果不出现在上传列表，下载继承所属业务当前授权';
COMMENT ON COLUMN public.nw_foundation_files.created_at IS '文件首次受理时刻';
COMMENT ON COLUMN public.nw_foundation_files.updated_at IS '最后状态迁移时刻';
CREATE TABLE public.nw_foundation_file_references (
 file_id varchar(36) NOT NULL,
 scope_key text NOT NULL,
 owner_kind varchar(64) NOT NULL,
 owner_id varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(file_id,owner_kind,owner_id),
 FOREIGN KEY(scope_key,file_id) REFERENCES public.nw_foundation_files(scope_key,id)
);
COMMENT ON TABLE public.nw_foundation_file_references IS '业务所有者维护的活跃引用，零引用才允许清理文件';
COMMENT ON COLUMN public.nw_foundation_file_references.file_id IS '公共文件身份';
COMMENT ON COLUMN public.nw_foundation_file_references.scope_key IS '引用与文件必须同租户/平台归属';
COMMENT ON COLUMN public.nw_foundation_file_references.owner_kind IS '代码定义的领域所有者，foundation.job 为管理任务';
COMMENT ON COLUMN public.nw_foundation_file_references.owner_id IS '所属业务稳定资源键';
COMMENT ON COLUMN public.nw_foundation_file_references.created_at IS '首次引用时刻';
CREATE TABLE public.nw_foundation_admin_jobs (
 id varchar(36) PRIMARY KEY,
 tenant_id bigint REFERENCES public.nw_tenants(id),
 scope_key text GENERATED ALWAYS AS (COALESCE(tenant_id::text,'platform')) STORED NOT NULL,
 member_id varchar(128) NOT NULL,
 handler varchar(64) NOT NULL,
 operation varchar(16) NOT NULL CHECK(operation IN('import','export')),
 input_file_id varchar(36),
 options_json jsonb NOT NULL CHECK(jsonb_typeof(options_json)='object' AND octet_length(options_json::text)<=8192),
 idempotency_key varchar(128) NOT NULL,
 request_digest char(64) NOT NULL,
 state varchar(24) NOT NULL CHECK(state IN('queued','running','cancel_requested','succeeded','failed','cancelled','expired')),
 total_rows integer NOT NULL DEFAULT 0 CHECK(total_rows BETWEEN 0 AND 10000),
 completed_rows integer NOT NULL DEFAULT 0 CHECK(completed_rows BETWEEN 0 AND 10000),
 error_rows integer NOT NULL DEFAULT 0 CHECK(error_rows BETWEEN 0 AND 10000),
 error_message varchar(500),
 result_file_id varchar(36),
 lease_owner varchar(36),
 lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz,
 UNIQUE(scope_key,id),
 UNIQUE(scope_key,idempotency_key),
 FOREIGN KEY(scope_key,input_file_id) REFERENCES public.nw_foundation_files(scope_key,id),
 FOREIGN KEY(scope_key,result_file_id) REFERENCES public.nw_foundation_files(scope_key,id),
 CHECK((lease_owner IS NULL)=(lease_until IS NULL)),
 CHECK((state IN('queued','running','cancel_requested') AND expires_at IS NULL) OR (state IN('succeeded','failed','cancelled','expired') AND expires_at IS NOT NULL)),
 CHECK((operation='import' AND input_file_id IS NOT NULL) OR operation='export' OR state='expired')
);
COMMENT ON TABLE public.nw_foundation_admin_jobs IS '代码注册的后台管理任务，租约控制执行写权；七日终态到期保留幂等墓碑';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.id IS '管理任务 UUID 身份';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.tenant_id IS '可信机构归属，NULL 为独立平台队列';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.scope_key IS '数据库生成的作用域关联与幂等键';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.member_id IS '受理时可信主体，执行每行重新校验当前授权';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.handler IS '启动时注册的代码，禁止动态 SQL/Shell/类名执行';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.operation IS 'import 逐行事务或 export 受控读取';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.input_file_id IS '不可变 CSV 输入，活跃引用保护至终态到期';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.options_json IS '已由注册消费者严格校验的有限字符串选项，不包含身份和凭据';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.idempotency_key IS '调用方受理幂等键，当前作用域内唯一';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.request_digest IS '处理器、操作、输入与选项完整请求摘要';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.state IS '受理、租约执行、取消请求、成功/失败/取消、到期墓碑';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.total_rows IS '数据行总数，不包含表头';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.completed_rows IS '已提交成功或错误回执行数';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.error_rows IS '存在至少一个错误的行数';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.error_message IS '有限长可公开失败原因，不含内部路径/SQL/秘密';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.result_file_id IS '受控结果或错误 CSV 文件，下载重新授权';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.lease_owner IS '拥有执行写权的工作者 UUID';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.lease_until IS '数据库时钟租约截止，过期才允许恢复领取';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.created_at IS '受理提交时间';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.updated_at IS '最后进度或状态提交时间';
COMMENT ON COLUMN public.nw_foundation_admin_jobs.expires_at IS '终态七日材料保留截止，活跃任务无 TTL';
CREATE TABLE public.nw_foundation_admin_job_rows (
 job_id varchar(36) NOT NULL,
 scope_key text NOT NULL,
 row_number integer NOT NULL CHECK(row_number BETWEEN 2 AND 10001),
 errors_json jsonb NOT NULL CHECK(jsonb_typeof(errors_json)='array' AND octet_length(errors_json::text)<=16384),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(job_id,row_number),
 FOREIGN KEY(scope_key,job_id) REFERENCES public.nw_foundation_admin_jobs(scope_key,id)
);
COMMENT ON TABLE public.nw_foundation_admin_job_rows IS '每行与领域写入同事务提交的恢复回执，终态到期才清理';
COMMENT ON COLUMN public.nw_foundation_admin_job_rows.job_id IS '所属管理任务';
COMMENT ON COLUMN public.nw_foundation_admin_job_rows.scope_key IS '同租户复合关联';
COMMENT ON COLUMN public.nw_foundation_admin_job_rows.row_number IS 'CSV 数据逻辑行号，从二开始';
COMMENT ON COLUMN public.nw_foundation_admin_job_rows.errors_json IS '空数组代表成功；仅行号/字段/可纠正说明，不复制原始行';
COMMENT ON COLUMN public.nw_foundation_admin_job_rows.created_at IS '行写入与回执共同提交时刻';
CREATE INDEX skillforge_foundation_files_listing ON public.nw_foundation_files(scope_key,created_at DESC,id DESC) WHERE state='ready';
CREATE INDEX skillforge_foundation_files_cleanup ON public.nw_foundation_files(state,created_at,id) WHERE state<>'deleted';
CREATE INDEX foundation_file_reference_owner ON public.nw_foundation_file_references(scope_key,owner_kind,owner_id);
CREATE INDEX foundation_jobs_listing ON public.nw_foundation_admin_jobs(scope_key,created_at DESC,id DESC);
CREATE INDEX foundation_jobs_queue ON public.nw_foundation_admin_jobs(state,lease_until,created_at,id) WHERE state IN('queued','running','cancel_requested');
CREATE INDEX foundation_jobs_expiry ON public.nw_foundation_admin_jobs(expires_at,id) WHERE state IN('succeeded','failed','cancelled');
-- 平台只能访问 NULL 归属；调度身份仅由域内代码设置，机构/平台队列互不代理。
ALTER TABLE public.nw_foundation_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_files FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_foundation_files_scope ON public.nw_foundation_files USING(scope_key=COALESCE(NULLIF(current_setting('app.tenant_id',true),''),CASE WHEN current_setting('app.platform_admin',true)='true' THEN 'platform' END)) WITH CHECK(scope_key=COALESCE(NULLIF(current_setting('app.tenant_id',true),''),CASE WHEN current_setting('app.platform_admin',true)='true' THEN 'platform' END));
CREATE POLICY skillforge_foundation_files_maintenance ON public.nw_foundation_files FOR SELECT USING(current_setting('app.foundation_maintenance',true)='true');
ALTER TABLE public.nw_foundation_file_references ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_file_references FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_foundation_file_references_scope ON public.nw_foundation_file_references USING(scope_key=COALESCE(NULLIF(current_setting('app.tenant_id',true),''),CASE WHEN current_setting('app.platform_admin',true)='true' THEN 'platform' END)) WITH CHECK(scope_key=COALESCE(NULLIF(current_setting('app.tenant_id',true),''),CASE WHEN current_setting('app.platform_admin',true)='true' THEN 'platform' END));
CREATE POLICY skillforge_foundation_file_references_maintenance ON public.nw_foundation_file_references FOR SELECT USING(current_setting('app.foundation_maintenance',true)='true');
ALTER TABLE public.nw_foundation_admin_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_admin_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY foundation_jobs_scope ON public.nw_foundation_admin_jobs USING(scope_key=COALESCE(NULLIF(current_setting('app.tenant_id',true),''),CASE WHEN current_setting('app.platform_admin',true)='true' THEN 'platform' END)) WITH CHECK(scope_key=COALESCE(NULLIF(current_setting('app.tenant_id',true),''),CASE WHEN current_setting('app.platform_admin',true)='true' THEN 'platform' END));
CREATE POLICY foundation_jobs_dispatch ON public.nw_foundation_admin_jobs USING((current_setting('app.foundation_jobs_dispatch',true)='platform' AND tenant_id IS NULL) OR (current_setting('app.foundation_jobs_dispatch',true)='tenant' AND tenant_id IS NOT NULL)) WITH CHECK((current_setting('app.foundation_jobs_dispatch',true)='platform' AND tenant_id IS NULL) OR (current_setting('app.foundation_jobs_dispatch',true)='tenant' AND tenant_id IS NOT NULL));
ALTER TABLE public.nw_foundation_admin_job_rows ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_admin_job_rows FORCE ROW LEVEL SECURITY;
CREATE POLICY foundation_job_rows_scope ON public.nw_foundation_admin_job_rows USING(scope_key=COALESCE(NULLIF(current_setting('app.tenant_id',true),''),CASE WHEN current_setting('app.platform_admin',true)='true' THEN 'platform' END)) WITH CHECK(scope_key=COALESCE(NULLIF(current_setting('app.tenant_id',true),''),CASE WHEN current_setting('app.platform_admin',true)='true' THEN 'platform' END));
