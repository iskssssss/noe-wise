package com.skillforge.core.chat.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.chat.persistence.ChatMessageDO;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 会话消息 MyBatis-Plus 映射接口。 */
public interface ChatMessageMapper extends BaseMapper<ChatMessageDO> {

    /** 在调用方借用的受理事务中归档用户消息；重复输入由受理幂等边界处理。 */
    int insertArchived(ChatMessageDO row);

    /**
     * 在租户、会话及输入身份匹配时一次性冻结用户消息引用，不覆盖已经冻结的引用。
     * @param tenantId 已授权租户 ID
     * @param sessionId 当前会话身份
     * @param inputId 当前用户输入身份
     * @param references 已校验的类型化引用，编码由专属 TypeHandler 完成
     * @return 影响行数；无匹配、已冻结或引用未变化时为零
     */
    int freezeInputReferences(@Param("tenantId") long tenantId,@Param("sessionId") String sessionId,
            @Param("inputId") String inputId,
            @Param("references") java.util.List<com.skillforge.core.chat.contract.ChatReferenceView> references);

    /**
     * 查询一次工作流执行的全部消息（metadata.execution_id 匹配，docs/02-架构与设计/07-记忆管理.md §2.1）。
     *
     * @param tenantId    租户 ID
     * @param executionId 执行 ID
     * @return 消息列表（按创建时间正序）
     */
    List<ChatMessageDO> selectByExecutionId(
            @Param("tenantId") Long tenantId,
            @Param("executionId") String executionId);

    /**
     * 卡消息同 id merge（同 (tenant_id, message_id) 覆盖 content/metadata，
     * created_at 保留首次时间保证历史时序；SQL 见 ChatMessageMapper.xml）。
     *
     * @param row 消息行（messageId 决定覆盖目标）
     * @return 影响行数（1 新增 / 1 覆盖 / 0 冲突未变化）
     */
    int upsertById(ChatMessageDO row);
}
