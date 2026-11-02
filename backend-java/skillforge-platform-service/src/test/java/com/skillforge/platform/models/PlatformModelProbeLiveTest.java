package com.skillforge.platform.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.knowledge.PlatformKnowledgeModelProbe;
import com.skillforge.core.model.ModelCompatibilityReport;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.TimePricingRules;
import com.skillforge.core.model.PlatformModelMapper;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;

/** 显式付费验收同一按钮服务的真实供应商链；草稿和报告存储为夹具，不证明 API、DB 或 UI。 */
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_MODEL_COMPATIBILITY", matches = "true")
@Timeout(210)
class PlatformModelProbeLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String DRAFT = "7001";
    private static final String ENCRYPTED_FIXTURE = "fixture-encrypted-live-credential";

    @Test void deepSeekChatLowUsesTheRealCompatibilityButtonService() throws Exception {
        probe("deepseek-chat-low", "deepseek", "deepseek-api", ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                "SKILLFORGE_DEEPSEEK", "deepseek-flash", ReasoningPolicy.LOW);
    }

    @Test void deepSeekMessagesLowUsesTheRealCompatibilityButtonService() throws Exception {
        probe("deepseek-messages-low", "deepseek", "deepseek-api", ModelProtocol.ANTHROPIC_MESSAGES,
                "SKILLFORGE_DEEPSEEK", "deepseek-flash", ReasoningPolicy.LOW);
    }

    @Test void bailianChatEnabledUsesTheRealCompatibilityButtonService() throws Exception {
        probe("bailian-chat-enabled", "aliyun-bailian", "bailian-cn-beijing", ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                "SKILLFORGE_BAILIAN", "qwen-plus", ReasoningPolicy.ENABLED);
    }

    private void probe(String caseName, String providerId, String serviceId, ModelProtocol protocol,
            String environmentPrefix, String defaultModel, ReasoningPolicy reasoning) throws Exception {
        String credential = required(environmentPrefix + "_API_KEY");
        String configuredModel = System.getenv(environmentPrefix + "_MODEL");
        String model = configuredModel == null || configuredModel.isBlank() ? defaultModel : configuredModel;
        var catalog = ModelProviderCatalog.standard();
        var service = catalog.presets().stream().filter(provider -> provider.providerId().equals(providerId))
                .flatMap(provider -> provider.services().stream()).filter(candidate -> candidate.serviceId().equals(serviceId))
                .findFirst().orElseThrow();
        var declared = service.protocols().stream().filter(candidate -> candidate.protocol() == protocol).findFirst().orElseThrow();
        var authentication = declared.authenticationSchemes().iterator().next();
        var profile = catalog.resolve(new ModelProviderCatalog.Selection(providerId, serviceId, service.profileVersion(), model,
                protocol, authentication, service.endpoint()));
        var config = new PlatformModelConfig(DRAFT, 1, "live compatibility fixture", "LLM", true,
                providerId, serviceId, service.profileVersion(), protocol.id(), authentication.id(), profile.dialectVersion(),
                profile.endpoint(), model, ENCRYPTED_FIXTURE, 8192, 64000, 4096, null, reasoning.name(), 90,
                null, null, null, null, null, "CNY");
        // 默认 prefixCapabilities 未核实；不按模型名自动激活历史 SYSTEM 更新。
        var crypto = mock(CryptoService.class);
        when(crypto.decrypt(ENCRYPTED_FIXTURE)).thenReturn(credential);
        var mapper = mock(PlatformModelMapper.class);
        when(mapper.saveCompatibilityReport(eq(DRAFT), eq(1L), anyString())).thenReturn(1);
        // 只替代草稿读取；SDK 策略校验和同修订报告保存均调用真实所属实现。
        var models = spy(new PlatformModelCatalogService(mapper, crypto, JSON, catalog, mock(TimePricingRules.class)));
        doReturn(config).when(models).draftForProbe(DRAFT);
        var knowledge = mock(PlatformKnowledgeModelProbe.class);
        Path reports = Files.createDirectories(Path.of("build", "reports"));
        Path evidence = Files.createTempDirectory(reports, "platform-model-probe-live-" + caseName + "-").resolve("report.json");
        Files.writeString(evidence, JSON.writeValueAsString(new Evidence("supplier-request-chain-only", caseName, null, false)), StandardCharsets.UTF_8);
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var runtime = spy(ModelProviderRuntime.runtime(http, Clock.systemUTC(), 2 * 1024 * 1024));
            var probes = new PlatformModelProbeService(models, crypto, knowledge, catalog, runtime);
            var result = probes.test(DRAFT);
            var report = result.compatibilityReport();
            assertNotNull(report, "compatibility report missing");
            var persisted = ArgumentCaptor.forClass(String.class);
            verify(mapper).saveCompatibilityReport(eq(DRAFT), eq(1L), persisted.capture());
            assertEquals(report, JSON.readValue(persisted.getValue(), ModelCompatibilityReport.class), "saved compatibility report differs");
            Files.writeString(evidence, JSON.writeValueAsString(new Evidence("supplier-request-chain-only", caseName, report, true)), StandardCharsets.UTF_8);
            assertTrue(result.ok(), () -> failureReasons(report));
            assertEquals(ModelCompatibilityReport.Status.PASSED, report.status(), () -> failureReasons(report));
            for (var check : List.of(report.configurationAcceptance(), report.streaming(), report.toolContinuation()))
                assertEquals(ModelCompatibilityReport.Status.PASSED, check.status(), () -> failureReasons(report));
            verify(runtime, times(2)).stream(eq(providerId), org.mockito.ArgumentMatchers.any());
            verify(runtime, never()).generate(anyString(), org.mockito.ArgumentMatchers.any());
            verify(models).validateReasoning(config);
            verify(models).saveCompatibilityReport(DRAFT, 1, report);
            verifyNoInteractions(knowledge);
        }
    }

    private static String failureReasons(ModelCompatibilityReport report) {
        return "compatibility=" + report.status() + ":" + report.reasonCode()
                + "; acceptance=" + report.configurationAcceptance().reasonCode()
                + "; streaming=" + report.streaming().reasonCode()
                + "; continuation=" + report.toolContinuation().reasonCode();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), () -> "Required environment variable is missing: " + name);
        return value;
    }

    private record Evidence(String scope, String caseName, ModelCompatibilityReport report, boolean reportSaved) { }
}
