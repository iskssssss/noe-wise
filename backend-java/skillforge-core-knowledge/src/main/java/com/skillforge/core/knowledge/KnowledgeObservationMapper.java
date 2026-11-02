package com.skillforge.core.knowledge;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 平台知识观测的窄Mapper扫描标记；与机构宿主的标准Mapper扫描共用同一映射定义。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface KnowledgeObservationMapper { }
