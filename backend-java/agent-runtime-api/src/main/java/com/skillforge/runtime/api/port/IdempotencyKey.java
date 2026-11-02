package com.skillforge.runtime.api.port;

/** 表示跨安全重试保持稳定的幂等键。 */
public record IdempotencyKey(String value) {
    /** 拒绝空白幂等键。 */
    public IdempotencyKey {
        value = PortContracts.requireText(value, "value");
    }
}
