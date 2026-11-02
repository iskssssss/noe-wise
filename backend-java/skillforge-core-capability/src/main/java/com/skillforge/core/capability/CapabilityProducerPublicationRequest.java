package com.skillforge.core.capability;

import java.util.Map;

/**
 * Producer bridge 的完整发布输入。
 *
 * <p>所有治理相关字段由生产者显式提供；桥接层不会按 CapabilityType 补齐或推导
 * descriptor 字段，也不会从请求正文推断作用域。descriptor 的严格形状由 Catalog
 * service 的 {@link CapabilityDescriptorCodec} 校验。
 */
public record CapabilityProducerPublicationRequest(
        CapabilityCatalogScope scope,
        CapabilityType capabilityType,
        String capabilityKey,
        String versionKey,
        String name,
        String description,
        String contentHash,
        Map<String, Object> descriptor) {
}
