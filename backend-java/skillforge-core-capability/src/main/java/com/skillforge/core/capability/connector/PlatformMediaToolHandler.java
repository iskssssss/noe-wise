package com.skillforge.core.capability.connector;

/**
 * 返回有序模型内容的平台工具；content 字段是 Text/Image 数组，其余字段是结构化结果。
 * 普通工具的同名业务字段仍是业务数据，Provider 不以字段名猜测图片协议。
 */
public interface PlatformMediaToolHandler extends PlatformToolHandler { }
