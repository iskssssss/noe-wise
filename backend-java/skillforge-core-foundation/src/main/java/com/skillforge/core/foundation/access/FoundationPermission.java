package com.skillforge.core.foundation.access;

/**
 * 领域注册的稳定权限定义，平台与机构的目录分别过滤。
 * @param code 已注册的稳定动作编码，foundation 公共域或 platform/tenant 专属域
 * @param name 面向管理员的中文动作名称
 * @param audience 允许使用此权限的账号体系
 */
public record FoundationPermission(String code, String name, Audience audience) {
    /** 权限所属的账号体系；BOTH 仅表示双方均有此动作，不形成跨体系授权。 */
    public enum Audience { PLATFORM, TENANT, BOTH }
}
