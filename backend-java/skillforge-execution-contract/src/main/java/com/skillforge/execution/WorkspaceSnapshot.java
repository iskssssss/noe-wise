package com.skillforge.execution;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;

/** 已冻结版本的受控元数据；存储对象键和凭据不作为公开接口。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record WorkspaceSnapshot(String id, long revision, long size, String sha256, Instant createdAt, boolean ready) { }
