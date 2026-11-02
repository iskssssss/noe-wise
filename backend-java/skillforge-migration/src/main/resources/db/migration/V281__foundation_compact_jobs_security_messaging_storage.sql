-- B5: 由父负责人统一并入新的 Flyway migration；当前文件不得对旧环境执行。
-- 新建数据库路径；存量转换见报告，需要部署停止旧worker并在备份后的新候选DB验证。
CREATE TABLE public.nw_foundation_admin_job_receipt_blocks (
 job_id varchar(36) NOT NULL,
 scope_key text NOT NULL,
 block_number integer NOT NULL CHECK(block_number BETWEEN 0 AND 39),
 completed_bits bytea NOT NULL CHECK(octet_length(completed_bits)=32),
 CHECK(block_number<>39 OR substring(completed_bits from 3)=decode(repeat('00',30),'hex')),
 PRIMARY KEY(job_id,block_number),
 FOREIGN KEY(scope_key,job_id) REFERENCES public.nw_foundation_admin_jobs(scope_key,id)
);
COMMENT ON TABLE public.nw_foundation_admin_job_receipt_blocks IS 'CSV行副作用同事务恢复身份；每256行一块位图，不保存成功行正文，终态材料七日到期后释放';
COMMENT ON COLUMN public.nw_foundation_admin_job_receipt_blocks.job_id IS '不可变管理任务身份';
COMMENT ON COLUMN public.nw_foundation_admin_job_receipt_blocks.scope_key IS '同租户复合关联，平台单独作用域';
COMMENT ON COLUMN public.nw_foundation_admin_job_receipt_blocks.block_number IS '逻辑数据行号减二除256的块身份';
COMMENT ON COLUMN public.nw_foundation_admin_job_receipt_blocks.completed_bits IS '32字节恢复身份位图，包含成功及已记录错误行；位号为逻辑行号减二模256';
ALTER TABLE public.nw_foundation_admin_job_receipt_blocks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_admin_job_receipt_blocks FORCE ROW LEVEL SECURITY;
CREATE POLICY foundation_job_blocks_scope ON public.nw_foundation_admin_job_receipt_blocks USING(scope_key=COALESCE(NULLIF(current_setting('app.tenant_id',true),''),CASE WHEN current_setting('app.platform_admin',true)='true' THEN 'platform' END)) WITH CHECK(scope_key=COALESCE(NULLIF(current_setting('app.tenant_id',true),''),CASE WHEN current_setting('app.platform_admin',true)='true' THEN 'platform' END));

-- 原回执仅保留错误正文；先转换全部旧身份再删成功明细，不可交换顺序。
DO $$ DECLARE receipt record; BEGIN
 FOR receipt IN SELECT job_id,scope_key,row_number FROM public.nw_foundation_admin_job_rows ORDER BY job_id,row_number LOOP
  INSERT INTO public.nw_foundation_admin_job_receipt_blocks(job_id,scope_key,block_number,completed_bits)
  VALUES(receipt.job_id,receipt.scope_key,(receipt.row_number-2)/256,set_bit(decode(repeat('00',32),'hex'),(receipt.row_number-2)%256,1))
  ON CONFLICT(job_id,block_number) DO UPDATE SET completed_bits=set_bit(nw_foundation_admin_job_receipt_blocks.completed_bits,(receipt.row_number-2)%256,1);
 END LOOP;
END $$;
DELETE FROM public.nw_foundation_admin_job_rows WHERE jsonb_array_length(errors_json)=0;
ALTER TABLE public.nw_foundation_admin_job_rows ADD CONSTRAINT foundation_job_error_nonempty CHECK(jsonb_array_length(errors_json)>0);
COMMENT ON TABLE public.nw_foundation_admin_job_rows IS 'CSV错误行定位及可纠正说明；成功身份由紧凑位图持有，终态材料七日到期释放';
COMMENT ON COLUMN public.nw_foundation_admin_job_rows.errors_json IS '非空错误数组，仅行号/字段/说明，不复制原始行';

-- B4 分段：已批准普通撤销/过期会话90日，显式覆盖与调查保护，仍有效认证永不回收。
-- 沿V40已批准权限契约：受控迁移管理员必须为superuser/BYPASSRLS，机构应用角色不提权。
-- FORCE RLS目录必须由该管理员拥有；无能力时整分段拒绝，不能静默创建租户盲目录。
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls)) THEN
  RAISE EXCEPTION 'B4 cleanup directory requires a controlled superuser or BYPASSRLS migration owner';
 END IF;
