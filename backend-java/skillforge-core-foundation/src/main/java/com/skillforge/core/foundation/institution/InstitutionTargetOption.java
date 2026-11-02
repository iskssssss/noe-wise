package com.skillforge.core.foundation.institution;

/** 机构动作候选的最小安全投影，不携带机构配置或关系/许可明细。 */
public record InstitutionTargetOption(String id, String label, String description) { }
