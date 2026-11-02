package com.skillforge.core.knowledge;

import com.skillforge.core.model.embedding.PlatformModelHttpSupport;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.foundation.application.CryptoService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 受控传输核对真实请求序列化与响应解码，探测不进入业务租户计量。 */
class PlatformKnowledgeModelProbeTest {
    @Test void embeddingProbeSendsCurrentDimensionsAndReturnsTheValidatedProviderVectorLength() {
        var fixture = fixture("EMBEDDING", 3, """
                {"data":[{"index":0,"embedding":[0.1,0.2,0.3]}],"usage":{"total_tokens":7}}
                """, """
                {"model":"model","input":["ping"],"dimensions":3,"encoding_format":"float"}
                """);
        var result = fixture.probe.test(fixture.config);
        assertThat(result.ok()).isTrue(); assertThat(result.dimensions()).isEqualTo(3);
        assertThat(result.ranking()).isNull(); assertThat(result.usage().inputTokens()).isNull();
        assertThat(result.usage().totalTokens()).isEqualTo(7L); fixture.server.verify();
    }

    @Test void malformedOrMismatchedVectorsCannotPassWhileTheirReportedUsageIsRetained() {
        for (String data : List.of("[]", "[{\"index\":0,\"embedding\":[1,2]}]",
                "[{\"index\":0,\"embedding\":[0,0,0]}]", "[{\"index\":1,\"embedding\":[1,2,3]}]")) {
            var fixture = fixture("EMBEDDING", 3,
                    "{\"data\":" + data + ",\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}", null);
            var result = fixture.probe.test(fixture.config);
            assertThat(result.ok()).isFalse(); assertThat(result.dimensions()).isNull();
            assertThat(result.usage().inputTokens()).isEqualTo(7L); fixture.server.verify();
        }
    }

    @Test void missingAndInvalidUsageNeverBecomesReportedZero() {
        for (String suffix : List.of("", ",\"usage\":{}", ",\"usage\":{\"prompt_tokens\":\"7\"}",
                ",\"usage\":{\"prompt_tokens\":7,\"total_tokens\":6}")) {
            var fixture = fixture("EMBEDDING", 3,
                    "{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]" + suffix + "}", null);
            var result = fixture.probe.test(fixture.config);
            assertThat(result.ok()).isTrue(); assertThat(result.usage()).isNull(); fixture.server.verify();
        }
    }

    @Test void rerankProbeUsesTheNativeClientAndPreservesProviderOrderScoresAndUsage() {
        var fixture = fixture("RERANK", null, """
                {"output":{"results":[{"index":1,"relevance_score":0.95},{"index":0,"relevance_score":0.2},
                {"index":2,"relevance_score":0.01}]},"usage":{"prompt_tokens":79,"total_tokens":79}}
                """, """
                {"model":"model","input":{"query":"中国的首都是哪里？","documents":["巴黎是法国的首都。","北京是中国的首都。","香蕉是一种水果。"]}}
                """);
        var result = fixture.probe.test(fixture.config);
        assertThat(result.ok()).isTrue(); assertThat(result.dimensions()).isNull();
        assertThat(result.ranking()).containsExactly(new PlatformKnowledgeModelProbe.Ranking(1, 0.95),
                new PlatformKnowledgeModelProbe.Ranking(0, 0.2), new PlatformKnowledgeModelProbe.Ranking(2, 0.01));
        assertThat(result.usage().inputTokens()).isEqualTo(79L); assertThat(result.usage().totalTokens()).isEqualTo(79L);
        fixture.server.verify();
    }

    @Test void emptyPartialOrInvalidRerankResultsCannotPassTheThreeDocumentProbe() {
        for (String ranking : List.of("[]", "[{\"index\":1,\"relevance_score\":0.9}]",
                "[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8},{\"index\":2,\"relevance_score\":0.7}]")) {
            var fixture = fixture("RERANK", null,
                    "{\"output\":{\"results\":" + ranking + "},\"usage\":{\"total_tokens\":7}}", null);
            var result = fixture.probe.test(fixture.config);
            assertThat(result.ok()).isFalse(); assertThat(result.ranking()).isNull();
            assertThat(result.usage().inputTokens()).isNull(); assertThat(result.usage().totalTokens()).isEqualTo(7L);
            fixture.server.verify();
        }
    }

    @Test void credentialAndProviderFailuresAreFailClosedAndSanitized() {
        var crypto = mock(CryptoService.class);
        var http = new PlatformModelHttpSupport(crypto);
        when(crypto.decrypt("encrypted")).thenThrow(new IllegalStateException("decrypt-private-secret"));
        assertThatThrownBy(() -> http.client(model("EMBEDDING", 768))).isInstanceOf(IllegalStateException.class)
                .hasMessage("platform_model_credential_unavailable").hasNoCause();
        var failure = HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "bad", HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"provider-private-secret\"}}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(http.friendlyError(failure)).isEqualTo("模型服务请求被拒绝（HTTP 401）").doesNotContain("provider-private-secret");
        assertThat(http.friendlyError(new IllegalStateException("network-private-secret"))).isEqualTo("模型服务连接失败");
    }

    private Fixture fixture(String kind, Integer dimensions, String response, String expectedRequest) {
        var config = model(kind, dimensions);
        var http = mock(PlatformModelHttpSupport.class);
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        when(http.client(config)).thenReturn(builder.build());
        String path = kind.equals("EMBEDDING") ? "/embeddings" : "/rerank";
        when(http.endpoint(config, path)).thenReturn("https://controlled.example/probe");
        var expectation = server.expect(requestTo("https://controlled.example/probe")).andExpect(method(HttpMethod.POST));
        if (expectedRequest != null) expectation.andExpect(content().json(expectedRequest, true));
        expectation.andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        return new Fixture(new PlatformKnowledgeModelProbe(http), config, server);
    }

    private record Fixture(PlatformKnowledgeModelProbe probe, PlatformModelConfig config, MockRestServiceServer server) { }

    private PlatformModelConfig model(String kind, Integer dimensions) {
        boolean rerank = kind.equals("RERANK");
        return new PlatformModelConfig("81", 1, kind, kind, true,
                "aliyun-bailian", rerank ? "bailian-cn-beijing-rerank" : "bailian-cn-beijing-embedding",
                "2026-10-25-cn-v1", rerank ? "openai-rerank" : "openai-embeddings", "bearer-api-key", "bailian-native-v1",
                rerank ? "https://dashscope.aliyuncs.com" : "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "model", "encrypted", null, null, null, null, null, 20, dimensions,
                rerank ? "/api/v1/services/rerank/text-rerank/text-rerank" : null, null, null, null, "CNY");
    }
}
