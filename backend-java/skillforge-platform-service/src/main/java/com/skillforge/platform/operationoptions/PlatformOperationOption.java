package com.skillforge.platform.operationoptions;

/** 操作候选只公开稳定身份、名称与辨识信息，不返回配置正文或授权快照。 */
public record PlatformOperationOption(String id, String label, String description) { }
