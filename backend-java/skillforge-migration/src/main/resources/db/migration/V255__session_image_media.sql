-- 图片与文本数据附件共用二进制所有者；图片随会话保留以支持模型冻结请求恢复。
ALTER TABLE public.nw_chat_session_attachments
    ADD COLUMN mime_type varchar(32),
    ADD COLUMN pixel_width integer,
    ADD COLUMN pixel_height integer,
    ADD COLUMN source_media_id uuid REFERENCES public.nw_chat_session_attachments(id),
    ADD COLUMN media_transform text,
    ADD COLUMN runtime_session_id text,
    ADD COLUMN media_run_id text,
    ALTER COLUMN expires_at DROP NOT NULL;

ALTER TABLE public.nw_chat_session_attachments
    DROP CONSTRAINT ck_chat_session_attachment_input_path,
    DROP CONSTRAINT ck_chat_session_attachment_expiry;
ALTER TABLE public.nw_chat_session_attachments ADD CONSTRAINT ck_chat_session_attachment_input_path CHECK (
    input_path ~ ('^attachments/' || id::text || '/input\.(csv|tsv|txt|md|json|png|jpeg|webp)$')
);
ALTER TABLE public.nw_chat_session_attachments ADD CONSTRAINT ck_chat_session_attachment_expiry CHECK (
    (mime_type IS NULL AND expires_at IS NOT NULL AND expires_at > created_at
        AND expires_at <= created_at + interval '7 days')
    OR (mime_type IS NOT NULL AND expires_at IS NULL)
);
ALTER TABLE public.nw_chat_session_attachments ADD CONSTRAINT ck_chat_session_image_metadata CHECK (
    (mime_type IS NULL AND pixel_width IS NULL AND pixel_height IS NULL AND media_transform IS NULL AND runtime_session_id IS NULL AND media_run_id IS NULL)
    OR (mime_type IN ('image/png', 'image/jpeg', 'image/webp') AND pixel_width > 0 AND pixel_height > 0
        AND content IS NOT NULL AND media_transform IS NOT NULL AND runtime_session_id IS NOT NULL)
);
COMMENT ON COLUMN public.nw_chat_session_attachments.mime_type IS '实际解码确认的图片类型；NULL 表示文本数据附件';
COMMENT ON COLUMN public.nw_chat_session_attachments.pixel_width IS '实际图片像素宽度';
COMMENT ON COLUMN public.nw_chat_session_attachments.pixel_height IS '实际图片像素高度';
COMMENT ON COLUMN public.nw_chat_session_attachments.source_media_id IS '规范化、裁剪或请求变体对应的不可变来源图片';
COMMENT ON COLUMN public.nw_chat_session_attachments.media_transform IS '图片产生方式与版本，原始上传为 source-v1';
COMMENT ON COLUMN public.nw_chat_session_attachments.content IS '附件或图片的实际二进制；图片随会话保留，文本附件到期清空';
COMMENT ON COLUMN public.nw_chat_session_attachments.expires_at IS '文本附件最长七天；图片为 NULL 并随会话保留';
COMMENT ON COLUMN public.nw_chat_session_attachments.size IS '实际二进制字节数，单文件上限 50 MiB';

COMMENT ON COLUMN public.nw_chat_session_attachments.runtime_session_id IS '图片逻辑归属；可为委派子会话，session_id仍承担产品会话生命周期';
COMMENT ON COLUMN public.nw_chat_session_attachments.media_run_id IS '工具图片对应的不可变Run绑定，用于产品展示重新授权';
