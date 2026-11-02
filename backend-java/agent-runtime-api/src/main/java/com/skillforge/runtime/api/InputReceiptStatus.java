package com.skillforge.runtime.api;

/** 定义幂等账本稳定 receipt 的接受或业务拒绝状态。 */
public enum InputReceiptStatus {
    /** 输入已被接受并绑定一个持久 RunRef。 */
    ACCEPTED,
    /** 输入被稳定拒绝且没有为 NewRun 创建运行。 */
    REJECTED
}
