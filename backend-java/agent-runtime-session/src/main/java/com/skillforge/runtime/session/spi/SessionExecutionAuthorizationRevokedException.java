package com.skillforge.runtime.session.spi;

/**
 * 宿主已完成实时权限查询并确定原执行授权被撤回；当前 Run 应走失败收敛，不能重试业务。
 * 数据库不可用、未分类的身份错误和租约竞争不得转换为本异常。
 */
public final class SessionExecutionAuthorizationRevokedException extends SecurityException {
    public SessionExecutionAuthorizationRevokedException(String reasonCode) {
        super(requireReason(reasonCode));
    }

    public String reasonCode() { return getMessage(); }

    private static String requireReason(String reasonCode) {
        if (reasonCode == null || !reasonCode.matches("[a-z][a-z0-9_]{0,95}")) {
            throw new IllegalArgumentException("invalid_authorization_revocation_reason");
        }
        return reasonCode;
    }
}
