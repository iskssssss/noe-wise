package com.skillforge.core.channel.persistence;

import com.skillforge.core.channel.mapper.ChannelMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 渠道定义与发布路由的持久化接口专属包。 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = {ChannelMapper.class, OpenPlatformChannelBindingMapper.class})
public class ChannelPersistenceConfiguration { }
