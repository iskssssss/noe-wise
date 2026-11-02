package com.skillforge.institution.artifact;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 成果冻结完成意图及其发布快照的持久化边界。 */
@Mapper
public interface DeliverableMapper {
    /** 判断运行是否存在待结算的完成意图。 */
    boolean completionPending(@Param("tenant") String tenant,
            @Param("run") String run);
    /** 读取冻结完成意图；lock 为 true 时参与当前事务行锁。 */
    Map<String,Object> completionPlan(@Param("tenant") String tenant, @Param("run") String run,
            @Param("lock") boolean lock);
    /** 读取任务发布状态；没有结果时返回 null。 */
    Map<String,Object> publication(@Param("tenant") String tenant,
            @Param("run") String run);
    /** 按交付顺序读取结果关联版本。 */
    List<String> revisionIds(@Param("tenant") String tenant,
            @Param("run") String run);
    /** 有界读取已提交终态且待发布的原Run引用；完整身份由正式ExecutionStore恢复。 */
    List<String> pendingRuns(@Param("tenant") String tenant, @Param("limit") int limit);
    /** 原子冻结交付物和完成意图，返回更新行数。 */
    int freeze(@Param("tenant") String tenant, @Param("run") String run,
            @Param("frozen") String frozen, @Param("hash") String hash,
            @Param("completionState") String completionState);
    /** 记录已结算或被替代的完成意图；与释放 holds 同事务。 */
    int markCompletion(@Param("tenant") String tenant, @Param("run") String run,
            @Param("state") String state);
    /** 按完成意图状态释放文件保留，防止待结算来源提前回收。 */
    int releaseHolds(@Param("tenant") String tenant, @Param("run") String run,
            @Param("includePending") boolean includePending);

    /**
     * 当前正式成果及其发布时冻结的计划；不包含候选文件标识或文件正文。
     * @param key 会话内逻辑键
     * @param title 当前版本标题
     * @param kind 当前成果类型
     * @param baseRevisionId 当前正式版本，改版必须精确引用
     * @param plan 发布来源 Run 的冻结计划 JSON，用于核验格式来源
     */
    record CurrentPlanRow(String key, String title, String kind, String baseRevisionId, String plan) { }

