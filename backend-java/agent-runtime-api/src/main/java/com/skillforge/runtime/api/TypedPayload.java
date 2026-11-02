package com.skillforge.runtime.api;

/** 以注册 schema 和规范化 JSON 文本承载公开动态数据。 */
public record TypedPayload(String schemaId, String canonicalJson) {

    /** 拒绝空白 schema 身份或空白规范化 JSON。 */
    public TypedPayload {
        schemaId = ApiContracts.requireText(schemaId, "schemaId");
        canonicalJson = ApiContracts.requireText(canonicalJson, "canonicalJson");
    }
}
