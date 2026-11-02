package com.skillforge.core.capability;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 维护台编辑快照；已发布结果只用于幂等回执，不进入能力挂载查询。 */
public record SkillDraftView(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long definitionId,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long baseVersionId,
        long revision, String versionKey,
        Map<String, String> files, boolean modelInvocable, boolean userInvocable,
        SkillActivationMode activationMode, String conflictGroup,
        List<CapabilityRef> requiredCapabilities,
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long publishedVersionId,
        Instant updatedAt,
        /** 仅精确版本回读已解析；编辑草稿为null，不推断为optional。 */
        Boolean requiresPlan) {
    public static SkillDraftView of(Long id, Long definitionId, Long baseVersionId, long revision,
            SkillDraftContent content, Long publishedVersionId, Instant updatedAt, Boolean requiresPlan) {
        return new SkillDraftView(id, definitionId, baseVersionId, revision, content.versionKey(),
                content.files(), content.modelInvocable(), content.userInvocable(), content.activationMode(),
                content.conflictGroup(), content.requiredCapabilities(), publishedVersionId, updatedAt, requiresPlan);
    }
    public SkillDraftContent content() {
        return new SkillDraftContent(versionKey, files, modelInvocable, userInvocable,
                activationMode, conflictGroup, requiredCapabilities);
    }
}
