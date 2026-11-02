package com.skillforge.runtime.api;

import java.util.List;

/** 引用已提交到 SessionLog 的特定版本证据项集合。 */
public record EvidenceRef(
        SessionRef sessionRef,
        String bundleId,
        List<String> itemIds,
        long version,
        EventId sourceEventId) {

    /** 拒绝缺失 Session、空白身份、重复条目或无效版本。 */
    public EvidenceRef {
        sessionRef = ApiContracts.requireNonNull(sessionRef, "sessionRef");
        bundleId = ApiContracts.requireText(bundleId, "bundleId");
        itemIds = ApiContracts.immutableDistinctTextList(itemIds, "itemIds");
        version = ApiContracts.requirePositive(version, "version");
        sourceEventId = ApiContracts.requireNonNull(sourceEventId, "sourceEventId");
    }
}
