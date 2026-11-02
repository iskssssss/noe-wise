package com.skillforge.core.foundation.integration;

/** 开通时可选的真实启用套餐，编码是既有业务键，不创建新套餐。 */
public record ProvisioningPlanOption(String id, String label, String description) { }