END $$;
ALTER TABLE public.nw_foundation_security_sessions ADD COLUMN investigation_protected boolean NOT NULL DEFAULT false;
ALTER TABLE public.nw_foundation_security_policies ADD COLUMN session_retention_days integer DEFAULT 90 CHECK(session_retention_days BETWEEN 1 AND 36500);
ALTER TABLE public.nw_foundation_security_policies ADD COLUMN next_session_cleanup_at timestamptz NOT NULL DEFAULT now();
COMMENT ON COLUMN public.nw_foundation_security_sessions.investigation_protected IS '同范围安全管理员调查保护；与清理竞争同一行锁，解除另记安全审计';
COMMENT ON COLUMN public.nw_foundation_security_policies.session_retention_days IS '权威撤销或到期后调查保留天数；批准默认90日，NULL仅为管理员显式关闭';
COMMENT ON COLUMN public.nw_foundation_security_policies.next_session_cleanup_at IS '跨宿主公平有界扫描水位，不是业务到期时间';
INSERT INTO public.nw_foundation_security_policies(scope_key) SELECT DISTINCT scope_key FROM public.nw_foundation_security_sessions ON CONFLICT DO NOTHING;
CREATE INDEX foundation_security_session_cleanup ON public.nw_foundation_security_sessions(scope_key,COALESCE(revoked_at,expires_at),id) WHERE NOT investigation_protected;
CREATE FUNCTION public.foundation_security_cleanup_scopes() RETURNS SETOF varchar
 LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
 WITH due AS (SELECT p.scope_key FROM public.nw_foundation_security_policies p WHERE p.session_retention_days IS NOT NULL AND p.next_session_cleanup_at<=now() AND EXISTS(SELECT 1 FROM public.nw_foundation_security_sessions s WHERE s.scope_key=p.scope_key AND NOT s.investigation_protected AND COALESCE(s.revoked_at,s.expires_at)<now()-make_interval(days=>p.session_retention_days)) ORDER BY p.next_session_cleanup_at,p.scope_key FOR UPDATE OF p SKIP LOCKED LIMIT 100)
 UPDATE public.nw_foundation_security_policies p SET next_session_cleanup_at=now()+interval '1 minute' FROM due d WHERE p.scope_key=d.scope_key RETURNING p.scope_key
 $$;
DO $$ BEGIN
 EXECUTE format('ALTER FUNCTION public.foundation_security_cleanup_scopes() OWNER TO %I',current_user);
 IF NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_roles r ON r.oid=p.proowner WHERE p.oid='public.foundation_security_cleanup_scopes()'::regprocedure AND (r.rolsuper OR r.rolbypassrls)) THEN
  RAISE EXCEPTION 'B4 cleanup directory owner must retain superuser or BYPASSRLS capability';
 END IF;
END $$;
REVOKE ALL ON FUNCTION public.foundation_security_cleanup_scopes() FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='skillforge') THEN GRANT EXECUTE ON FUNCTION public.foundation_security_cleanup_scopes() TO skillforge; END IF;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='skillforge_platform') THEN GRANT EXECUTE ON FUNCTION public.foundation_security_cleanup_scopes() TO skillforge_platform; END IF;
END $$;
COMMENT ON FUNCTION public.foundation_security_cleanup_scopes() IS '受控superuser/BYPASSRLS迁移owner枚举策略scope，仅现有scheduler应用角色获EXECUTE，不返回会话或凭据；清理逐scope恢复RLS';

-- B6 分段：公告共享正文一次加密保存及批准30日普通终态生命周期；不在runtime双读/双写旧格式。
-- 本分段可独立集成，必须独立检查目录owner契约；只给现有scheduler角色EXECUTE。
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls)) THEN
  RAISE EXCEPTION 'B6 cleanup directory requires a controlled superuser or BYPASSRLS migration owner';
 END IF;
