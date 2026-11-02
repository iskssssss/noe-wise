package com.skillforge.core.model.invocation;

/** 正式版本材料的可读存在性，不加载请求或返回正文。 */
public record ModelCallRecordStatus(boolean requestRecorded, boolean outcomeRecorded) { }
