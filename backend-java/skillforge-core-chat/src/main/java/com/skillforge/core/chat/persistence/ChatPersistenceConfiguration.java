package com.skillforge.core.chat.persistence;

import com.skillforge.core.chat.observation.SessionObservationMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.context.annotation.Configuration;

/** 会话正文与观测映射归本域装配，不将查询和授权端口注册为 Mapper。 */
@Configuration(proxyBeanMethods = false)
@MapperScans({
        @MapperScan(basePackageClasses = SessionMapper.class),
        @MapperScan(basePackageClasses = SessionObservationMapper.class, annotationClass = Mapper.class)
})
public class ChatPersistenceConfiguration { }
