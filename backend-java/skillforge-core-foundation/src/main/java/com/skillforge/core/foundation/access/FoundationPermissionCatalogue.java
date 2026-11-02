package com.skillforge.core.foundation.access;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 汇总各域权限定义，冲突或非法编码阻止启动，未知编码永远不授权。 */
@Component
public final class FoundationPermissionCatalogue {
    private final Map<String, FoundationPermission> definitions;

    /** 构造不可变权限目录，读取所有域贡献者。 */
    public FoundationPermissionCatalogue(List<FoundationPermissionContributor> contributors) {
        Map<String, FoundationPermission> result = new LinkedHashMap<>();
        for (FoundationPermissionContributor contributor : contributors) {
            for (FoundationPermission definition : contributor.permissions()) {
                if (definition == null || definition.code() == null
                        || !definition.code().matches("(foundation|platform|tenant)\\.[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+" )
                        || definition.name() == null || definition.name().isBlank() || definition.audience() == null) {
                    throw new IllegalArgumentException("权限目录存在非法定义");
                }
                if ((definition.code().startsWith("platform.") && definition.audience() != FoundationPermission.Audience.PLATFORM)
                        || (definition.code().startsWith("tenant.") && definition.audience() != FoundationPermission.Audience.TENANT)) {
                    throw new IllegalArgumentException("权限命名空间与账号体系不匹配");
                }
                FoundationPermission old = result.putIfAbsent(definition.code(), definition);
                if (old != null && !old.equals(definition)) throw new IllegalStateException("权限定义冲突: " + definition.code());
            }
        }
        this.definitions = Map.copyOf(result);
    }

    /** 返回当前账号体系可见的权限，不把平台专用动作授予机构。 */
    public List<FoundationPermission> permissions(boolean platform) {
        return definitions.values().stream().filter(p -> p.audience() == FoundationPermission.Audience.BOTH
                || p.audience() == (platform ? FoundationPermission.Audience.PLATFORM : FoundationPermission.Audience.TENANT))
                .sorted(java.util.Comparator.comparing(FoundationPermission::code)).toList();
    }

    /** 检查编码已经注册且属于目标体系。 */
    public boolean supports(String code, boolean platform) {
        FoundationPermission definition = definitions.get(code);
        return definition != null && (definition.audience() == FoundationPermission.Audience.BOTH
                || definition.audience() == (platform ? FoundationPermission.Audience.PLATFORM : FoundationPermission.Audience.TENANT));
    }
}
