package com.skillforge.runtime.adapter.jdbc.identity;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.util.List;
import java.util.Optional;

/** IAM 资源尾部的原始请求者、消息和来源；恢复只接受完整 typed 事实。 */
final class RunPolicyFields {
    private RunPolicyFields() { }

    /** 所有执行资源共享四个来源字段，来源和请求者不从会话所有者反推。 */
    static List<String> values(RunIdentity run) {
        String requester = switch (run.requester()) {
            case RunRequester.EndUser user -> "user:" + user.endUserId().value();
            case RunRequester.System system -> "system:" + system.principal().principalId();
        };
        return List.of(requester, run.sourceMessageRef().map(value -> value.sessionRef().sessionId()).orElse(""),
                run.sourceMessageRef().map(SourceMessageRef::messageId).orElse(""), run.originRef().originId());
    }

    /** 仅恢复明确声明的真实请求者分支，不从归属推导。 */
    static RunRequester requester(TenantId tenant,String value) {
        if(value.startsWith("user:")) {
            long id=Long.parseLong(value.substring(5));
            if(!value.equals("user:"+id))throw new IllegalArgumentException("run_policy_requester_invalid");
            return new RunRequester.EndUser(new EndUserId(id));
        }
        if(value.startsWith("system:"))return new RunRequester.System(new PrincipalRef.SystemPrincipal(tenant,value.substring(7)));
        throw new IllegalArgumentException("run_policy_requester_invalid");
    }

    /** 按原运行字段和来源尾部构造公开类型，任何字段缺失或交叉归属均失败。 */
    static RunIdentity restore(SessionOwner owner, AgentId agent, InputId input, RunRef ref, TraceEnvelope trace,
            ResultRef identity, List<String> fields, int start) {
        String requester = fields.get(start);
        RunRequester author=requester(ref.tenantId(),requester);
        if (fields.get(start + 1).isEmpty() != fields.get(start + 2).isEmpty()) throw new IllegalArgumentException("run_policy_source_invalid");
        var source = fields.get(start + 1).isEmpty() ? Optional.<SourceMessageRef>empty()
                : Optional.of(new SourceMessageRef(new SessionRef(ref.tenantId(), fields.get(start + 1)), fields.get(start + 2)));
        var run = new RunIdentity(owner, agent, input, ref, trace, identity, author, source,
                new RunOriginRef(ref.tenantId(), fields.get(start + 3)));
        if (!values(run).equals(fields.subList(start, start + 4))) throw new IllegalArgumentException("run_policy_source_not_canonical");
        return run;
    }
}
