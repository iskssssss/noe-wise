package com.skillforge.core.foundation.assets;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.ObjectProvider;

/** 双宿主共享公共文件装配；平台需显式导入，机构按 core 扫描装配。 */
@Configuration
@org.springframework.context.annotation.Import(AssetsJobsPermissionContributor.class)
@EnableConfigurationProperties(FoundationFileProperties.class)
@MapperScan(basePackageClasses=FoundationFileMapper.class,annotationClass=org.apache.ibatis.annotations.Mapper.class)
public class FoundationAssetsConfiguration {
    /** 注入同库事务所有者，配额锁与元数据创建共享提交边界。 */
    @Bean public FoundationFileService foundationFileService(FoundationFileMapper mapper,FoundationAccess access,FeatureGate gate,ResourceQuota quota,PlatformTransactionManager manager,FoundationFileProperties properties,ObjectProvider<FoundationFileReferenceAuthorizer> referenceOwners){return new FoundationFileService(mapper,access,gate,quota,manager,properties,referenceOwners);}
}
