package com.skillforge.institution.browser;

import com.skillforge.institution.runtime.BrowserConnectionRuntimeBridge.ToolWait;

/** 连接请求第一次观察到的最小等待身份，不再复制内部checkpoint或唤醒票据。
 * @param actionId 原逻辑动作 @param generation 该动作等待代数 @param runRevision 等待提交时业务版本
 */
public record BrowserConnectionWaitSnapshot(String actionId, long generation, long runRevision) {
    /** 原等待由Runtime持久事实投影，不能把已建立连接借给新等待。 */
    public static BrowserConnectionWaitSnapshot of(ToolWait pending) {
        return new BrowserConnectionWaitSnapshot(pending.waiting().action().actionId(), pending.waiting().generation(), pending.runRevision());
    }
}
