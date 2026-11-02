-- F1/F2 target schema request. Coordinator owns migration numbering and deployment.
-- This request is for an isolated empty/rebuilt environment. It deliberately refuses
-- existing attachment rows; live conversion requires a separately reviewed backup,
-- exact byte verification and restore plan, and is not performed by this change.
DO $$ BEGIN
  IF EXISTS(SELECT 1 FROM public.nw_chat_session_attachments) THEN
    RAISE EXCEPTION 'chat storage target schema requires empty attachment table; existing data conversion is not authorized';
  END IF;
END $$;

CREATE TABLE public.nw_chat_attachment_bodies (
  tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
  sha256 char(64) NOT NULL,
  size bigint NOT NULL,
  format varchar(16) NOT NULL,
  content bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(tenant_id,sha256,size,format),
  CONSTRAINT ck_chat_attachment_body_digest CHECK(sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_chat_attachment_body_format CHECK(format='bytea.v1'),
  CONSTRAINT ck_chat_attachment_body_size CHECK(size BETWEEN 1 AND 52428800 AND octet_length(content)=size)
);
CREATE INDEX idx_skillforge_chat_attachment_bodies_reclaim ON public.nw_chat_attachment_bodies(tenant_id,created_at,sha256,size);
ALTER TABLE public.nw_chat_attachment_bodies ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_chat_attachment_bodies FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_chat_attachment_bodies_tenant_isolation ON public.nw_chat_attachment_bodies
  USING(tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''))
  WITH CHECK(tenant_id::text=COALESCE(current_setting('app.tenant_id',true),''));
CREATE FUNCTION public.chat_attachment_body_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'chat attachment body is immutable';
END $$;
CREATE TRIGGER trg_chat_attachment_body_immutable BEFORE UPDATE ON public.nw_chat_attachment_bodies
  FOR EACH ROW EXECUTE FUNCTION public.chat_attachment_body_immutable();

ALTER TABLE public.nw_chat_session_attachments
  DROP CONSTRAINT ck_chat_session_attachment_content_size,
  DROP CONSTRAINT ck_chat_session_image_metadata,
  DROP CONSTRAINT ck_chat_session_attachment_name_nonblank,
  DROP CONSTRAINT nw_chat_session_attachments_source_media_id_fkey,
  DROP COLUMN content,
  ADD COLUMN body_format varchar(16),
  ADD COLUMN referenced_at timestamptz,
  ADD COLUMN reclaimed_at timestamptz,
  ALTER COLUMN name DROP NOT NULL;
ALTER TABLE public.nw_chat_session_attachments
  ADD CONSTRAINT fk_chat_session_attachment_body FOREIGN KEY(tenant_id,sha256,size,body_format)
    REFERENCES public.nw_chat_attachment_bodies(tenant_id,sha256,size,format),
  ADD CONSTRAINT ck_chat_session_attachment_body_lifecycle CHECK(
    (body_format IS NOT NULL AND body_format='bytea.v1' AND reclaimed_at IS NULL
      AND name IS NOT NULL AND length(btrim(name))>0)
    OR (body_format IS NULL AND reclaimed_at IS NOT NULL AND name IS NULL)),
  ADD CONSTRAINT ck_chat_session_image_metadata CHECK(
    (mime_type IS NULL AND pixel_width IS NULL AND pixel_height IS NULL AND media_transform IS NULL
      AND runtime_session_id IS NULL AND media_run_id IS NULL AND referenced_at IS NULL)
    OR (mime_type IS NOT NULL AND mime_type IN('image/png','image/jpeg','image/webp')
      AND pixel_width IS NOT NULL AND pixel_height IS NOT NULL AND pixel_width>0 AND pixel_height>0
      AND media_transform IS NOT NULL AND runtime_session_id IS NOT NULL)),
  ADD CONSTRAINT uq_chat_media_source_owner UNIQUE(tenant_id,runtime_session_id,id),
  ADD CONSTRAINT fk_chat_media_source_owner FOREIGN KEY(tenant_id,runtime_session_id,source_media_id)
    REFERENCES public.nw_chat_session_attachments(tenant_id,runtime_session_id,id);
CREATE INDEX idx_chat_attachment_body_references
  ON public.nw_chat_session_attachments(tenant_id,sha256,size,body_format) WHERE body_format IS NOT NULL;
CREATE INDEX idx_chat_attachment_expired_body
  ON public.nw_chat_session_attachments(tenant_id,expires_at,id) WHERE expires_at IS NOT NULL AND body_format IS NOT NULL;
CREATE INDEX idx_chat_media_active_children
  ON public.nw_chat_session_attachments(tenant_id,runtime_session_id,source_media_id) WHERE source_media_id IS NOT NULL;

COMMENT ON TABLE public.nw_chat_attachment_bodies IS '租户内附件及图片单一不可变二进制正文；元数据引用与容量核算、回收共用租户主行锁';
COMMENT ON COLUMN public.nw_chat_attachment_bodies.tenant_id IS '授权租户边界，不跨租户共享正文';
COMMENT ON COLUMN public.nw_chat_attachment_bodies.sha256 IS '原始二进制SHA-256；复用还须验证精确字节';
COMMENT ON COLUMN public.nw_chat_attachment_bodies.size IS '不可变原始字节长度';
COMMENT ON COLUMN public.nw_chat_attachment_bodies.format IS '精确存储格式bytea.v1，媒体MIME仍归附件元数据';
COMMENT ON COLUMN public.nw_chat_attachment_bodies.content IS '唯一权威正文；无附件引用时在租户锁内有界回收';
COMMENT ON COLUMN public.nw_chat_attachment_bodies.created_at IS '正文第一次注册时间，仅用于稳定回收顺序';
COMMENT ON COLUMN public.nw_chat_session_attachments.body_format IS '与tenant_id、sha256、size共同定位精确正文；NULL表示已回收墓碑';
COMMENT ON COLUMN public.nw_chat_session_attachments.referenced_at IS '媒体第一次读取或冻结意图，保护到所属会话删除；后续读取不刷新';
COMMENT ON COLUMN public.nw_chat_session_attachments.reclaimed_at IS '正文引用解除时间；元数据墓碑阻止UUID重新绑定';
COMMENT ON COLUMN public.nw_chat_session_attachments.name IS '有效附件安全展示名；回收后去除非必要展示字段';
COMMENT ON CONSTRAINT fk_chat_session_attachment_body ON public.nw_chat_session_attachments IS '精确正文引用同时绑定租户、摘要、长度、格式';
COMMENT ON CONSTRAINT fk_chat_media_source_owner ON public.nw_chat_session_attachments IS '媒体来源不能跨租户或逻辑Runtime会话';
