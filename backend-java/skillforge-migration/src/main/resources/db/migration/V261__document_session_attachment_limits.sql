-- 会话附件承载文本、文档和原始图片；模型媒体仍使用已批准的独立元数据与生命周期。
ALTER TABLE public.nw_chat_session_attachments
    DROP CONSTRAINT ck_chat_session_attachment_size,
    DROP CONSTRAINT ck_chat_session_attachment_input_path;

ALTER TABLE public.nw_chat_session_attachments
    ADD CONSTRAINT ck_chat_session_attachment_size CHECK (size BETWEEN 0 AND 52428800),
    ADD CONSTRAINT ck_chat_session_attachment_input_path CHECK (
        (mime_type IS NULL AND input_path ~
            ('^attachments/' || id::text || '/input\.(csv|tsv|txt|md|json|pdf|docx|pptx|xlsx|png|jpg|jpeg)$'))
        OR (mime_type IS NOT NULL AND input_path ~
            ('^attachments/' || id::text || '/input\.(png|jpeg|webp)$'))
    );

COMMENT ON COLUMN public.nw_chat_session_attachments.mime_type IS
    '模型图片的实际解码类型；NULL 表示独立会话输入附件，可为文本、PDF、Office或原始图片';
COMMENT ON COLUMN public.nw_chat_session_attachments.content IS
    '附件或模型图片的原始二进制；会话输入附件到期清空正文并保留UUID，模型图片随会话保留';
COMMENT ON COLUMN public.nw_chat_session_attachments.expires_at IS
    '会话输入附件最长七天；模型图片为NULL并随会话保留';
COMMENT ON COLUMN public.nw_chat_session_attachments.size IS
    '原始二进制字节数，单文件上限50MiB；与ChatAttachmentLimits保持一致';
