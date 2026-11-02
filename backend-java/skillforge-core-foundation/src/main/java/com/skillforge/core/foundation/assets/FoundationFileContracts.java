package com.skillforge.core.foundation.assets;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

/** 公共管理文件的公开投影；不暴露物理路径，不承担 Session 附件或成果权威。 */
public final class FoundationFileContracts {
    private FoundationFileContracts() { }
    /** @param id 文件身份 @param name 下载文件名 @param mimeType 服务端识别类型 @param sizeBytes 字节数 @param sha256 内容摘要 @param referenceCount 活跃引用数 @param createdAt 上传时间 */
    public record FileView(String id, String name, @JsonProperty("mime_type") String mimeType,
            @JsonProperty("size_bytes") long sizeBytes, String sha256,
            @JsonProperty("reference_count") long referenceCount,
            @JsonProperty("created_at") OffsetDateTime createdAt) { }
    /** @param items 当前页 @param total 总数 @param page 页号 @param pageSize 页大小 */
    public record Page(List<FileView> items, long total, int page, @JsonProperty("page_size") int pageSize) {
        public Page { items = List.copyOf(items); }
    }
    /** 受控下载材料，流只在已经重新授权后交给宿主响应。 @param file 元数据 @param bytes 精确原始内容 */
    public record Download(FileView file, byte[] bytes) { public Download { bytes = bytes.clone(); } }
    /** 引用键由业务所有者产生；ownerKind 是固定代码，ownerId 是稳定资源身份。 */
    public record Reference(String ownerKind, String ownerId) { }
}