    /** 单次查询当前会话已发布版本与格式来源，不跨租户、会话继承版本。 */
    List<CurrentPlanRow> currentPlans(@Param("tenant") long tenant, @Param("session") String session);
    /** 在最终冻结事务内插入完整交付清单和身份快照；不作为候选生成前置。 */
    int insertPlan(@Param("tenant") long tenant,
            @Param("runId") String runId,
            @Param("session") String session,
            @Param("sessionId") String sessionId,
            @Param("runIdentity") String runIdentity,
            @Param("payload") String payload);
    /** 记录用户放弃原因；与失败结果同事务。 */
    int abandonPlan(@Param("abandonReason") String abandonReason,
            @Param("tenant") long tenant,
            @Param("runId") String runId);
    /** 登记主动放弃的失败任务结果。 */
    int insertFailedResult(@Param("tenant") long tenant,
            @Param("runId") String runId,
            @Param("sessionId") String sessionId,
            @Param("abandonReason") String abandonReason);
    /** 登记已冻结且等待发布的任务结果。 */
    int insertPendingResult(@Param("tenant") long tenant,
            @Param("runId") String runId,
            @Param("sessionId") String sessionId);
    /** 按租户、会话和逻辑键幂等创建成果占位。 */
    int ensureDeliverable(@Param("tenant") long tenant,
            @Param("id") String id,
            @Param("session") String session,
            @Param("key") String key);
    /** 锁定逻辑成果，用于竞争预留；不存在返回 null。 */
    Map<String,Object> lockDeliverableKey(@Param("tenant") long tenant,
            @Param("session") String session,
            @Param("key") String key);
    /** 在已持有行锁的事务内设置运行预留。 */
    int reserveDeliverable(@Param("runId") String runId,
            @Param("tenant") long tenant,
            @Param("id") String id);
    /** 按文件身份加锁，防止验证和发布之间被回收；不存在返回 null。 */
    Map<String,Object> lockFileId(@Param("tenant") long tenant,
            @Param("fileId") String fileId);
    /** 按 artifact 和相对路径加锁读取文件；不存在返回 null。 */
    Map<String,Object> lockFilePath(@Param("tenant") long tenant,
            @Param("artifactId") String artifactId,
            @Param("path") String path);
    /** 幂等保留任务引用文件，等待发布或结算后释放。 */
    int holdFile(@Param("tenant") long tenant,
            @Param("runId") String runId,
            @Param("fileId") String fileId);
    /** 依据真实 Run 终态登记失败或取消结果。 */
    int upsertTerminalResult(@Param("tenant") long tenant,
            @Param("runId") String runId,
            @Param("sessionId") String sessionId,
            @Param("state") String state,
            @Param("reason") String reason);
    /** 真实 Run 未成功完成时关闭未发布计划。 */
    int abandonTerminalPlan(@Param("tenant") long tenant,
            @Param("runId") String runId);
    /** 登记最终文件校验失败，不发布不完整结果。 */
    int failValidationResult(@Param("tenant") long tenant,
            @Param("runId") String runId);
    /** 将文件校验失败原因写入计划，与结果和释放同事务。 */
    int abandonValidationPlan(@Param("tenant") long tenant,
            @Param("runId") String runId);
    /** 锁定并检查当前 revision 与运行预留，发现竞争时失败关闭。 */
    Map<String,Object> lockReservation(@Param("tenant") long tenant,
            @Param("deliverableId") String deliverableId);
    /** 锁定成果并读取当前版本，用于原子发布。 */
    Map<String,Object> lockDeliverableId(@Param("tenant") long tenant,
            @Param("deliverableId") String deliverableId);
    /** 读取已指定基线版本以验证内容指纹；不存在返回 null。 */
    Map<String,Object> revision(@Param("tenant") long tenant,
            @Param("baseRevisionId") String baseRevisionId);
    /** 记录不可变成果版本及前驱版本。 */
    int insertRevision(@Param("tenant") long tenant,
            @Param("revision") String revision,
            @Param("deliverableId") String deliverableId,
            @Param("number") int number,
            @Param("runId") String runId,
            @Param("baseRevisionId") String baseRevisionId,
            @Param("title") String title,
            @Param("kind") String kind,
            @Param("fingerprint") String fingerprint,
            @Param("changeType") String changeType);
    /** 登记版本的格式表示与预览入口。 */
    int insertRepresentation(@Param("tenant") long tenant,
            @Param("id") String id,
            @Param("revision") String revision,
            @Param("role") String role,
            @Param("format") String format,
            @Param("primaryPreview") boolean primaryPreview,
            @Param("entryPath") String entryPath,
            @Param("sourceHash") String sourceHash);
    /** 按明确顺序记录格式表示中的文件及下载名称。 */
    int insertRepresentationFile(@Param("tenant") long tenant,
            @Param("id") String id,
            @Param("fileId") String fileId,
            @Param("path") String path,
            @Param("downloadName") String downloadName,
            @Param("index") int index);
    /** 登记表示所依据的源文件哈希，保留真实来源。 */
    int insertRepresentationSource(@Param("tenant") long tenant,
            @Param("id") String id,
            @Param("fileId") String fileId,
            @Param("sha256") String sha256);
    /** 在成果行锁下推进当前版本，与版本写入原子提交。 */
    int advanceRevision(@Param("revision") String revision,
            @Param("tenant") long tenant,
            @Param("deliverableId") String deliverableId);
    /** 按交付顺序关联结果与成果版本。 */
    int linkResultRevision(@Param("tenant") long tenant,
            @Param("runId") String runId,
            @Param("revision") String revision,
            @Param("ordinal") int ordinal);
    /** 保存任务证据引用及摘要，不重写源文件。 */
    int insertEvidence(@Param("tenant") long tenant,
            @Param("id") String id,
            @Param("runId") String runId,
            @Param("fileId") String fileId,
            @Param("summary") String summary);
    /** 最终标记结果已发布，调用失败必须回滚全部版本写入。 */
    int publishResult(@Param("tenant") long tenant,
            @Param("runId") String runId);
    /** 在成功发布事务内结束计划。 */
    int finalizePlan(@Param("tenant") long tenant,
            @Param("runId") String runId);
    /** 批量读取当前会话成果及文件，不产生逐行读取。 */
    List<Map<String,Object>> currentOutputs(@Param("tenant") long tenant,
            @Param("sessionId") String sessionId);
    /** 记录发布失败原因，与计划放弃和预留释放原子提交。 */
    int failResult(@Param("reason") String reason,
            @Param("tenant") long tenant,
            @Param("runId") String runId);
    /** 记录发布拒绝原因并关闭计划。 */
    int abandonFailedPlan(@Param("reason") String reason,
            @Param("tenant") long tenant,
            @Param("runId") String runId);
    /** 释放指定运行持有的全部成果预留。 */
    int releaseReservations(@Param("tenant") long tenant,
            @Param("runId") String runId);
    /** 读取运行计划与冻结载荷；lock 控制是否在当前事务锁定。 */
    List<Map<String,Object>> planRows(@Param("tenant") long tenant,
            @Param("runId") String runId,
            @Param("lock") boolean lock);
    /** 按稳定顺序批量读取会话全部成果版本。 */
    List<Map<String,Object>> readRevisions(@Param("tenant") long tenant,
            @Param("session") String session);
    /** 批量读取会话版本文件，按表示及文件顺序排序。 */
    List<Map<String,Object>> readFiles(@Param("tenant") long tenant,
            @Param("session") String session);
    /** 只读取已发布任务对版本的关联。 */
    List<Map<String,Object>> readResultLinks(@Param("tenant") long tenant,
            @Param("session") String session);
    /** 只读取已发布且尚未过期的任务证据。 */
    List<Map<String,Object>> readEvidence(@Param("tenant") long tenant,
            @Param("session") String session);
    /** 查询指定版本所属会话，供权限入口再次校验。 */
    List<Map<String,Object>> revisionSessions(@Param("tenant") long tenant,
            @Param("revision") String revision);
    /** 读取精确版本文件关系，禁止跨版本拼接文件标识。 */
    List<Map<String,Object>> revisionFile(@Param("tenant") long tenant,
            @Param("revision") String revision,
            @Param("fileId") String fileId);
    /** 将表示内相对路径解析到持久文件身份。 */
    List<Map<String,Object>> representationPath(@Param("tenant") long tenant,
            @Param("representation") String representation,
            @Param("relativePath") String relativePath);
    /** 统计指定表示的文件数量，用于预览边界判断。 */
    Integer representationFileCount(@Param("tenant") long tenant,
            @Param("representation") String representation);
}
