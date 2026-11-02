package com.skillforge.platform.sessionmodelattempt;

/** 私有模型调用正文的独立平台授权；不复用公开 trace 的 TENANT 作用域。 */
public final class ModelAttemptDiagnosticScope {
    private final String actorId;

    /** 仅同包授权器能签发私有诊断作用域。 */
    ModelAttemptDiagnosticScope(String actorId) {
        if (actorId == null || actorId.isBlank()) throw new IllegalArgumentException("diagnostic_actor_missing");
        this.actorId = actorId;
    }

    /** 审计使用已认证操作者，不读取请求传入的 actor。 */
    public String actorId() { return actorId; }
}
