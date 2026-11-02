package com.skillforge.core.foundation.assets;

import java.time.OffsetDateTime;
import lombok.Data;

/** 公共文件持久化行；物理 key 只由文件领域生成和解析。 */
@Data
public class FoundationFileDO {
    /** 文件 UUID 身份。 */ private String id;
    /** 平台文件为 NULL；租户文件固定所属机构。 */ private Long tenantId;
    /** 创建主体，不能由请求指定。 */ private String createdBy;
    /** 经过校验的下载名称。 */ private String name;
    /** 内容识别 MIME。 */ private String mimeType;
    /** 原始字节数。 */ private long sizeBytes;
    /** SHA-256 小写十六进制摘要。 */ private String sha256;
    /** UUID 物理对象键，不含路径。 */ private String blobKey;
    /** ready、deleting、deleted，删除意图优先持久化。 */ private String state;
    /** upload 为通用上传，job_result 必须继续验证业务引用权威。 */ private String sourceKind;
    /** 当前引用投影。 */ private long referenceCount;
    /** 创建时刻。 */ private OffsetDateTime createdAt;
}
