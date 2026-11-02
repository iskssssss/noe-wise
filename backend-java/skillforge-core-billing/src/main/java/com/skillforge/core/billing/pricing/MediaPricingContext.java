package com.skillforge.core.billing.pricing;

/** 原媒体 Job 的计价条件；来自原请求，不作为供应商计量报告。 */
public record MediaPricingContext(String videoResolution) { }
