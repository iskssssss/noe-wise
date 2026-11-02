package com.skillforge.core.chat.persistence;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 原始画像总结窗口的固定结构；画像正文与偏好仍保持动态结构。 */
public record ProfileSummary(String content, @JsonProperty("created_at") String createdAt) { }
