package com.skillforge.core.foundation.settings;

import java.time.Instant;
import java.util.Map;

/** 不可变运营配置版本的持久化行。 */
public class OperationalSettingVersionRow {
    private String groupKey;
    private long revision;
    private Map<String, Object> values;
    private Instant createdAt;
    private Long actorId;

    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public Map<String, Object> getValues() { return values; }
    public void setValues(Map<String, Object> values) { this.values = values; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
}
