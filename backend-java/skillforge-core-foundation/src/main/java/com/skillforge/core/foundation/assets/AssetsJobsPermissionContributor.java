package com.skillforge.core.foundation.assets;

import com.skillforge.core.foundation.access.FoundationPermission;
import com.skillforge.core.foundation.access.FoundationPermissionContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/** 公共文件与管理任务动作的唯一权限定义，双宿主使用同一目录。 */
@Component
public final class AssetsJobsPermissionContributor implements FoundationPermissionContributor {
    /** 文件读写与任务受理、取消相互独立，任务结果仍复验原业务授权。 */
    @Override public List<FoundationPermission> permissions() {
        return List.of(p("files.read","查看公共文件"),p("files.upload","上传公共文件"),p("files.delete","删除公共文件"),
                p("jobs.read","查看管理任务"),p("jobs.create","受理管理任务"),p("jobs.cancel","取消管理任务"));
    }
    private static FoundationPermission p(String action,String label) { return new FoundationPermission("foundation."+action,label,FoundationPermission.Audience.BOTH); }
}
