package com.skillforge.runtime.adapter.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Message;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Role;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolChoice;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 显式启用的真实供应商验证；每项仅两次短推理，默认测试和 CI 不访问外网。 */
class LiveModelProviderSmokeTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    static final LiveProviderAttemptMeter.Outcome liveMeterOutcome = new LiveProviderAttemptMeter.Outcome();
    @org.junit.jupiter.api.AfterEach void collectLiveAttemptEvidence() { LiveProviderAttemptMeter.snapshot(); }
    @Test
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_DEEPSEEK", matches = "true")
    void deepSeekSyncAndStreamRetainContentAndUsage() throws Exception {
        verify("deepseek", "deepseek-api", null, "SKILLFORGE_DEEPSEEK");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_BAILIAN", matches = "true")
    void bailianSyncAndStreamRetainContentAndUsage() throws Exception {
        verify("aliyun-bailian", required("SKILLFORGE_BAILIAN_SERVICE"),
                required("SKILLFORGE_BAILIAN_ENDPOINT"), "SKILLFORGE_BAILIAN");
    }

    private void verify(String providerId, String serviceId, String endpoint, String environmentPrefix)
            throws Exception {
        String credential = required(environmentPrefix + "_API_KEY");
        String modelId = required(environmentPrefix + "_MODEL");
        try (var sdkResources = new com.skillforge.runtime.adapter.model.transport.SdkHttpResources();
             HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            ModelProvider provider = ModelProviders.runtime(http, Clock.systemUTC(), 1024 * 1024, sdkResources).stream()
                    .filter(candidate -> providerId.equals(candidate.preset().providerId()))
                    .findFirst().orElseThrow();
            var service = provider.preset().services().stream()
                    .filter(candidate -> serviceId.equals(candidate.serviceId())).findFirst().orElseThrow();
            var protocol = service.protocols().getFirst();
            var request = new InvocationRequest(provider.resolveEndpoint(service, endpoint), credential, modelId,
                    new InvocationProfile(service.serviceId(), service.profileVersion(), protocol.protocol(),
                            AuthenticationScheme.BEARER_API_KEY, protocol.dialectVersion()),
                    List.of(new Message(Role.USER, "Reply with only the word OK.", null, List.of())),
                    new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "live-smoke"),
                    new GenerationOptions(OptionalInt.of(128), OptionalDouble.empty()),
                    ReasoningPolicy.DISABLED, List.of(), ToolChoice.NONE, Duration.ofSeconds(45), () -> false, () -> { });
            var syncTicket = LiveProviderAttemptMeter.prepare(providerId, modelId, serviceId + ":sync", 8192, 128, 4096);
            var syncProvider = ModelProviders.runtime(LiveProviderAttemptMeter.client(http, syncTicket), Clock.systemUTC(), 1024 * 1024, sdkResources).stream()
                    .filter(candidate -> providerId.equals(candidate.preset().providerId())).findFirst().orElseThrow();
            var syncResult = syncProvider.generate(request);
            LiveProviderAttemptMeter.result(syncTicket, syncResult);
            var sync = completed(syncResult);
            verifyCompletion(sync, modelId);

            Probe probe = new Probe();
            try {
                var streamTicket = LiveProviderAttemptMeter.prepare(providerId, modelId, serviceId + ":stream", 8192, 128, 4096);
                var streamProvider = ModelProviders.runtime(LiveProviderAttemptMeter.client(http, streamTicket), Clock.systemUTC(), 1024 * 1024, sdkResources).stream()
                        .filter(candidate -> providerId.equals(candidate.preset().providerId())).findFirst().orElseThrow();
                streamProvider.stream(request).subscribe(LiveProviderAttemptMeter.observe(streamTicket, probe));
                assertTrue(probe.done.await(55, TimeUnit.SECONDS), "供应商流未在请求截止后结束");
                assertEquals(null, probe.failureType.get(), "供应商流出现未分类异常");
                var terminal = probe.events.stream().filter(event -> event instanceof StreamEvent.Completed
                        || event instanceof StreamEvent.Rejected || event instanceof StreamEvent.OutcomeUnknown).toList();
                assertEquals(1, terminal.size(), "供应商流必须恰好有一个终态");
                var streamed = assertInstanceOf(StreamEvent.Completed.class, terminal.getFirst()).result();
                verifyCompletion(streamed, modelId);
                String deltas = probe.events.stream().filter(StreamEvent.ContentDelta.class::isInstance)
                        .map(StreamEvent.ContentDelta.class::cast).map(StreamEvent.ContentDelta::content)
                        .collect(java.util.stream.Collectors.joining());
                assertEquals(streamed.content(), deltas, "流式正文与完成结果不一致");
                System.out.printf("LIVE_PROVIDER_PASS provider=%s model=%s syncTokens=%d/%d streamTokens=%d/%d%n",
                        providerId, modelId, sync.usage().inputTokens().orElseThrow(),
                        sync.usage().outputTokens().orElseThrow(), streamed.usage().inputTokens().orElseThrow(),
                        streamed.usage().outputTokens().orElseThrow());
            } finally {
                if (probe.subscription != null) probe.subscription.cancel();
            }
        }
    }

    private static InvocationResult.Completed completed(InvocationResult result) {
        // 失败仅记录分类；不将原始响应、消息或凭据写入测试报告。
        String reason = result instanceof InvocationResult.Rejected rejected ? rejected.reasonCode()
                : result instanceof InvocationResult.OutcomeUnknown unknown ? unknown.reasonCode() : "completed";
        assertTrue(result instanceof InvocationResult.Completed, reason);
        return (InvocationResult.Completed) result;
    }

    private static void verifyCompletion(InvocationResult.Completed result, String modelId) {
        assertEquals(modelId, result.modelId());
        assertNotNull(result.content());
        assertFalse(result.content().isBlank(), "供应商没有返回正文");
        assertEquals("stop", result.finishReasonCode());
        assertTrue(result.toolCalls().isEmpty());
        assertTrue(result.usage().inputTokens().isPresent(), "供应商没有保留输入计量");
        assertTrue(result.usage().outputTokens().isPresent(), "供应商没有保留输出计量");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "缺少测试环境变量 " + name);
        return value;
    }

    private static final class Probe implements Flow.Subscriber<StreamEvent> {
        private final List<StreamEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<String> failureType = new AtomicReference<>();
        private volatile Flow.Subscription subscription;

        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(Long.MAX_VALUE);
        }
        @Override public void onNext(StreamEvent event) { events.add(event); }
        @Override public void onError(Throwable failure) {
            failureType.set(failure.getClass().getSimpleName());
            done.countDown();
        }
        @Override public void onComplete() { done.countDown(); }
    }
}
