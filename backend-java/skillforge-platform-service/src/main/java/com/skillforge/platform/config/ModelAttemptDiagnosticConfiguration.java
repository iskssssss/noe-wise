package com.skillforge.platform.config;

import com.skillforge.platform.sessionmodelattempt.ModelAttemptDiagnosticMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 平台私有诊断 Mapper 独立装配，不扩大公共观测查询的内容权限。 */
@Configuration
@MapperScan(basePackageClasses = ModelAttemptDiagnosticMapper.class,
        annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class ModelAttemptDiagnosticConfiguration {
}
