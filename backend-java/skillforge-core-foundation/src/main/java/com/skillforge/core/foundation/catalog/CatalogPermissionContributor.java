package com.skillforge.core.foundation.catalog;

import com.skillforge.core.foundation.access.FoundationPermission;
import com.skillforge.core.foundation.access.FoundationPermissionContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/** catalog 管理动作的唯一权限定义，注册进统一权限目录。 */
@Component
public class CatalogPermissionContributor implements FoundationPermissionContributor {
    /** 同一域 read/manage 分离；套餐写入仅属于平台。 */
    @Override public List<FoundationPermission> permissions() {
        return List.of(p("dictionary.read","查看字典",FoundationPermission.Audience.BOTH),p("dictionary.manage","维护字典",FoundationPermission.Audience.BOTH),
            p("navigation.read","查看菜单配置",FoundationPermission.Audience.BOTH),p("navigation.manage","维护菜单结构",FoundationPermission.Audience.BOTH),
            p("settings.read","查看机构品牌",FoundationPermission.Audience.TENANT),p("settings.manage","维护机构品牌",FoundationPermission.Audience.TENANT),
            p("preferences.manage","维护本人偏好",FoundationPermission.Audience.BOTH),p("entitlements.read","查看功能与配额",FoundationPermission.Audience.BOTH),p("entitlements.manage","维护套餐功能与配额",FoundationPermission.Audience.PLATFORM));
    }
    /** 权限编码集中在拥有域内，不接受任意默认放行。 */
    private FoundationPermission p(String action,String name,FoundationPermission.Audience audience) { return new FoundationPermission("foundation."+action,name,audience); }
}
