package com.skillforge.core.foundation.application;

import com.skillforge.core.foundation.contract.ControlOperation;

/** 异步控制操作的执行上下文；只从已抢占的持久账本建立，关闭必须恢复原线程状态。 */
@FunctionalInterface
public interface ControlOperationExecutionScope {
    Scope open(ControlOperation processing);

    /** 与一次资源执行及结果落账同寿命的上下文。 */
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
