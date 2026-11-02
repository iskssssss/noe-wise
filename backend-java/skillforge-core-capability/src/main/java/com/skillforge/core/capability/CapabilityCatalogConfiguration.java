package com.skillforge.core.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Catalog 严格 Descriptor Codec 的最小 Spring 装配。 */
@Configuration
public class CapabilityCatalogConfiguration {

    @Bean
    public CapabilityDescriptorCodec capabilityDescriptorCodec(ObjectMapper objectMapper) {
        return new CapabilityDescriptorCodec(objectMapper);
    }

}
