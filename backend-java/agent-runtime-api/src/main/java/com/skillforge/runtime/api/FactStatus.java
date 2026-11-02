package com.skillforge.runtime.api;

/** 固定 MemoryFact 的六态治理生命周期。 */
public enum FactStatus {
    ACTIVE,
    DECAYED,
    PENDING_CONFIRM,
    REJECTED,
    ARCHIVED,
    FORGOTTEN
}
