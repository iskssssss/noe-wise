package com.skillforge.core.capability.contract;

import java.util.Objects;

/** 只允许指向已发布 revision 的部署引用。 */
public record DeploymentRef(
        String deploymentId,
        ResourceRevisionRef publishedRevision,
        String environment,
        long deploymentRevision) {

    public DeploymentRef {
        deploymentId = required(deploymentId, "deploymentId");
        if (publishedRevision == null) {
            throw new IllegalArgumentException("publishedRevision must not be null");
        }
        if (publishedRevision.status() != ResourceRevisionStatus.PUBLISHED) {
            throw new IllegalArgumentException("deployment must reference a published revision");
        }
        environment = required(environment, "environment");
        if (deploymentRevision <= 0) {
            throw new IllegalArgumentException("deploymentRevision must be positive");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
