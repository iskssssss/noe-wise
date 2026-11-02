package com.skillforge.runtime.api;

import com.skillforge.runtime.api.EndUserId;

/** 固定原始执行请求者；与 Session 生命周期归属及当前认证操作者分别保存。 */
public sealed interface RunRequester permits RunRequester.EndUser, RunRequester.System {
    record EndUser(EndUserId endUserId) implements RunRequester {
        public EndUser { endUserId = ApiContracts.requireNonNull(endUserId, "endUserId"); }
    }
    record System(PrincipalRef.SystemPrincipal principal) implements RunRequester {
        public System { principal = ApiContracts.requireNonNull(principal, "principal"); }
    }
}
