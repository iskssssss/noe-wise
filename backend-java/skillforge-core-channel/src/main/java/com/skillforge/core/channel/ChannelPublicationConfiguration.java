package com.skillforge.core.channel;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 提供渠道的宿主必须显式装配本配置，确保发布事务推进渠道路由。 */
@Configuration(proxyBeanMethods = false)
@Import({com.skillforge.core.channel.persistence.ChannelPersistenceConfiguration.class,
        OpenPlatformChannelBindingService.class, ChannelPublicationListener.class})
public class ChannelPublicationConfiguration { }
