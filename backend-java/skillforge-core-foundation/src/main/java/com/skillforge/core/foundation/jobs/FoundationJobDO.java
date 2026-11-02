package com.skillforge.core.foundation.jobs;

import java.time.OffsetDateTime;
import lombok.Data;

/** 管理任务持久化状态；租约所有者拥有进度与终态写权。 */
@Data
public class FoundationJobDO {
    /** UUID 任务身份。 */ private String id;
    /** NULL 为独立平台作用域。 */ private Long tenantId;
    /** 可信受理主体。 */ private String memberId;
    /** 代码注册处理器。 */ private String handler;
    /** import 或 export。 */ private String operation;
    /** 不可变输入文件。 */ private String inputFileId;
    /** 已校验领域选项的 JSON 文本。 */ private String optionsJson;
    /** 同作用域受理幂等键。 */ private String idempotencyKey;
    /** 请求完整摘要，防止同键异内容。 */ private String requestDigest;
    /** 任务状态。 */ private String state;
    /** 数据行总量。 */ private int totalRows;
    /** 成功或失败的已处理行数。 */ private int completedRows;
    /** 失败行数。 */ private int errorRows;
    /** 可公开失败原因。 */ private String errorMessage;
    /** 受控结果文件。 */ private String resultFileId;
    /** 当前租约 UUID。 */ private String leaseOwner;
    /** 租约有效截止。 */ private OffsetDateTime leaseUntil;
    /** 受理时间。 */ private OffsetDateTime createdAt;
    /** 终态保留截止，运行中为 NULL。 */ private OffsetDateTime expiresAt;
}
