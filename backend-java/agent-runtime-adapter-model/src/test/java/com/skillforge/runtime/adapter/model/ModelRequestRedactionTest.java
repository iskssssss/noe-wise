package com.skillforge.runtime.adapter.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ResolvedDiscovery;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.VerificationStatus;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** 请求对象进入日志时不得通过 record 默认字符串泄露凭据或提示正文。 */
class ModelRequestRedactionTest {

    private static final String CREDENTIAL = "credential-private-secret";
    private static final String CONTENT = "message-private-secret";
    private static final String ENDPOINT = "https://endpoint-private-secret.example/v1";

    @Test
    void providerRequestsExposeOnlySafeMetadata() {
        var discovery = new ModelProvider.DiscoveryRequest(
                new ResolvedDiscovery("provider", "service", "v1",
                        AuthenticationScheme.BEARER_API_KEY, ENDPOINT, ENDPOINT + "/models",
                        "openai-model-list-v1", VerificationStatus.UNVERIFIED),
                CREDENTIAL, Duration.ofSeconds(3));
        var invocation = new ModelProvider.InvocationRequest(
                ENDPOINT, CREDENTIAL, "model-a",
                new ModelProvider.InvocationProfile("service", "v1",
                        ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                        AuthenticationScheme.BEARER_API_KEY, "openai-compatible-chat-v1"),
                List.of(new ModelProvider.Message(
                        ModelProvider.Role.USER, CONTENT, null, List.of())),
                response(), generation(), ReasoningPolicy.DISABLED, List.of(),
                ModelProvider.ToolChoice.NONE, Duration.ofSeconds(3), () -> false, () -> { });

        assertRedacted(discovery.toString());
        assertRedacted(invocation.toString());
    }

    private static ResponseContract response() {
        return new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "test");
    }

    private static GenerationOptions generation() {
        return new GenerationOptions(OptionalInt.of(32), OptionalDouble.empty());
    }

    private static void assertRedacted(String value) {
        assertTrue(value.contains("***"));
        assertFalse(value.contains(CREDENTIAL));
        assertFalse(value.contains(CONTENT));
        assertFalse(value.contains(ENDPOINT));
    }
}
