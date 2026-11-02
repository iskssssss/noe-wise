package com.skillforge.execution;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

/** 查询投影来自 Sandbox 持久记录，宿主不复制备份状态机。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record WorkspaceStatus(String workspaceId, long revision, long backupRevision, boolean localAvailable,
        String phase, String backupError, Instant lastBackupAt, List<WorkspaceSnapshot> snapshots) {
    public WorkspaceStatus { snapshots = List.copyOf(snapshots); }
}
