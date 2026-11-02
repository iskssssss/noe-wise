package com.skillforge.institution.media;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SessionMediaMapper {
    int insert(SessionMediaRow row);
    SessionMediaRow find(@Param("tenant") String tenant, @Param("session") String session,
            @Param("id") String id);
    StoredBytes readBytes(@Param("tenant") String tenant, @Param("session") String session, @Param("id") String id);
    SessionMediaRow findProduct(@Param("tenant") String tenant, @Param("productSession") String productSession,
            @Param("id") String id);
    int pin(@Param("tenant") String tenant, @Param("session") String session, @Param("id") String id);

    /** 无锁、有界扫描原始上传；年龄只是候选，真正退役仍由完整门禁核对。 */
    java.util.List<UnusedUpload> unusedUploadPage(@Param("tenant") String tenant,
            @Param("after") String after, @Param("limit") int limit);

    /** 锁并核对精确候选的 Runtime owner 闭包；不表示整个 owner 已退役。 */
    boolean unusedMediaOwnerGate(@Param("tenant") String tenant, @Param("productSession") String productSession,
            @Param("runtimeSession") String runtimeSession, @Param("rootId") String rootId);

    /** 调用前已锁 owner、tenant；锁至多 limit 个后代，用额外一行识别超界闭包。 */
    java.util.List<UnusedMediaRow> lockUnusedUploadClosure(@Param("tenant") String tenant,
            @Param("runtimeSession") String runtimeSession, @Param("rootId") String rootId, @Param("limit") int limit);

    /** 只解除已锁、已证明可退役的完整未用上传闭包，保留精确 UUID 与元数据墓碑。 */
    int releaseUnusedUploadClosure(@Param("tenant") String tenant, @Param("productSession") String productSession,
            @Param("runtimeSession") String runtimeSession, @Param("rootId") String rootId,
            @Param("ids") java.util.List<String> ids);

    /**
     * 调度游标仅携带稳定候选身份，不能用展示 RETIRED 推断完整 owner 退役。
     * @param id 原始上传媒体 UUID
     * @param productSessionId 正文生命周期所属产品会话
     * @param runtimeSessionId 媒体精确逻辑归属，不能借产品会话扩大 Runtime 读域
     */
    record UnusedUpload(String id, String productSessionId, String runtimeSessionId) { }

    /**
     * 已锁媒体行的实际保护字段；NULL bodyFormat 为永久防重绑定墓碑。
     * @param id 原媒体 UUID，退役后不重新绑定
     * @param productSessionId 产品正文归属
     * @param runtimeSessionId 精确 Runtime 媒体归属
     * @param runId 工具产出的可信 Run 绑定，非空即保护
     * @param sourceId 不可变来源图，原始上传为空
     * @param transform 原图或规范化版本，其他有效派生保守保护
     * @param bodyFormat 正文复合 FK 格式，NULL 说明引用已解除
     * @param referencedAt 第一次读取/冻结的单调保护，非空不能退回 unused
     */
    record UnusedMediaRow(String id, String productSessionId, String runtimeSessionId, String runId,
            String sourceId, String transform, String bodyFormat, java.time.Instant referencedAt) { }
    record StoredBytes(byte[] content) { }
}
