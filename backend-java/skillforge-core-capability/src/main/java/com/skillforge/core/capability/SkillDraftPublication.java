package com.skillforge.core.capability;

/** 草稿一次发布的持久回执。 */
public record SkillDraftPublication(
        @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-9][0-9]*")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long versionId,
        long definitionRevision) { }
