package com.skillforge.core.chat.dto.response;

import com.skillforge.core.chat.message.ChatMessageRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.skillforge.core.chat.contract.ProcessPresentation;

/**
 * 会话历史恢复响应。
 *
 * @param sessionId   会话 ID
 * @param messages    按创建时间和消息 ID 正序排列的当前页消息列表（metadata 不再承载 card，节点数据走运行时表）
 * @param pendingInteractions 会话存在挂起执行（waiting_hitl）时的交互卡恢复信息
 *                    （每项保留独立 execution_id 与等待版本），无挂起为空列表；刷新后据此还原
 *                    行动点菜单 / 确认等可点选交互卡（来自运行时任务表）
 * @param activities  该会话节点执行历史（跨执行聚合）：含卡数据与 message_id 关联，
 *                    前端按 message_id 精确还原历史卡渲染
 * @param processes   按 Session 持久顺序排列的聊天公开过程项
 * @param collaboration Plan 模式选择与当前 Todo 清单的会话投影
 * @param closed 当前会话已关闭，不接受新输入
 */
public record ChatHistoryResponse(
        String sessionId,
        List<ChatMessageRecord> messages,
        List<Map<String, Object>> pendingInteractions,
        List<ActivityItem> activities,
        List<ProcessItem> processes,
        Collaboration collaboration,
        boolean hasMore,
        String nextBeforeMessageId,
        String eventCursor,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        HistoryRetention historyRetention,
        @com.fasterxml.jackson.annotation.JsonProperty("work_plans") List<Map<String,Object>> workPlans,
        boolean closed) {

    public ChatHistoryResponse {
        workPlans = workPlans == null ? List.of() : List.copyOf(workPlans);
        pendingInteractions = pendingInteractions == null ? List.of() : List.copyOf(pendingInteractions);
        collaboration = Objects.requireNonNull(collaboration, "collaboration");
    }

    public ChatHistoryResponse(String sessionId,List<ChatMessageRecord> messages,List<Map<String,Object>> pendingInteractions,
            List<ActivityItem> activities,List<ProcessItem> processes,Collaboration collaboration,
            boolean hasMore,String nextBeforeMessageId,String eventCursor,HistoryRetention historyRetention) {
        this(sessionId,messages,pendingInteractions,activities,processes,collaboration,hasMore,nextBeforeMessageId,eventCursor,historyRetention,List.of(),false);
    }
    public ChatHistoryResponse withWorkPlans(List<Map<String,Object>> plans) {
        return new ChatHistoryResponse(sessionId,messages,pendingInteractions,activities,processes,collaboration,hasMore,nextBeforeMessageId,eventCursor,historyRetention,plans,closed);
    }

    /** 查询边界使用已授权会话的关闭事实，不从消息或 Run 终态推断。 */
    public ChatHistoryResponse withClosed(boolean value) {
        return new ChatHistoryResponse(sessionId,messages,pendingInteractions,activities,processes,collaboration,
                hasMore,nextBeforeMessageId,eventCursor,historyRetention,workPlans,value);
    }

    /** 非Runtime历史没有退休事实；分页等重建操作应显式保留已有historyRetention。 */
    public ChatHistoryResponse(String sessionId,List<ChatMessageRecord> messages,List<Map<String,Object>> pendingInteractions,
            List<ActivityItem> activities,List<ProcessItem> processes,Collaboration collaboration,
            boolean hasMore,String nextBeforeMessageId,String eventCursor) {
        this(sessionId,messages,pendingInteractions,activities,processes,collaboration,hasMore,nextBeforeMessageId,eventCursor,null);
    }

    /** 将明确历史退休状态附加到原公开投影，不制造完整推理或改写任何原消息版本。 */
    public ChatHistoryResponse withHistoryRetention(HistoryRetention value) {
        return new ChatHistoryResponse(sessionId,messages,pendingInteractions,activities,processes,collaboration,
                hasMore,nextBeforeMessageId,eventCursor,value,workPlans,closed);
    }

    /** 普通展示历史退休的公开状态；完整恢复、幂等、采用及最终输出仍按原事实提供。 */
    public record HistoryRetention(long sourceSessionRevision, Instant closedAt, long retiredThroughSequence,
            long retiredEventCount, List<String> retiredEventTypes, boolean complete) {
        public HistoryRetention { retiredEventTypes = List.copyOf(retiredEventTypes); }
    }

    /** Runtime 内部投影携带协作快照，由聊天查询边界原样保留。 */
    public ChatHistoryResponse(String sessionId,List<ChatMessageRecord> messages,List<Map<String,Object>> pendingInteractions,
                               List<ActivityItem> activities,List<ProcessItem> processes,
                               Collaboration collaboration) {
        this(sessionId,messages,pendingInteractions,activities,processes,collaboration,false,null,null);
    }

    public ChatHistoryResponse(String sessionId,List<ChatMessageRecord> messages,List<Map<String,Object>> pendingInteractions,
            List<ActivityItem> activities,List<ProcessItem> processes,Collaboration collaboration,
            boolean hasMore,String nextBeforeMessageId) {
        this(sessionId,messages,pendingInteractions,activities,processes,collaboration,hasMore,nextBeforeMessageId,null);
    }

    /** 会话级协作快照；pending 表示 selectedActive 将在下一次已接受准备时生效。 */
    public record Collaboration(boolean active, boolean pending, boolean selectedActive) {
        public static Collaboration empty() { return new Collaboration(false, false, false); }
    }

    /**
     * 已授权会话内可恢复的过程项；程序标识与业务呈现具有明确类型边界。
     */
    public record ProcessItem(
            String processId,
            String runId,
            String inputId,
            String referenceId,
            String type,
            long sequence,
            String status,
            ProcessPresentation presentation,
            Instant startedAt,
            Instant finishedAt,
            String rootRunId,
            String rootInputId,
            int ordinal,
            long updatedSequence,
            String actorAgentId,
            String actorName,
            ProcessStage stage) {
    }

    /** 阶段来自冻结准备身份，id 为不透明分组键，index 为实际迭代序号。 */
    public record ProcessStage(String id, int index) { }

    /**
     * 节点活动视图。
     *
     * @param nodeId    节点 ID
     * @param nodeType  节点类型
     * @param status    执行状态
     * @param card      节点卡数据（card 节点展示卡），可空
     * @param render    节点自定义渲染数据（渲染契约：插值后原样透传，客户端自渲染），可空
     * @param messageId 卡消息关联，可空
     * @param startedAt 开始时间
     */
    public record ActivityItem(
            String nodeId,
            String nodeType,
            String status,
            Map<String, Object> card,
            Map<String, Object> render,
            String messageId,
            Instant startedAt) {
    }
}
