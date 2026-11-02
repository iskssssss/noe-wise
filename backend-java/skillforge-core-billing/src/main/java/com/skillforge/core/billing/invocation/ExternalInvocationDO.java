package com.skillforge.core.billing.invocation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/** 外部调用尝试账本；保存可审计边界事实，不保存请求/响应载荷或凭证。 */
@Data
@TableName(value = "nw_external_invocations", autoResultMap = true)
public class ExternalInvocationDO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String invocationId;
    private String attemptId;
    private String operationType;
    private String provider;
    private String service;
    private String idempotencyKey;
    private String status;
    private String inputId;
    private String rootInputId;
    private String operationId;
    private String rootOperationId;
    private String traceId;
    private String spanId;
    private String sourceEventId;
    private String causedByEventId;
    private String providerRequestId;
    private String errorCode;
    private Instant startedAt;
    private Instant endedAt;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> metadata;

    private Instant createdAt;
    private Instant updatedAt;
}
