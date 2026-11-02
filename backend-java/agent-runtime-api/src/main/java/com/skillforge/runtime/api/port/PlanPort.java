package com.skillforge.runtime.api.port;

import java.util.*;

/** 通用计划宿主边界，与委派能力开关无关。所有查询均使用可信 Run 身份。 */
public interface PlanPort {
    record Reply(Map<String,Object> value, Optional<String> waitHandle) {
        public Reply { value = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(value))); Objects.requireNonNull(waitHandle); }
    }
    record State(boolean exists, boolean executionApproved, boolean complete, Optional<String> activeTaskId, boolean inherited) {
        public State(boolean exists,boolean executionApproved,boolean complete,Optional<String> activeTaskId) { this(exists,executionApproved,complete,activeTaskId,false); }
        public State { Objects.requireNonNull(activeTaskId); }
        public static State absent() { return new State(false,false,false,Optional.empty()); }
    }
    Reply execute(RunIdentity run, String operationKey, PlanCommand command);
    Optional<Reply> pollWait(RunIdentity run, String waitHandle);
    State state(RunIdentity run);
    /** 在外部派发前绑定原 Action；宿主必须幂等核对当前步骤和受理版本。 */
    void bindAction(RunIdentity run, ActionRef action);
}
