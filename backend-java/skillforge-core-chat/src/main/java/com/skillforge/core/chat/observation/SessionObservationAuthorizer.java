package com.skillforge.core.chat.observation;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 将已认证身份收敛为查询服务可消费的显式作用域。 */
@Component
public final class SessionObservationAuthorizer {
    private final FoundationAccess access;

    /** 注入数据库当前授权，JWT角色声明不形成读取权。 */
    public SessionObservationAuthorizer(FoundationAccess access) { this.access = access; }

    /** 当前平台主体拥有会话读取动作后才能签发本次请求作用域。 */
    public SessionObservationScope platformAdmin() {
        access.require("foundation.sessions.read");
        FoundationAccess.Subject subject = access.currentSubject();
        if (!subject.platform()) throw new ApiException(HttpStatus.FORBIDDEN, "仅平台账号可观测跨机构会话");
        String actorId = subject.memberId();
        return new SessionObservationScope(
                SessionObservationScope.Kind.PLATFORM_ADMIN, null, actorId);
    }

    /** 机构观测只能由当前机构成员签发，不能由请求中的 tenant_id 决定。 */
    public SessionObservationScope tenant() {
        access.require("foundation.sessions.read");
        FoundationAccess.Subject subject = access.currentSubject();
        if (subject.platform()) throw denied("平台账号不能使用机构会话观测入口");
        return new SessionObservationScope(SessionObservationScope.Kind.TENANT,
                Long.parseLong(subject.tenantId()), subject.memberId());
    }

    /** 每次深层查询复核当前主体及会话自身的数据范围，历史作用域不形成授权缓存。 */
    public void requireSession(SessionObservationScope scope, long tenantId, String sessionId) {
        FoundationAccess.Subject subject = requireCurrent(scope);
        if (tenantId <= 0 || scope.kind() == SessionObservationScope.Kind.TENANT
                && scope.tenantId().longValue() != tenantId) throw denied("目标机构不在当前观测范围内");
        if (scope.kind() == SessionObservationScope.Kind.TENANT)
            access.requireSession(subject, sessionId, "read");
    }

    /** null 表示当前机构全部会话；空集合必须生成不匹配任何会话的过滤条件。 */
    public List<String> visibleSessionIds(SessionObservationScope scope) {
        FoundationAccess.Subject subject = requireCurrent(scope);
        return scope.kind() == SessionObservationScope.Kind.PLATFORM_ADMIN
                ? null : access.visibleSessionIds(subject, "read");
    }

    private FoundationAccess.Subject requireCurrent(SessionObservationScope scope) {
        if (scope == null) throw denied("缺少会话观测授权作用域");
        access.require("foundation.sessions.read");
        FoundationAccess.Subject subject = access.currentSubject();
        boolean matches = subject.memberId().equals(scope.actorId())
                && (scope.kind() == SessionObservationScope.Kind.PLATFORM_ADMIN
                    ? subject.platform()
                    : !subject.platform() && subject.tenantId().equals(scope.tenantId().toString()));
        if (!matches) throw denied("会话观测身份已变化，请重新读取");
        return subject;
    }

    private static ApiException denied(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }
}
