package com.skillforge.core.capability.connector;

import java.util.Map;
import java.util.Objects;

/** 参数准入失败只携带契约定义的诊断，不包含模型输入值或原始异常正文。 */
public final class CodeExecutionArgumentException extends IllegalArgumentException {
    public enum Code {
        ARGUMENTS_REQUIRED, UNKNOWN_FIELD, REQUIRED_FIELD, INVALID_TYPE,
        FIELD_FORBIDDEN_FOR_MODE, UNSUPPORTED_VALUE, UNSAFE_PATH,
        INVALID_FORMAT, DUPLICATE_VALUE, LIMIT_EXCEEDED,
        DEPENDENCY_NOT_DECLARED, DEPENDENCY_VERSION_MISMATCH, FROZEN_MANIFEST_INVALID
    }

    private final Code code;
    private final String field;
    private final String hint;

    // 仅解析器构造受控消息；field 只能由已知字段和数组序号组成。
    CodeExecutionArgumentException(Code code, String field, String message, String hint) {
        super(Objects.requireNonNull(message));
        this.code = Objects.requireNonNull(code);
        this.field = Objects.requireNonNull(field);
        this.hint = Objects.requireNonNull(hint);
    }

    public Code code() { return code; }
    public String field() { return field; }
    public String hint() { return hint; }

    public static CodeExecutionArgumentException dependencyNotDeclared() {
        return new CodeExecutionArgumentException(Code.DEPENDENCY_NOT_DECLARED, "/dependencies",
                "依赖未在冻结技能清单中声明。", "删除额外依赖；可省略 dependencies，由冻结技能清单提供精确依赖。");
    }

    public static CodeExecutionArgumentException dependencyVersionMismatch() {
        return new CodeExecutionArgumentException(Code.DEPENDENCY_VERSION_MISMATCH, "/dependencies",
                "依赖版本与冻结技能清单不一致。", "使用冻结清单中的精确版本，或省略 dependencies 由清单自动提供。");
    }

    public static CodeExecutionArgumentException frozenManifestInvalid() {
        return new CodeExecutionArgumentException(Code.FROZEN_MANIFEST_INVALID, "/skill_handle",
                "冻结技能的依赖清单不合法。", "检查并修复技能配置后重新发布；修改本次调用版本不能修复冻结清单。");
    }

    /** 模型可据此修正参数；提示本身不授予资源或执行权限。 */
    public Map<String, Object> diagnostic() {
        return Map.of("status", "available", "code", code.name(), "field", field,
                "message", getMessage(), "hint", hint);
    }
}
