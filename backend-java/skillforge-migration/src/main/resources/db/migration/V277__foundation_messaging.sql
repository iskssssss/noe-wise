-- 公告、站内信、冻结模板与可靠邮件投递属于宿主；邮件接受不等于最终送达。
CREATE TABLE public.nw_foundation_announcements (
 id varchar(64) PRIMARY KEY, scope_key varchar(96) NOT NULL, tenant_id bigint REFERENCES public.nw_tenants(id),
 title varchar(240) NOT NULL, body text NOT NULL CHECK(octet_length(body)<=131072),
 audience varchar(32) NOT NULL CHECK(audience IN ('all','members','departments','roles')),
 recipients integer NOT NULL CHECK(recipients>=0), status varchar(32) NOT NULL CHECK(status IN ('published','withdrawn')),
 deduplication_key varchar(240) NOT NULL, fingerprint varchar(64) NOT NULL,
 created_by varchar(64) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(scope_key,deduplication_key), UNIQUE(scope_key,id),
 CHECK(scope_key=CASE WHEN tenant_id IS NULL THEN 'platform' ELSE 'tenant:'||tenant_id::text END)
);
CREATE TABLE public.nw_foundation_notification_templates (
 id varchar(64) PRIMARY KEY, scope_key varchar(96) NOT NULL, tenant_id bigint REFERENCES public.nw_tenants(id),
 template_key varchar(128) NOT NULL, subject varchar(1024) NOT NULL, body text NOT NULL CHECK(octet_length(body)<=131072),
 variable_names text NOT NULL, current boolean NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(scope_key,id), CHECK(scope_key=CASE WHEN tenant_id IS NULL THEN 'platform' ELSE 'tenant:'||tenant_id::text END)
);
CREATE UNIQUE INDEX foundation_template_current ON public.nw_foundation_notification_templates(scope_key,template_key) WHERE current;
CREATE TABLE public.nw_foundation_mailboxes (
 id varchar(64) PRIMARY KEY, scope_key varchar(96) NOT NULL, tenant_id bigint REFERENCES public.nw_tenants(id),
 host varchar(256), port integer CHECK(port BETWEEN 1 AND 65535), username varchar(256), password_cipher text,
 from_address varchar(256), tls_mode varchar(32) CHECK(tls_mode IN ('starttls','tls')),
 enabled boolean NOT NULL, current boolean NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(scope_key,id), CHECK(scope_key=CASE WHEN tenant_id IS NULL THEN 'platform' ELSE 'tenant:'||tenant_id::text END),
 CHECK(NOT enabled OR (host IS NOT NULL AND port IS NOT NULL AND username IS NOT NULL AND password_cipher IS NOT NULL AND from_address IS NOT NULL AND tls_mode IS NOT NULL))
);
CREATE UNIQUE INDEX foundation_mailbox_current ON public.nw_foundation_mailboxes(scope_key) WHERE current;
CREATE TABLE public.nw_foundation_notifications (
 id varchar(64) PRIMARY KEY, scope_key varchar(96) NOT NULL, tenant_id bigint REFERENCES public.nw_tenants(id),
 recipient_member_id varchar(64), recipient_email varchar(256), deduplication_key varchar(240) NOT NULL,
 fingerprint varchar(64) NOT NULL, template_id varchar(64), title varchar(240), body text,
 email_subject_cipher text, email_body_cipher text, announcement_id varchar(64), in_app boolean NOT NULL,
 read_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(scope_key,deduplication_key), UNIQUE(scope_key,id),
 FOREIGN KEY(scope_key,announcement_id) REFERENCES public.nw_foundation_announcements(scope_key,id),
 FOREIGN KEY(scope_key,template_id) REFERENCES public.nw_foundation_notification_templates(scope_key,id),
 CHECK(scope_key=CASE WHEN tenant_id IS NULL THEN 'platform' ELSE 'tenant:'||tenant_id::text END),
 CHECK(NOT in_app OR (recipient_member_id IS NOT NULL AND title IS NOT NULL AND body IS NOT NULL)),
 CHECK((email_subject_cipher IS NULL)=(email_body_cipher IS NULL)),
 CHECK(email_subject_cipher IS NULL OR recipient_email IS NOT NULL),
 CHECK(body IS NULL OR octet_length(body)<=131072)
);
CREATE INDEX foundation_inbox ON public.nw_foundation_notifications(scope_key,recipient_member_id,id DESC) WHERE in_app;
CREATE TABLE public.nw_foundation_notification_outbox (
 id varchar(64) PRIMARY KEY, scope_key varchar(96) NOT NULL, tenant_id bigint REFERENCES public.nw_tenants(id),
 notification_id varchar(64) NOT NULL, mailbox_id varchar(64),
 status varchar(32) NOT NULL CHECK(status IN ('pending','leased','dispatching','accepted','rejected','unknown','blocked','cancelled')),
 error_code varchar(128), attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 lease_token varchar(64), lease_until timestamptz, updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(scope_key,notification_id), UNIQUE(scope_key,id),
 FOREIGN KEY(scope_key,notification_id) REFERENCES public.nw_foundation_notifications(scope_key,id),
 FOREIGN KEY(scope_key,mailbox_id) REFERENCES public.nw_foundation_mailboxes(scope_key,id),
 CHECK(scope_key=CASE WHEN tenant_id IS NULL THEN 'platform' ELSE 'tenant:'||tenant_id::text END),
 CHECK((status IN ('leased','dispatching'))=(lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX foundation_notification_due ON public.nw_foundation_notification_outbox(scope_key,updated_at,id) WHERE status IN ('pending','leased','dispatching');
CREATE TABLE public.nw_foundation_notification_attempts (
 id varchar(64) PRIMARY KEY, scope_key varchar(96) NOT NULL, tenant_id bigint REFERENCES public.nw_tenants(id),
 delivery_id varchar(64) NOT NULL, lease_token varchar(64), disposition varchar(32) NOT NULL,
 error_code varchar(128), evidence varchar(1024), created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(scope_key,delivery_id) REFERENCES public.nw_foundation_notification_outbox(scope_key,id),
 CHECK(scope_key=CASE WHEN tenant_id IS NULL THEN 'platform' ELSE 'tenant:'||tenant_id::text END)
);

-- 所有普通SQL仍受当前租户绑定；后台仅能枚举待处理作用域，不暴露消息或凭据正文。
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['nw_foundation_announcements','nw_foundation_notification_templates','nw_foundation_mailboxes','nw_foundation_notifications','nw_foundation_notification_outbox','nw_foundation_notification_attempts'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY messaging_scope_isolation ON public.%I USING ((tenant_id::text = current_setting(''app.tenant_id'',true)) OR (tenant_id IS NULL AND COALESCE(current_setting(''app.tenant_id'',true),'''')='''')) WITH CHECK ((tenant_id::text = current_setting(''app.tenant_id'',true)) OR (tenant_id IS NULL AND COALESCE(current_setting(''app.tenant_id'',true),'''')=''''))',t);
 END LOOP;
END $$;
-- 工作目录只保存作用域与轮询时间，不保存正文/凭据，避免worker需要RLS绕过角色。
CREATE TABLE public.nw_foundation_messaging_work_scopes (
 scope_key varchar(96) PRIMARY KEY, next_poll_at timestamptz NOT NULL DEFAULT now(),
 CHECK(scope_key='platform' OR scope_key ~ '^tenant:[1-9][0-9]{0,18}$')
);
CREATE FUNCTION public.foundation_messaging_due_scopes() RETURNS SETOF varchar
 LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp
 AS $$ WITH due AS (SELECT scope_key FROM public.nw_foundation_messaging_work_scopes WHERE next_poll_at<=now() ORDER BY next_poll_at,scope_key FOR UPDATE SKIP LOCKED LIMIT 100)
 UPDATE public.nw_foundation_messaging_work_scopes s SET next_poll_at=now()+interval '5 seconds' FROM due WHERE s.scope_key=due.scope_key RETURNING s.scope_key $$;
REVOKE ALL ON FUNCTION public.foundation_messaging_due_scopes() FROM PUBLIC;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='skillforge') THEN
 GRANT EXECUTE ON FUNCTION public.foundation_messaging_due_scopes() TO skillforge;
END IF; END $$;

COMMENT ON TABLE public.nw_foundation_announcements IS '同作用域公告权威状态；正文不可变，撤回隐藏站内正文并取消未派发邮件';
COMMENT ON TABLE public.nw_foundation_notification_templates IS '不可变通知模板版本；当前指针只决定新通知';
COMMENT ON TABLE public.nw_foundation_mailboxes IS '不可变SMTP配置版本与AES-GCM加密凭据；当前停用仍阻止旧版本投递';
COMMENT ON TABLE public.nw_foundation_notifications IS '同事务受理通知；仅站内正文为纯文本，所有邮件正文加密且不查询回显';
COMMENT ON TABLE public.nw_foundation_notification_outbox IS '可靠邮件outbox；accepted仅为SMTP接受，dispatching失租约转unknown禁止盲重试';
COMMENT ON TABLE public.nw_foundation_notification_attempts IS '追加投递/人工核验事实；仅安全错误码与人工证据，不记录邮箱正文或密码';
COMMENT ON TABLE public.nw_foundation_messaging_work_scopes IS '后台公平轮询的无正文作用域目录；不依赖数据库角色绕过RLS';
COMMENT ON COLUMN public.nw_foundation_notifications.deduplication_key IS '同作用域稳定业务键；相同键不同内容必须冲突';
COMMENT ON COLUMN public.nw_foundation_notifications.fingerprint IS '完整受理语义SHA-256摘要；原模板变量不保存';
COMMENT ON COLUMN public.nw_foundation_notifications.email_subject_cipher IS 'CryptoService AES-GCM加密标题，包含敏感模板变量时不泄漏';
COMMENT ON COLUMN public.nw_foundation_notifications.email_body_cipher IS 'CryptoService AES-GCM加密正文，安全一次性token仅在发送边界解密';
COMMENT ON COLUMN public.nw_foundation_mailboxes.password_cipher IS '独立于普通配置的加密SMTP凭据；新版本沿用时复制密文，无明文查询';
COMMENT ON COLUMN public.nw_foundation_notification_outbox.mailbox_id IS '受理时冻结配置版本；未配置为NULL且blocked，显式恢复采用当前版本';
COMMENT ON COLUMN public.nw_foundation_notification_outbox.lease_token IS '每次claim新的写权token，迟到worker不得覆盖新处理结果';
COMMENT ON COLUMN public.nw_foundation_notification_outbox.lease_until IS '有限租约截止；只允许未派发leased恢复pending';
COMMENT ON FUNCTION public.foundation_messaging_due_scopes() IS '后台受控枚举待处理作用域，不跨租户返回正文；worker仍逐作用域RLS执行';

-- 公共隔离字段和完整性约束统一注释，领域敏感字段以上述专门注释为准。
DO $$ DECLARE c record; description text; BEGIN
 FOR c IN SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('nw_foundation_announcements','nw_foundation_notification_templates','nw_foundation_mailboxes','nw_foundation_notifications','nw_foundation_notification_outbox','nw_foundation_notification_attempts','nw_foundation_messaging_work_scopes') LOOP
  IF col_description(('public.'||c.table_name)::regclass,(SELECT attnum FROM pg_attribute WHERE attrelid=('public.'||c.table_name)::regclass AND attname=c.column_name)) IS NOT NULL THEN CONTINUE; END IF;
  description=CASE c.column_name
   WHEN 'id' THEN '雪花ID，以十进制字符串传输，生命周期内稳定不变'
   WHEN 'scope_key' THEN '可信账号域：platform或tenant:机构ID；不可由请求覆盖'
   WHEN 'tenant_id' THEN '机构隔离维度，平台为空；FORCE RLS按当前租户绑定'
   WHEN 'title' THEN '冻结纯文本标题，最长240字符；邮件only通知为空'
   WHEN 'subject' THEN '不可变纯文本标题模板，仅支持双花括号变量'
   WHEN 'body' THEN '冻结纯文本正文，最多131072字节；邮件only通知为空'
   WHEN 'audience' THEN '冻结公告解析范围：all、members、departments、roles'
   WHEN 'recipients' THEN '发布时解析的有效去重成员数，不随当前目录变化'
   WHEN 'status' THEN '所属公告或投递权威状态；合法取值由CHECK约束定义'
   WHEN 'deduplication_key' THEN '同作用域幂等业务键，不含正文或敏感变量'
   WHEN 'fingerprint' THEN '完整受理语义SHA-256摘要，同键不同摘要冲突'
   WHEN 'created_by' THEN '当前可信发布人账号ID，邮件派发前实时检查其发布权限'
   WHEN 'created_at' THEN '事实受理或版本发布时间，带时区绝对时刻'
   WHEN 'template_key' THEN '同作用域稳定模板业务键'
   WHEN 'variable_names' THEN '逗号分隔的完整必需变量名；不保存变量值'
   WHEN 'current' THEN '当前发布指针，仅影响新受理；旧版本正文不可变'
   WHEN 'host' THEN 'SMTP主机名；启用前必须配置'
   WHEN 'port' THEN 'SMTP TCP端口，范围1至65535'
   WHEN 'username' THEN 'SMTP认证账号，只在配置管理和发送边界使用'
   WHEN 'from_address' THEN '单个已授权发件邮箱，不允许列表或头注入'
   WHEN 'tls_mode' THEN 'starttls或tls，发送强制加密并校验证书主机名'
   WHEN 'enabled' THEN '当前可派发开关，关闭实时阻止未派发邮件'
   WHEN 'recipient_member_id' THEN '同作用域站内收件人ID，邮件only允许为空'
   WHEN 'recipient_email' THEN '受理时已确认的单个邮箱，不从模板变量推断'
   WHEN 'template_id' THEN '本作用域冻结模板版本ID，公告正文通知可为空'
   WHEN 'announcement_id' THEN '本作用域原公告ID，撤回后隐藏站内正文'
   WHEN 'in_app' THEN '是否允许本人站内读取，安全token模板禁止开启'
   WHEN 'read_at' THEN '本人首次已读时间，未读为空，重复已读不改写'
   WHEN 'notification_id' THEN '本作用域冻结通知ID，每通知最多一个邮件outbox'
   WHEN 'error_code' THEN '安全稳定处理原因，不包含第三方错误正文或秘密'
   WHEN 'attempts' THEN '领取尝试计数，只有claim时递增'
   WHEN 'updated_at' THEN '最近权威状态处理时间，不表示最终送达时间'
   WHEN 'delivery_id' THEN '本作用域投递ID，追加尝试事实所属记录'
   WHEN 'disposition' THEN '派发结果或人工核验事实，不覆盖历史尝试'
   WHEN 'evidence' THEN '人工核验工单/服务端记录引用，最长1024字符，不应包含秘密'
   WHEN 'lease_token' THEN '本次尝试租约写权token，人工事实为空'
   WHEN 'next_poll_at' THEN '公平轮询下一次受理时间，新pending同事务唤醒'
   ELSE '消息领域受控持久化字段'
  END;
  EXECUTE format('COMMENT ON COLUMN public.%I.%I IS %L',c.table_name,c.column_name,description);
 END LOOP;
 FOR c IN SELECT n.nspname,t.relname,k.conname,pg_get_constraintdef(k.oid) definition FROM pg_constraint k JOIN pg_class t ON t.oid=k.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='public' AND t.relname IN ('nw_foundation_announcements','nw_foundation_notification_templates','nw_foundation_mailboxes','nw_foundation_notifications','nw_foundation_notification_outbox','nw_foundation_notification_attempts','nw_foundation_messaging_work_scopes') LOOP
  EXECUTE format('COMMENT ON CONSTRAINT %I ON %I.%I IS %L',c.conname,c.nspname,c.relname,'消息领域完整性约束：'||c.definition);
 END LOOP;
END $$;
COMMENT ON INDEX public.foundation_template_current IS '每作用域每模板键仅一个当前版本';
COMMENT ON INDEX public.foundation_mailbox_current IS '每作用域仅一个当前邮箱版本';
COMMENT ON INDEX public.foundation_inbox IS '当前成员站内消息倒序分页；正文权限仍由应用和RLS检查';
COMMENT ON INDEX public.foundation_notification_due IS '本作用域pending和失效租约扫描，不包含终态';
