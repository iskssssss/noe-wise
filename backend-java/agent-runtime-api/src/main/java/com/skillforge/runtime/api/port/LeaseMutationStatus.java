package com.skillforge.runtime.api.port;

/** 表示带租约和 fencing 的 mutation 收敛结果。 */
public enum LeaseMutationStatus {
    APPLIED,
    ALREADY_APPLIED,
    STALE_LEASE,
    NOT_FOUND,
    TERMINAL
}
