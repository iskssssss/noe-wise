package com.skillforge.core.foundation.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.assets.FoundationFileService;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import java.util.List;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** 双宿主管理任务装配，按代码注册消费者并启动有限调度。 */
@Configuration
@MapperScan(basePackageClasses=FoundationJobMapper.class,annotationClass=org.apache.ibatis.annotations.Mapper.class)
public class FoundationJobsConfiguration {
    /** Spring 收集实际消费者，未注册的代码不会成为可执行任务。 */
    @Bean public FoundationJobService foundationJobService(FoundationJobMapper mapper,FoundationAccess access,FoundationFileService files,FeatureGate gate,ResourceQuota quota,ObjectMapper json,PlatformTransactionManager manager,List<CsvTransferHandler> handlers){return new FoundationJobService(mapper,access,files,gate,quota,json,manager,handlers);}
    /** 明确宿主队列边界，平台与机构不能互相代跑任务。 */
    @Bean public FoundationJobScheduler foundationJobScheduler(FoundationJobService jobs,FoundationFileService files,@Value("${spring.application.name}") String applicationName){if(!applicationName.contains("platform")&&!applicationName.contains("institution"))throw new IllegalStateException("管理任务宿主身份无效");return new FoundationJobScheduler(jobs,files,applicationName.contains("platform"));}
}
