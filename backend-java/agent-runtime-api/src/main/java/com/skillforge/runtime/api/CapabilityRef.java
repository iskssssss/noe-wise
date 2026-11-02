package com.skillforge.runtime.api;

/** 表示已发布能力类型、稳定身份与版本的组合引用。 */
public record CapabilityRef(
        CapabilityType type,
        String capabilityId,
        String versionId) {

    /** 拒绝缺失类型以及空白能力或版本身份。 */
    public CapabilityRef {
        type = ApiContracts.requireNonNull(type, "type");
        capabilityId = ApiContracts.requireText(capabilityId, "capabilityId");
        versionId = ApiContracts.requireText(versionId, "versionId");
    }
}
