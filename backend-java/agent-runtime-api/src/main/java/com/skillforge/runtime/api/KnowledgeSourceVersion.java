package com.skillforge.runtime.api;

/** 固定知识来源及其不可漂移的版本身份。 */
public record KnowledgeSourceVersion(
        String sourceId,
        String sourceVersion) {

    /** 拒绝空白来源或版本身份。 */
    public KnowledgeSourceVersion {
        sourceId = ApiContracts.requireText(sourceId, "sourceId");
        sourceVersion = ApiContracts.requireText(sourceVersion, "sourceVersion");
    }
}
