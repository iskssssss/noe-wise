package com.skillforge.institution.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryScope;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 宿主 HTTP 显式投影 Runtime 封闭归属；领域 API 不依赖 Jackson。 */
@Configuration
class MemoryHttpSerialization {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer memoryOwnerSerialization() {
        return builder->builder.mixIn(MemoryOwner.class,OwnerMixin.class).mixIn(MemoryFact.class,FactMixin.class);
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME,include=JsonTypeInfo.As.PROPERTY,property="scope")
    @JsonSubTypes({@JsonSubTypes.Type(value=MemoryOwner.Session.class,name="SESSION"),
            @JsonSubTypes.Type(value=MemoryOwner.UserAgent.class,name="USER_AGENT"),
            @JsonSubTypes.Type(value=MemoryOwner.User.class,name="USER"),
            @JsonSubTypes.Type(value=MemoryOwner.Group.class,name="GROUP"),
            @JsonSubTypes.Type(value=MemoryOwner.GroupMember.class,name="GROUP_MEMBER")})
    private interface OwnerMixin { }

    private interface FactMixin {
        @JsonProperty("scope") MemoryScope scope();
    }
}
