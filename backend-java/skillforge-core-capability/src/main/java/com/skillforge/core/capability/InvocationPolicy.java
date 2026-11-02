package com.skillforge.core.capability;

/** 能力在模型面和用户面是否可调用。 */
public record InvocationPolicy(boolean modelInvocable, boolean userInvocable) {
}
