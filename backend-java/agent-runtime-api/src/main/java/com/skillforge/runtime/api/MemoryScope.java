package com.skillforge.runtime.api;

/** 会话、个人与群的五种持久记忆作用域，由 MemoryOwner 分支确定。 */
public enum MemoryScope {
    SESSION,
    USER_AGENT,
    USER,
    GROUP,
    GROUP_MEMBER
}
