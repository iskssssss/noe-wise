package com.skillforge.runtime.api;

/** 表示入口终端用户的正整数身份。 */
public record EndUserId(long value) {

    /** 拒绝零值和负值，避免匿名身份进入运行时幂等边界。 */
    public EndUserId {
        if (value <= 0) {
            throw new IllegalArgumentException("value must be positive");
        }
    }
}
