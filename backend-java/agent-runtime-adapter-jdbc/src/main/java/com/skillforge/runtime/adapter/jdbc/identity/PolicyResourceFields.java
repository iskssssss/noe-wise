package com.skillforge.runtime.adapter.jdbc.identity;

import java.util.List;

/** 宿主授权资源复用 Runtime 固定字符串数组协议的严格解析边界。 */
public final class PolicyResourceFields {
    private PolicyResourceFields() { }

    /** 字段数量、字符串转义和完整输入均按 Runtime 规范检查，不解析开放 JSON 对象。 */
    public static List<String> parse(String canonicalJson, int expectedSize) {
        return AdmissionResource.flatStrings(canonicalJson, expectedSize);
    }
}
