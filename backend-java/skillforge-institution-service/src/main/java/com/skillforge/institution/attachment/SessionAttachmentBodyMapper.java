package com.skillforge.institution.attachment;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 正文只按租户、摘要、长度和存储格式寻址；调用前必须持有租户主行锁。 */
@Mapper
public interface SessionAttachmentBodyMapper {
    String lockTenant(@Param("tenant") String tenant);
    List<String> tenantPage(@Param("after") String after, @Param("limit") int limit);
    StoredBody find(@Param("tenant") String tenant, @Param("sha256") String sha256, @Param("size") long size);
    int insert(@Param("tenant") String tenant, @Param("sha256") String sha256,
            @Param("size") long size, @Param("bytes") byte[] bytes);
    Capacity capacity(@Param("tenant") String tenant, @Param("session") String session,
            @Param("sha256") String sha256, @Param("size") long size);
    long sessionBytes(@Param("tenant") String tenant, @Param("session") String session);
    long tenantBytes(@Param("tenant") String tenant);
    int reclaimUnreferenced(@Param("tenant") String tenant, @Param("limit") int limit);
    Backlog backlog(@Param("tenant") String tenant, @Param("limit") int limit);

    /** 精确正文验证使用原始字节，摘要一致不能授权跨租户读写。 */
    record StoredBody(byte[] content) { }
    /** 会话计量同一正文一次；租户计量尚未物理回收的正文，避免低估占用。 */
    record Capacity(long sessionBytes, long tenantBytes, boolean inSession) { }
    /** 观察最多 limit 条到期正文；saturated 表示仍须继续处理，不能冒充完整总量。 */
    record Backlog(long sampledRows, long sampledBytes, Instant oldestExpiry, boolean saturated) { }
}
