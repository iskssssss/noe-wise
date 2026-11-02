package com.skillforge.core.capability;

/** Producer bridge 返回的已发布版本及本次请求是否复用了既有版本。 */
public record CapabilityProducerPublicationResult(
        CapabilityDefinitionView definition,
        CapabilityVersionView version,
        boolean idempotent) {

    public enum Status {
        PUBLISHED,
        IDEMPOTENT
    }

    public Status status() {
        return idempotent ? Status.IDEMPOTENT : Status.PUBLISHED;
    }
}
