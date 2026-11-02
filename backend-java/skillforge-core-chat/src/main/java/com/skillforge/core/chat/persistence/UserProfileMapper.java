package com.skillforge.core.chat.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.chat.persistence.UserProfileDO;
import com.skillforge.core.chat.persistence.ProfileSummary;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 用户画像 MyBatis-Plus 映射接口。
 *
 * <p>除常规 CRUD 外，提供基于唯一键 (tenant_id, end_user_id) 的
 * upsert，避免会话摘要任务并发写入重复行。
 */
public interface UserProfileMapper extends BaseMapper<UserProfileDO> {

    /** 一条 SQL 仲裁首次插入、追加有界窗口、累加计数并返回已提交快照。 */
    UserProfileDO appendRawSummary(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("endUserId") Long endUserId, @Param("initialSummary") String initialSummary,
            @Param("entries") List<ProfileSummary> entries, @Param("max") int max,
            @Param("lastSessionAt") Instant lastSessionAt);

    /** 模型结果只写回原快照，冲突时保持新窗口且不再调用模型。 */
    int mergeSummaryIfRevision(@Param("tenantId") Long tenantId, @Param("endUserId") Long endUserId,
            @Param("revision") long revision, @Param("summary") String summary);

    /**
     * 按租户 + 用户 upsert 画像摘要。
     *
     * @param id               主键 Long
     * @param tenantId         租户 ID
     * @param endUserId        用户 ID
     * @param summary          结构化画像 JSON
     * @param structuredFields 结构化字段 JSON
     * @param preferences      偏好 JSON
     * @param interactionDelta 本次累加的交互次数
     * @param lastSessionAt    最近会话时间
     * @return 影响行数
     */
    int upsert(
            @Param("id") Long id,
            @Param("tenantId") Long tenantId,
            @Param("endUserId") Long endUserId,
            @Param("summary") String summary,
            @Param("structuredFields") Map<String, Object> structuredFields,
            @Param("preferences") Map<String, Object> preferences,
            @Param("interactionDelta") int interactionDelta,
            @Param("lastSessionAt") Instant lastSessionAt);
}
