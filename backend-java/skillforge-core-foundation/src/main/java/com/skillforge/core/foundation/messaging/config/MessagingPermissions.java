package com.skillforge.core.foundation.messaging.config;

import com.skillforge.core.foundation.access.FoundationPermission;
import com.skillforge.core.foundation.access.FoundationPermissionContributor;
import java.util.List;

/** 消息领域真实动作注册；BOTH只允许双方各自作用域，不授予跨租户能力。 */
public class MessagingPermissions implements FoundationPermissionContributor {
    /** {@inheritDoc} */
    @Override public List<FoundationPermission> permissions() {
        return List.of(permission("read","查看公告"),permission("publish","发布和撤回公告"),
                permission("configure","配置模板和SMTP邮箱"),permission("operate","查看和处置邮件投递"),permission("inbox","读取本人站内信"));
    }
    /** 定义单个稳定的本领域权限。 */
    private static FoundationPermission permission(String action,String name) { return new FoundationPermission("foundation.messaging."+action,name,FoundationPermission.Audience.BOTH); }
}
