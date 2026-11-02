package com.skillforge.runtime.api.port;

/** 枚举一次 Provider attempt 的确定成功、等待输入、确定失败与结果未知分辨率。 */
public enum PortalResolution {
    KNOWN_RESULT,
    INPUT_REQUIRED,
    KNOWN_FAILURE,
    OUTCOME_UNKNOWN
}
