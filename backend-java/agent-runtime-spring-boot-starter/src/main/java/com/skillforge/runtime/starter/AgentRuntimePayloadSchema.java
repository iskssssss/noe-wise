package com.skillforge.runtime.starter;

import com.skillforge.runtime.api.TypedPayload;
import java.util.Set;

/**
 * 宿主为自己拥有的 JSON 载荷声明准确 schema 与结构校验，无需依赖 Core。
 * Starter 先执行 Runtime JSON 资源限额检查，再调用本接口；此接口不授予身份或业务权限。
 */
public interface AgentRuntimePayloadSchema {
    /** 返回由本组件独占的非空 schema 标识；多个组件重复声明同一标识时启动失败。 */
    Set<String> schemaIds();

    /** 校验已通过有界 JSON 解析的原始载荷；结构非法时抛出不包含正文的异常。 */
    void validate(TypedPayload payload);
}
