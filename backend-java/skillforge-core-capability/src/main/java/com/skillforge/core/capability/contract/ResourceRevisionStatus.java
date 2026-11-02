package com.skillforge.core.capability.contract;

/** 资源 revision 生命周期。 */
public enum ResourceRevisionStatus {
    DRAFT("draft"),
    VALIDATING("validating"),
    VALIDATION_FAILED("validation_failed"),
    READY("ready"),
    PUBLISHED("published"),
    ARCHIVED("archived");

    private final String value;

    ResourceRevisionStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
