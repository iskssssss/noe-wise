package com.skillforge.core.knowledge.model;
import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import lombok.Data;
/** 知识库重建的持久事实，运行写权由 job fence 决定。 */
@Data @TableName(value="nw_knowledge_rebuild_jobs", autoResultMap=true)
public class KnowledgeRebuildJobDO {
@TableId(type=IdType.INPUT) private Long id; private Long tenantId; private Long kbId;
private String idempotencyKey; private String fingerprint; private String status;
/** 受理时由服务端 MeterScope 冻结的类型化因果来源；NULL 为没有对应来源，后台禁止补造。 */
private String meteringRequestId; private String meteringRootInputId; private String meteringSessionId;
private String meteringRunId; private String meteringScopeType; private String meteringScopeId;
private Long meteringEndUserId; private Long meteringAgentId; private String meteringChannelId;
private Long revision; private Long fence; @TableField(updateStrategy=FieldStrategy.ALWAYS) private Instant leaseUntil;
private Long targetModelId; private Long targetModelRevision; private Integer targetDimensions;
private Long originalActiveRevisionId; private Long originalModelId; private Long originalModelRevision;
@TableField(updateStrategy=FieldStrategy.ALWAYS) private String errorCode; private Long publishedRevision; private Instant createdAt; private Instant updatedAt;
}
