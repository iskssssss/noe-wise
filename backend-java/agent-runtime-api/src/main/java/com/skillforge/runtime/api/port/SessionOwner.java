package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.SessionSubject;

/**
 * 固定 Session 的机构、归属对象与服务目录，不承载每次触发的请求者或实际执行 Agent。
 * @param sessionRef 稳定 Session 与机构身份
 * @param subject 封闭的真实用户或群生命周期归属
 * @param target 个人的 Agent/Team，或与群归属完全相同的 GROUP 目录
 */
public record SessionOwner(SessionRef sessionRef, SessionSubject subject, SessionTarget target) {
    /** 拒绝错机构、错群目录及个人/群目标混用，归属不得通过空用户降级。 */
    public SessionOwner {
        sessionRef = PortContracts.requireNonNull(sessionRef, "sessionRef");
        subject = PortContracts.requireNonNull(subject, "subject");
        target = PortContracts.requireNonNull(target, "target");
        switch (subject) {
            case SessionSubject.User ignored -> {
                if (target.kind() == SessionTarget.Kind.GROUP)
                    throw new IllegalArgumentException("user Session subject requires AGENT or TEAM target");
            }
            case SessionSubject.Group group -> {
                PortContracts.requireSameTenant(sessionRef.tenantId(), group.groupBindingRef().tenantId(), "groupBindingRef.tenantId");
                if (target.kind() != SessionTarget.Kind.GROUP || !target.id().equals(group.groupBindingRef().groupBindingId()))
                    throw new IllegalArgumentException("group Session subject and GROUP target must reference the same binding");
            }
        }
    }

    /** 正式个人入口显式声明真实用户，不从群归属补造用户。 */
    public static SessionOwner user(SessionRef sessionRef, SessionTarget target, EndUserId endUserId) {
        return new SessionOwner(sessionRef, SessionSubject.user(endUserId), target);
    }

    /** 个人直连 Agent 的类型化工厂。 */
    public static SessionOwner user(SessionRef sessionRef, AgentId agentId, EndUserId endUserId) {
        return user(sessionRef, SessionTarget.agent(agentId), endUserId);
    }

    /** 仅供仍明确为个人的 Runtime 契约；群调用立即失败，不返回 null 或虚构用户。 */
    public EndUserId requireUser() {
        if (subject instanceof SessionSubject.User user) return user.endUserId();
        throw new IllegalStateException("group Session has no owner EndUser; this entry requires a user Session");
    }
}
