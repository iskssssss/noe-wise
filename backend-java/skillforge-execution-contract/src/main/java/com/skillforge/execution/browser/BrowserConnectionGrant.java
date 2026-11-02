package com.skillforge.execution.browser;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 宿主连接请求与官方设备授权的准确关联；不包含配对串、密钥或在线状态。 */
public record BrowserConnectionGrant(
        @JsonProperty("connection_request_id") String connectionRequestId,
        Status status,
        @JsonProperty("device_id") String deviceId) {
    /** WAITING 仅表示尚未观察到成功，UNKNOWN 不允许推断授权或重放配对。 */
    public enum Status { NOT_FOUND, WAITING, PAIRED, UNKNOWN }
}
