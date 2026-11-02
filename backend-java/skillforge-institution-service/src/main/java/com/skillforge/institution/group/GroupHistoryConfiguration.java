package com.skillforge.institution.group;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
/** 只登记群公开读取的类型化传输配置。 */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(GroupHistoryProperties.class)
public class GroupHistoryConfiguration { }
