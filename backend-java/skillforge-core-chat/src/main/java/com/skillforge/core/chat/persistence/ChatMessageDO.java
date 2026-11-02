package com.skillforge.core.chat.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/**
 * 会话消息实体，对应数据库表 {@code chat_messages}。
 *
 * <p>会话全量消息逐行归档；Redis 热消息与该表写穿，历史恢复与
 * 上下文注入以本表为权威源。
 */
@Data
@TableName(value = "nw_chat_messages", autoResultMap = true)
public class ChatMessageDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 会话 ID，引用 sessions.session_id。 */
    private String sessionId;

    /** 消息所属 C 端用户 ID。 */
    private Long endUserId;

    /** 消息幂等 ID：用户消息沿用 client_message_id，助手消息服务端生成。 */
    private String messageId;

    /** 消息角色：user / assistant / system。 */
    private String role;

    /** 消息正文。 */
    private String content;

    /** 附加元数据 JSON：execution_id / plan_id / intent / card 等。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> metadata;

    /** 同一消息的单调公开版本，重复内容不会增加版本。 */
    private Long messageVersion;

    /** 结构化材料引用与正文独立保存，不包含临时访问票据。 */
    @TableField(value = "input_references", typeHandler = com.skillforge.core.chat.persistence.ChatReferencesTypeHandler.class)
    private java.util.List<com.skillforge.core.chat.contract.ChatReferenceView> references;

    /** 消息创建时间。 */
    private Instant createdAt;
}
