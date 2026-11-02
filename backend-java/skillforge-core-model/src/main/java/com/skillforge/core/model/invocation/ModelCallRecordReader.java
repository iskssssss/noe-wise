package com.skillforge.core.model.invocation;

import java.util.Optional;
import java.util.List;
import java.util.Map;

/** 在已授权的租户窗口中精确读取一次外调材料；不解析业务归属或授予会话权限。 */
public interface ModelCallRecordReader {
    record Key(String invocationId, String attemptId) { }
    Map<Key, ModelCallRecordStatus> statuses(long tenantId, List<Key> keys);
    Optional<ModelCallRecord> find(long tenantId, String invocationId, String attemptId);
}
