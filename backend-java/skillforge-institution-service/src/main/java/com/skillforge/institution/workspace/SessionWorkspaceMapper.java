package com.skillforge.institution.workspace;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 宿主只持久工作区归属和清理意图；文件、修订及备份事实由 Sandbox 拥有。 */
@Mapper
public interface SessionWorkspaceMapper {
    int insert(Row row);
    Row find(@Param("tenant") String tenant, @Param("session") String session);
    List<Row> list(@Param("tenant") String tenant, @Param("productSession") String productSession);
    boolean productActive(@Param("tenant") String tenant, @Param("productSession") String productSession,
                          @Param("endUser") long endUser);
    int markDeletedProducts(@Param("tenant") String tenant);
    List<Row> pendingCleanup(@Param("tenant") String tenant);
    int completeCleanup(@Param("tenant") String tenant, @Param("workspace") String workspace);
    int confirmInitialization(@Param("tenant") String tenant, @Param("workspace") String workspace,
                              @Param("operation") String operation);
    int recordImport(@Param("tenant") String tenant, @Param("workspace") String workspace,
                     @Param("operation") String operation, @Param("evidence") String evidence);

    /** 无文件正文的稳定归属，删除状态保留墓碑阻止迟到调用重新建目录。 */
    record Row(String workspaceId, String tenantId, String sessionId, String productSessionId,
               long endUserId, String cleanupState, String initialOperationId, boolean initialized) { }
}
