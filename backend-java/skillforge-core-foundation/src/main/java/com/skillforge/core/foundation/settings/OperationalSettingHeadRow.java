package com.skillforge.core.foundation.settings;

import java.time.Instant;

/** 当前运营配置版本指针的持久化行。 */
public class OperationalSettingHeadRow {
    private String groupKey;
    private long currentRevision;
    private Instant updatedAt;
    private Long updatedBy;

    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }
    public long getCurrentRevision() { return currentRevision; }
    public void setCurrentRevision(long currentRevision) { this.currentRevision = currentRevision; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
}
