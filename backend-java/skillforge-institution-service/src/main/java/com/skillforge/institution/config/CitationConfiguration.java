package com.skillforge.institution.config;

import com.skillforge.core.chat.trajectory.ChatCitationReadService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 机构宿主提供面向会话成员的知识引用读取。 */
@Configuration(proxyBeanMethods = false)
@Import(ChatCitationReadService.class)
public class CitationConfiguration { }
