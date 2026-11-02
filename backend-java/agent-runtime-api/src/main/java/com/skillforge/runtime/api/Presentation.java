package com.skillforge.runtime.api;

/** 表示由注册 kind 与 schema 化 payload 组成的公开展示项。 */
public record Presentation(String kind, TypedPayload payload) {

    /** 拒绝空白展示类型和缺失 payload。 */
    public Presentation {
        kind = ApiContracts.requireText(kind, "kind");
        payload = ApiContracts.requireNonNull(payload, "payload");
    }
}
