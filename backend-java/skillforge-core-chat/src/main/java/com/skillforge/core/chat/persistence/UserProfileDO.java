package com.skillforge.core.chat.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import com.skillforge.core.chat.persistence.ProfileSummariesTypeHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * C 端用户中期画像实体，对应数据库表 {@code user_profiles}。
 *
 * <p>由异步画像摘要任务在会话空闲后 upsert，保存跨会话偏好与摘要，
 * 供后续会话上下文注入。
 */
@Data
@TableName(value = "nw_user_profiles", autoResultMap = true)
public class UserProfileDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** C 端用户 ID。 */
    private Long endUserId;

    /** 当前结构化画像 JSON。 */
    private String summary;

    /** 原始总结累积（jsonb 数组，条目 {content, created_at}），仅保留近期窗口，超阈值 LLM 合并进 summary。 */
    @TableField(typeHandler = ProfileSummariesTypeHandler.class)
    private List<ProfileSummary> summaries;

    /** 所有画像写入递增的并发版本，用于模型合并快照 CAS。 */
    private Long revision;

    /** 结构化画像字段 JSON，例如就诊偏好、既往诉求。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> structuredFields;

    /** 用户偏好 JSON，例如提醒方式。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> preferences;

    /** 累计交互次数。 */
    private Integer interactionCount;

    /** 最近一次会话时间。 */
    private Instant lastSessionAt;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;
}
