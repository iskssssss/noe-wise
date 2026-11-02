package com.skillforge.core.chat.access;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** Session边界持久化装配，机构宿主按core扫描加载。 */
@Configuration
@MapperScan(basePackageClasses=SessionAccessMapper.class,annotationClass=org.apache.ibatis.annotations.Mapper.class)
public class SessionAccessConfiguration { }
