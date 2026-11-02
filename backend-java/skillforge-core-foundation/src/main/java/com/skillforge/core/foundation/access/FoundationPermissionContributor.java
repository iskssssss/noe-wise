package com.skillforge.core.foundation.access;

import java.util.List;

/** 各能力域通过 Spring bean 注册自身真实实现的动作，不允许未注册编码放行。 */
public interface FoundationPermissionContributor {
    /** 返回本域稳定权限定义；重复编码的定义必须完全一致。 */
    List<FoundationPermission> permissions();
}
