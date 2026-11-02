package com.skillforge.runtime.adapter.jdbc.identity;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.SessionOwner;

/** IAM 固定数组中的会话主体编码；GROUP 明确携带绑定，不借用终端用户身份。 */
public final class OwnerPolicyFields {
    private OwnerPolicyFields() { }

    /** 保持个人主体的规范十进制编码，群主体使用带类型的精确绑定引用。 */
    public static String subject(SessionOwner owner) {
        return switch (owner.subject()) {
            case SessionSubject.User user -> Long.toString(user.endUserId().value());
            case SessionSubject.Group group -> "group:" + group.groupBindingRef().groupBindingId();
        };
    }

    /** 完整核对目标与主体判别，不接受空值、伪用户或跨租户绑定。 */
    public static SessionOwner owner(TenantId tenant, String session, String kind, String target, String subject) {
        var ref = new SessionRef(tenant, session);
        var destination = new SessionTarget(SessionTarget.Kind.valueOf(kind), target);
        if (destination.kind() == SessionTarget.Kind.GROUP) {
            if (!subject.equals("group:" + target)) throw new IllegalArgumentException("group_policy_subject_mismatch");
            return new SessionOwner(ref, SessionSubject.group(new GroupBindingRef(tenant, target)), destination);
        }
        long user = Long.parseLong(subject);
        if (!Long.toString(user).equals(subject)) throw new IllegalArgumentException("user_policy_subject_invalid");
        return SessionOwner.user(ref, destination, new EndUserId(user));
    }
}
