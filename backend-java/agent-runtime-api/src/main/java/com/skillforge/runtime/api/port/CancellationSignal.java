package com.skillforge.runtime.api.port;

/** 提供线程安全、无阻塞且单调触发的调用取消观察。 */
@FunctionalInterface
public interface CancellationSignal {
    /** 返回取消是否已经触发；实现不得抛异常或从 true 恢复为 false。 */
    boolean isCancellationRequested();
}
