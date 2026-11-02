package com.skillforge.core.foundation.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.foundation.contract.ControlOperationItem;
import com.skillforge.core.foundation.persistence.ControlOperationItemsTypeHandler;
import java.time.Instant;
import java.util.List;
import lombok.Data;

/** Open Platform 非会话控制操作受理账本。 */
@Data
@TableName(value = "nw_control_operations", autoResultMap = true)
public class ControlOperationDO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String operationId;
    private String rootOperationId;
    private String integrationId;
    /** 受理时冻结的入口种类。 */
    private String originKind;
    /** 已认证并持久保存的发起主体；系统任务使用真实任务责任引用。 */
    private String actorRef;
    /** 费用与业务责任主体；不从模型上下文补造。 */
    private String responsibilityRef;
    private String operationType;
    private String status;
    private String idempotencyKey;
    private String requestFingerprint;
    @TableField(typeHandler = ControlOperationItemsTypeHandler.class)
    private List<ControlOperationItem> items;
    private Instant acceptedAt;
    private Instant completedAt;
    private Instant updatedAt;
}
