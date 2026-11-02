package com.skillforge.core.chat.contract;

import java.util.Set;

/** 用户会话附件的统一容量与允许类型；执行产物使用成果模块自己的契约。 */
public final class ChatAttachmentLimits {
    public static final int MAX_FILE_BYTES = 50 * 1024 * 1024;
    public static final int MAX_FILES_PER_MESSAGE = com.skillforge.execution.ExecutionLimits.MAX_ATTACHMENT_INPUTS;
    public static final long MAX_MESSAGE_BYTES = 200L * 1024 * 1024;
    public static final int MAX_DOCUMENT_PAGES = 100;
    public static final long MAX_IMAGE_PIXELS = 40_000_000;
    public static final Set<String> EXTENSIONS = Set.of("csv", "tsv", "txt", "md", "json",
            "pdf", "docx", "pptx", "xlsx", "png", "jpg", "jpeg");
    private ChatAttachmentLimits() { }
}
