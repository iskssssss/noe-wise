package com.skillforge.core.chat.contract;

import java.util.Objects;

/** 名称来自已授权的公开材料；来源选择器仅供程序关联和再次提交。 */
public record ChatReferenceView(String label, ChatInputReference source) {
    public ChatReferenceView { Objects.requireNonNull(label); Objects.requireNonNull(source); }
}
