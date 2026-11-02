package com.skillforge.platform.sessionmodelattempt;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 在私有正文读取边界再次验证平台管理员身份，内部调用同样必须携带作用域。 */
@Component
public class ModelAttemptDiagnosticAuthorizer {
    private static final Logger LOG = LoggerFactory.getLogger(ModelAttemptDiagnosticAuthorizer.class);
    private final FoundationAccess access;

    /** 私有诊断正文具有独立动作权限，与普通会话统计读取分离。 */
    public ModelAttemptDiagnosticAuthorizer(FoundationAccess access) { this.access = access; }

    /** 未登录与非平台管理员均拒绝签发；日志只保存授权结果。 */
    public ModelAttemptDiagnosticScope platformAdmin() {
        LOG.debug("模型调用私有诊断授权进入");
        access.require("foundation.sessions.diagnostic_read");
        FoundationAccess.Subject subject = access.currentSubject();
        if (!subject.platform()) throw new ApiException(HttpStatus.FORBIDDEN, "仅平台账号可读取模型调用诊断正文");
        String actor = subject.memberId();
        LOG.debug("模型调用私有诊断授权完成");
        return new ModelAttemptDiagnosticScope(actor);
    }
}
