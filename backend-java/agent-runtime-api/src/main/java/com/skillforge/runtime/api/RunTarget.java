package com.skillforge.runtime.api;

/**
 * 显式区分一次 Inbound 是在准入后新建运行，还是作用于既有持久运行。
 */
public sealed interface RunTarget permits RunTarget.NewRun, RunTarget.ExistingRun {

    /** 表示只有通过完整准入后才允许分配 RunRef 的新运行请求。 */
    record NewRun() implements RunTarget {
    }

    /** 表示输入必须作用于指定且已经存在的持久运行。 */
    record ExistingRun(RunRef runRef) implements RunTarget {

        /** 拒绝缺失的目标运行引用。 */
        public ExistingRun {
            runRef = ApiContracts.requireNonNull(runRef, "runRef");
        }
    }
}
