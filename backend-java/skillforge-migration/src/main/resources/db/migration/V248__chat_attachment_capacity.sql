-- 仅调整用户附件的目标容量，不改写已有正文与身份。
ALTER TABLE public.nw_chat_session_attachments DROP CONSTRAINT ck_chat_session_attachment_size;
ALTER TABLE public.nw_chat_session_attachments ADD CONSTRAINT ck_chat_session_attachment_size
    CHECK (size BETWEEN 0 AND 52428800);
COMMENT ON COLUMN public.nw_chat_session_attachments.size IS '正文 UTF-8 字节数，单文件上限 50 MiB';
COMMENT ON CONSTRAINT ck_chat_session_attachment_size ON public.nw_chat_session_attachments IS '附件正文单文件上限 50 MiB；每条消息 16 个及合计 200 MiB 由受理服务核验';
