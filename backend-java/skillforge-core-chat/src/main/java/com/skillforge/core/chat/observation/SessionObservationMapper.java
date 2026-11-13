package com.skillforge.core.chat.observation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话观测的只读事实查询，业务授权由观测服务入口完成。
 *
 * <p>参数映射仅由本领域 Repository 构造，不接受外部 SQL 或任意列名。数据库异常由
 * MyBatis/Spring 翻译并向事务边界传播；方法执行日志由宿主 Mapper 拦截器统一记录。
 */
@Mapper
public interface SessionObservationMapper {
    /** 读取数据库当前时刻作为观测时间；每次执行重新读取，避免事务内缓存旧时刻。 */
    Instant readAt();
    /**
     * 读取会话、终端用户、智能体、团队和渠道的筛选候选。
     * @param parameters scopeTenantId、q 可空
     * @return 每行包含 kind、value、label、description
     */
    List<Map<String, Object>> filterOptions(Map<String, Object> parameters);
    /**
     * 按授权非状态过滤条件读取会话候选，公开归并之后统一分页。
     * @param parameters scopeTenantId、tenantId、sessionId、userId、agentId、teamId、channel、summary、startedFrom、startedTo 可空
     * @return 会话列映射列表，每行包含 total_count；无匹配返回空列表
     */
    List<Map<String, Object>> list(Map<String, Object> parameters);
    /**
     * 读取明确租户与会话的只读投影，不取得写锁。
     * @param parameters tenantId、sessionId 必填
     * @return 零或一行会话投影
     */
    List<Map<String, Object>> find(Map<String, Object> parameters);
    /**
     * 在冻结序列上界内分页读取根输入。
     * @param parameters tenantId、sessionId、upperBound、limit 必填；targetRootInputId、beforeSequence 可空
     * @return 根输入身份、序列和时间的列映射
     */
    List<Map<String, Object>> rounds(Map<String, Object> parameters);
    /**
     * 读取会话已提交事件序列上界。
     * @param parameters tenantId、sessionId 必填
     * @return 最大序列；无事件返回 null
     */
    Long sessionSequenceUpperBound(Map<String, Object> parameters);
    /**
     * 批量读取根输入关联的 Run。
     * @param parameters tenantId、sessionId、非空 roots 集合
     * @return Run 身份、状态和精确 epoch second/nano 列
     */
    List<Map<String, Object>> runs(Map<String, Object> parameters);
    /**
     * 读取指定 Run 的冻结准备和操作身份。
     * @param parameters tenantId、非空 runIds 集合
     * @return 来源类型、Run、循环和操作身份；可空身份保持 null
     */
    List<Map<String, Object>> frozenKeys(Map<String, Object> parameters);
    /** 批量读取已授权 scopes 内的公开事件与原 model-result 有限证明，保留真实身份。 */
    List<Map<String, Object>> publicFacts(Map<String, Object> parameters);
    /** 批量读取 scopes 的高水位内原消息；分页由两来源公开归并后执行。 */
    List<Map<String, Object>> messages(Map<String, Object> parameters);
    /** 捕获单会话原消息ID高水位；无消息返回 null。 */
    Long messageUpperBound(Map<String, Object> parameters);
    /**
     * 验证根输入的租户与会话归属。
     * @param parameters tenantId、sessionId、rootInputId 必填
     * @return 是否存在匹配事件
     */
    Boolean rootExists(Map<String, Object> parameters);
    /**
     * 读取指定根输入的事件上界。
     * @param parameters tenantId、sessionId、rootInputId 必填
     * @return 最大序列；无事件返回 null
     */
    Long traceUpperBound(Map<String, Object> parameters);
    /**
     * 在冻结上界内读取事件节点序列。
     * @param parameters tenantId、sessionId、rootInputId、nodeId、upperBound 必填
     * @return 零或一项事件序列
     */
    List<Long> traceNodeSequence(Map<String, Object> parameters);
    /**
     * 按序读取冻结上界内的轨迹与父节点关联。
     * @param parameters tenantId、sessionId、rootInputId、afterSequence、upperBound、limit 必填
     * @return 轨迹列映射，保留精确 epoch second/nano
     */
    List<Map<String, Object>> trace(Map<String, Object> parameters);
    /**
     * 批量读取根输入事件并应用可选上界。
     * @param parameters tenantId、sessionId、非空 roots 必填；sequenceUpperBound 可空
     * @return 按事件序列排列的事件列映射
     */
    List<Map<String, Object>> roundEvents(Map<String, Object> parameters);
    /**
     * 统计内容事件与明确根输入的匹配数。
     * @param parameters tenantId、sessionId、rootInputId、eventId 必填
     * @return 匹配数；调用方据此判断归属
     */
    Integer contentBelongsToRoot(Map<String, Object> parameters);
    /**
     * 读取模型调用账本及有效计量，保留无计量的调用。
     * @param parameters tenantId、sessionId 必填；rootInputId、attemptUpperBound 可空
     * @return 调用与可空计量列映射
     */
    List<Map<String, Object>> usage(Map<String, Object> parameters);
}