END $$;
-- 存量迁移需先备份并停旧worker；离线导出id/scope/body，使用同目录转换器产生staging SQL，
-- 在获授权的新转换副本中载入后再执行迁移。缺任何转换或摘要不匹配均整迁移失败，不删明文。
ALTER TABLE public.nw_foundation_announcements ADD COLUMN body_cipher text;
ALTER TABLE public.nw_foundation_announcements ADD COLUMN body_format varchar(64) NOT NULL DEFAULT 'text/plain;charset=utf-8' CHECK(body_format='text/plain;charset=utf-8');
ALTER TABLE public.nw_foundation_announcements ADD COLUMN body_digest varchar(64) CHECK(body_digest ~ '^[a-f0-9]{64}$');
ALTER TABLE public.nw_foundation_announcements ADD COLUMN retired_at timestamptz;
ALTER TABLE public.nw_foundation_announcements ADD COLUMN withdrawn_at timestamptz;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM public.nw_foundation_announcements) THEN
  IF to_regclass('public.nw_foundation_announcement_body_conversion') IS NULL THEN RAISE EXCEPTION 'B6 existing announcements require authorized offline AES-GCM conversion before schema replacement'; END IF;
  EXECUTE 'UPDATE public.nw_foundation_announcements a SET body_cipher=c.body_cipher,body_digest=c.body_digest FROM public.nw_foundation_announcement_body_conversion c WHERE a.scope_key=c.scope_key AND a.id=c.id';
  IF EXISTS(SELECT 1 FROM public.nw_foundation_announcements WHERE body_cipher IS NULL OR body_digest IS DISTINCT FROM encode(sha256(convert_to(body,'UTF8')),'hex')) THEN RAISE EXCEPTION 'B6 announcement conversion missing or original-byte digest mismatch'; END IF;
 END IF;
END $$;
ALTER TABLE public.nw_foundation_announcements ALTER COLUMN body_digest SET NOT NULL;
-- 旧记录没有精确撤回时刻；以本次owner确认时间保守起算30日，不猜测更早的历史时间。
UPDATE public.nw_foundation_announcements SET withdrawn_at=now() WHERE status='withdrawn';
ALTER TABLE public.nw_foundation_announcements DROP COLUMN body;
DROP TABLE IF EXISTS public.nw_foundation_announcement_body_conversion;
ALTER TABLE public.nw_foundation_announcements ADD CONSTRAINT foundation_announcement_body_present CHECK((body_cipher IS NOT NULL)=(retired_at IS NULL));
ALTER TABLE public.nw_foundation_announcements ADD CONSTRAINT foundation_announcement_cipher_size CHECK(body_cipher IS NULL OR octet_length(body_cipher)<=174880);
ALTER TABLE public.nw_foundation_notifications ADD COLUMN investigation_protected boolean NOT NULL DEFAULT false;
ALTER TABLE public.nw_foundation_notifications ADD COLUMN retired_at timestamptz;
DO $$ DECLARE constraint_row record; BEGIN
 FOR constraint_row IN SELECT conname FROM pg_constraint WHERE conrelid='public.nw_foundation_notifications'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%recipient_member_id%' AND pg_get_constraintdef(oid) LIKE '%title%' AND pg_get_constraintdef(oid) LIKE '%body%' LOOP
  EXECUTE format('ALTER TABLE public.nw_foundation_notifications DROP CONSTRAINT %I',constraint_row.conname);
 END LOOP;
