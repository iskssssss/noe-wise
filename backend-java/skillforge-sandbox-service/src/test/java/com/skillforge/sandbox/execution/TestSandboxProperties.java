package com.skillforge.sandbox.execution;

import com.skillforge.sandbox.config.SandboxProperties;
import com.skillforge.execution.ExecutionLimits;

import java.nio.file.Path;
import java.time.Duration;

public final class TestSandboxProperties {

    private TestSandboxProperties() {
    }

    public static com.skillforge.execution.ExecutionPolicy policy() {
        return new com.skillforge.execution.ExecutionPolicy(1, ExecutionLimits.DEFAULT_TIMEOUT_SECONDS, 10, 24, 60, com.skillforge.execution.ExecutionLimits.OUTPUT_CAPTURE_BYTES,
                com.skillforge.execution.ExecutionLimits.MAX_FILES,4,"1g",new java.math.BigDecimal("2"),256);
    }

    public static SandboxProperties create(Path root) {
        return new SandboxProperties(
                "test-service-token-with-at-least-32-characters",
                root,
                "docker",
                "skillforge-sandbox-task",
                "skillforge-sandbox-tasks",
                "skillforge-sandbox-ca",
                "http://sandbox-egress-proxy:8080",
                "skillforge-sandbox-egress-proxy",
                "skillforge-sandbox-python:3.12",
                "skillforge-sandbox-node:24",
                "https://pypi.org/simple",
                "https://registry.npmjs.org",
                Duration.ofSeconds(10),
                "workflow-python-v1",
                "workflow-node-v1",
                "skillforge-sandbox-python:workflow-v1",
                "skillforge-sandbox-node:workflow-v1");
    }
}
