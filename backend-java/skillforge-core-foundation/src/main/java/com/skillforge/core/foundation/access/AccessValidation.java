package com.skillforge.core.foundation.access;

import com.skillforge.core.shared.exception.ApiException;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** access 所属的严格输入约束，不允许空串、截断或未知状态自动回退。 */
final class AccessValidation {
    private AccessValidation() { }
    /** 验证十进制正整数 ID，拒绝 JSON 数字造成的身份精度丢失。 */
    static long id(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw bad(field + " 必须为十进制字符串 ID");
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw bad(field + " 超出 ID 范围"); }
    }
    /** 校验并规范化短文本，按 Unicode 码点计数。 */
    static String text(String value, int max, String field) {
        if (value == null || value.isBlank()) throw bad(field + " 不能为空");
        String result = value.trim();
        if (result.codePointCount(0, result.length()) > max) throw bad(field + " 超出长度限制");
        return result;
    }
    /** 校验稳定业务编码，禁止与平台保留命名冲突。 */
    static String code(String value) {
        String result = text(value, 64, "code");
        if (!result.matches("[a-z][a-z0-9_]{0,63}")) throw bad("编码仅允许小写字母、数字和下划线");
        return result;
    }
    /** 校验有限状态编码。 */
    static String choice(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value == null ? "" : value)) throw bad(field + " 取值无效");
        return value;
    }
    /** 校验资源种类和动作，消费者不能注入 SQL 表名或额外动作。 */
    static void resource(String kind, String action) {
        choice(kind, Set.of("agent", "workflow", "knowledge"), "kind");
        choice(action, Set.of("read", "execute", "manage"), "action");
    }
    /** 统一领域输入拒绝。 */
    static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "foundation.access.invalid", message, false); }
}
