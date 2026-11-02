package com.skillforge.core.capability;

import java.util.List;

/** Draft Version 的实时确定性校验结果；不写入持久化状态。 */
public record CapabilityValidationView(
        Long definitionId,
        Long versionId,
        Long definitionRevision,
        boolean valid,
        List<CapabilityCatalogError> errors) {

    public CapabilityValidationView {
        errors = List.copyOf(errors == null ? List.of() : errors);
    }
}