END $$;
-- 删除每收件人旧副本前已有一份匹配原字节的正式正文，scope复合FK身份不变。
UPDATE public.nw_foundation_notifications SET title=NULL,body=NULL,email_subject_cipher=NULL,email_body_cipher=NULL WHERE announcement_id IS NOT NULL;
ALTER TABLE public.nw_foundation_notifications ADD CONSTRAINT foundation_notification_visible_body CHECK(NOT in_app OR (recipient_member_id IS NOT NULL AND (retired_at IS NOT NULL OR announcement_id IS NOT NULL OR (title IS NOT NULL AND body IS NOT NULL))));
ALTER TABLE public.nw_foundation_notifications ADD CONSTRAINT foundation_notification_announcement_reference CHECK(announcement_id IS NULL OR (title IS NULL AND body IS NULL AND email_subject_cipher IS NULL AND email_body_cipher IS NULL AND template_id IS NULL));
ALTER TABLE public.nw_foundation_notifications ADD CONSTRAINT foundation_notification_retired_unprotected CHECK(retired_at IS NULL OR NOT investigation_protected);
COMMENT ON COLUMN public.nw_foundation_announcements.body_cipher IS '唯一公告正文AES-GCM封套；站内授权读取与SMTP引用相同身份解密，退休后NULL';
COMMENT ON COLUMN public.nw_foundation_announcements.body_format IS '原字节声明格式与编码，读取或投递不能从当前模板重建';
COMMENT ON COLUMN public.nw_foundation_announcements.body_digest IS '原UTF-8正文SHA-256摘要，与scope/id绑定，退休墓碑保留';
COMMENT ON COLUMN public.nw_foundation_announcements.retired_at IS '全部收件引用退休后释放正文的提交时间，NULL仍保留';
COMMENT ON COLUMN public.nw_foundation_announcements.withdrawn_at IS '正常撤回提交时刻；迁移前已撤回但缺时刻时为本次owner保守确认时间，保留期从此不提前起算';
COMMENT ON COLUMN public.nw_foundation_notifications.investigation_protected IS '当前调查引用，通知未退休时登记，与生命周期共享scope锁';
COMMENT ON COLUMN public.nw_foundation_notifications.retired_at IS '终态无引用材料释放时间，墓碑继续保护受理幂等及recipient关系';
COMMENT ON COLUMN public.nw_foundation_notifications.announcement_id IS '共享公告精确正文身份；同scope复合FK，不复制正文或cipher';
CREATE TABLE public.nw_foundation_messaging_retention (
 scope_key varchar(96) PRIMARY KEY,tenant_id bigint REFERENCES public.nw_tenants(id),
 retention_days integer DEFAULT 30 CHECK(retention_days BETWEEN 1 AND 36500),
 next_cleanup_at timestamptz NOT NULL DEFAULT now(),created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(scope_key=CASE WHEN tenant_id IS NULL THEN 'platform' ELSE 'tenant:'||tenant_id::text END)
);
ALTER TABLE public.nw_foundation_messaging_retention ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_foundation_messaging_retention FORCE ROW LEVEL SECURITY;
CREATE POLICY messaging_retention_scope ON public.nw_foundation_messaging_retention USING ((tenant_id::text=current_setting('app.tenant_id',true)) OR (tenant_id IS NULL AND COALESCE(current_setting('app.tenant_id',true),'')='')) WITH CHECK ((tenant_id::text=current_setting('app.tenant_id',true)) OR (tenant_id IS NULL AND COALESCE(current_setting('app.tenant_id',true),'')=''));
COMMENT ON TABLE public.nw_foundation_messaging_retention IS '同范围普通终态材料策略；批准默认30日，UNKNOWN/未读/可重试和调查保护独立保留';
COMMENT ON COLUMN public.nw_foundation_messaging_retention.scope_key IS '平台或可信机构作用域，与通知所有权一致';
COMMENT ON COLUMN public.nw_foundation_messaging_retention.tenant_id IS '可信机构归属，NULL只属于独立平台';
COMMENT ON COLUMN public.nw_foundation_messaging_retention.retention_days IS '已退役通知及已接受/取消投递材料保留天数，批准默认30日，NULL仅为管理员显式关闭';
COMMENT ON COLUMN public.nw_foundation_messaging_retention.next_cleanup_at IS '跨宿主公平扫描水位，仅控制扫描调度';
COMMENT ON COLUMN public.nw_foundation_messaging_retention.created_at IS '策略首次登记提交时间';
COMMENT ON COLUMN public.nw_foundation_messaging_retention.updated_at IS '策略实际变化提交时间';
INSERT INTO public.nw_foundation_messaging_retention(scope_key,tenant_id) SELECT scope_key,tenant_id FROM (SELECT scope_key,tenant_id FROM public.nw_foundation_notifications UNION SELECT scope_key,tenant_id FROM public.nw_foundation_notification_templates UNION SELECT scope_key,tenant_id FROM public.nw_foundation_mailboxes) sources GROUP BY scope_key,tenant_id ON CONFLICT DO NOTHING;
CREATE INDEX foundation_notification_history_cleanup ON public.nw_foundation_notifications(scope_key,created_at,id) WHERE retired_at IS NULL AND NOT investigation_protected;
CREATE INDEX foundation_notification_attempt_cleanup ON public.nw_foundation_notification_attempts(scope_key,delivery_id,created_at,id);
CREATE INDEX foundation_notification_announcement_reference ON public.nw_foundation_notifications(scope_key,announcement_id,retired_at) WHERE announcement_id IS NOT NULL;
CREATE FUNCTION public.foundation_messaging_cleanup_scopes() RETURNS SETOF varchar
 LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
 WITH due AS (SELECT p.scope_key FROM public.nw_foundation_messaging_retention p WHERE p.retention_days IS NOT NULL AND p.next_cleanup_at<=now() AND (
 EXISTS(SELECT 1 FROM public.nw_foundation_notifications n LEFT JOIN public.nw_foundation_notification_outbox o ON o.scope_key=n.scope_key AND o.notification_id=n.id WHERE n.scope_key=p.scope_key AND NOT n.investigation_protected AND n.retired_at IS NULL AND (NOT n.in_app OR n.read_at IS NOT NULL OR EXISTS(SELECT 1 FROM public.nw_foundation_announcements a WHERE a.scope_key=n.scope_key AND a.id=n.announcement_id AND a.status='withdrawn')) AND GREATEST(n.created_at,n.read_at,(SELECT a.withdrawn_at FROM public.nw_foundation_announcements a WHERE a.scope_key=n.scope_key AND a.id=n.announcement_id),o.updated_at)<now()-make_interval(days=>p.retention_days) AND (o.id IS NULL OR o.status IN('accepted','cancelled')))
 OR EXISTS(SELECT 1 FROM public.nw_foundation_notification_outbox o JOIN public.nw_foundation_notifications n ON n.scope_key=o.scope_key AND n.id=o.notification_id WHERE o.scope_key=p.scope_key AND n.retired_at IS NOT NULL AND o.status IN('accepted','cancelled') AND o.mailbox_id IS NOT NULL)
 OR EXISTS(SELECT 1 FROM public.nw_foundation_announcements a WHERE a.scope_key=p.scope_key AND a.body_cipher IS NOT NULL AND a.created_at<now()-make_interval(days=>p.retention_days) AND NOT EXISTS(SELECT 1 FROM public.nw_foundation_notifications n WHERE n.scope_key=a.scope_key AND n.announcement_id=a.id AND (n.retired_at IS NULL OR n.investigation_protected)))
 OR EXISTS(SELECT 1 FROM public.nw_foundation_notification_templates t WHERE t.scope_key=p.scope_key AND NOT t.current AND t.created_at<now()-make_interval(days=>p.retention_days) AND NOT EXISTS(SELECT 1 FROM public.nw_foundation_notifications n WHERE n.scope_key=t.scope_key AND n.template_id=t.id))
 OR EXISTS(SELECT 1 FROM public.nw_foundation_mailboxes m WHERE m.scope_key=p.scope_key AND NOT m.current AND m.created_at<now()-make_interval(days=>p.retention_days) AND NOT EXISTS(SELECT 1 FROM public.nw_foundation_notification_outbox o WHERE o.scope_key=m.scope_key AND o.mailbox_id=m.id))
 ) ORDER BY p.next_cleanup_at,p.scope_key FOR UPDATE OF p SKIP LOCKED LIMIT 100)
 UPDATE public.nw_foundation_messaging_retention p SET next_cleanup_at=now()+interval '1 minute' FROM due d WHERE p.scope_key=d.scope_key RETURNING p.scope_key
 $$;
DO $$ BEGIN
 EXECUTE format('ALTER FUNCTION public.foundation_messaging_cleanup_scopes() OWNER TO %I',current_user);
 IF NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_roles r ON r.oid=p.proowner WHERE p.oid='public.foundation_messaging_cleanup_scopes()'::regprocedure AND (r.rolsuper OR r.rolbypassrls)) THEN
  RAISE EXCEPTION 'B6 cleanup directory owner must retain superuser or BYPASSRLS capability';
 END IF;
END $$;
REVOKE ALL ON FUNCTION public.foundation_messaging_cleanup_scopes() FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='skillforge') THEN GRANT EXECUTE ON FUNCTION public.foundation_messaging_cleanup_scopes() TO skillforge; END IF;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='skillforge_platform') THEN GRANT EXECUTE ON FUNCTION public.foundation_messaging_cleanup_scopes() TO skillforge_platform; END IF;
END $$;
COMMENT ON FUNCTION public.foundation_messaging_cleanup_scopes() IS '受控superuser/BYPASSRLS迁移owner枚举策略scope，仅现有scheduler应用角色获EXECUTE，无正文；每scope仍按RLS与引用保护回收';
