package com.skillforge.core.foundation.operations;

import com.skillforge.core.foundation.access.FoundationPermission;
import com.skillforge.core.foundation.access.FoundationPermissionContributor;
import com.skillforge.core.foundation.operations.persistence.OperationsMapper;
import java.util.List;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** 双宿主 operations 生产装配；平台显式导入，机构既有扫描发现，XML Mapper只扫描声明接口。 */
@Configuration
@ComponentScan(basePackageClasses = FoundationAuditService.class)
@MapperScan(basePackageClasses = OperationsMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class FoundationOperationsConfiguration {
    /** 仅注册本域已经实现的动作，管理员目录也通过这些明确权限构建。 */
    @Bean
    public FoundationPermissionContributor operationsPermissions() {
        return () -> List.of(permission(FoundationAuditService.READ, "检索安全审计"),
                permission(FoundationAuditService.EXPORT, "导出安全审计"),
                permission(FoundationRetentionService.READ, "查看保留策略与回执"),
                permission(FoundationRetentionService.MANAGE, "维护保留策略与调查保护"),
                permission(FoundationRetentionService.EXECUTE, "预览并执行审计清理"),
                permission(FoundationOperationsStatus.READ, "查看真实运维状态"),
                new FoundationPermission("foundation.sessions.diagnostic_read", "读取模型调用私有诊断正文", FoundationPermission.Audience.PLATFORM),
                new FoundationPermission("foundation.handoff.manage", "处理本机构他人接管工单", FoundationPermission.Audience.TENANT));
    }

    /** 平台和机构使用同动作编码，各自仍受其账号体系和数据作用域限制。 */
    private static FoundationPermission permission(String code, String name) {
        return new FoundationPermission(code, name, FoundationPermission.Audience.BOTH);
    }
}
