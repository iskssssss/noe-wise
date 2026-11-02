package com.skillforge.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.embedding.PlatformModelHttpSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 官方响应协议回执测试；只访问 Mock HTTP，不调用供应商或推算实际 Token。 */
class RerankClientUsageTest {
    @Test void dashScopePreservesOfficialPromptAndTotalAlongsideNestedRanking() {
        var result = call(true, """
                {"output":{"results":[{"index":0,"relevance_score":0.9334521178273196},
                {"index":2,"relevance_score":0.34100082626411193}]},
                "usage":{"prompt_tokens":79,"total_tokens":79},"request_id":"official-example"}
                """);
        assertThat(result.results()).containsExactly(new RerankProvider.RerankResult(0, 0.9334521178273196),
                new RerankProvider.RerankResult(2, 0.34100082626411193));
        assertThat(result.usage().promptTokens()).isEqualTo(79L);
        assertThat(result.usage().totalTokens()).isEqualTo(79L);
        assertThat(result.usage().completionTokens()).isNull();
        assertThat(result.usage().usageSource()).isEqualTo(UsageSource.PROVIDER_REPORTED);
    }

    @Test void topLevelRankingKeepsReportedZeroAndLargeIntegerTokensExact() {
        var result = call(false, """
                {"results":[{"index":0,"relevance_score":0.5}],
                 "usage":{"prompt_tokens":0,"total_tokens":9007199254740993}}
                """);
        assertThat(result.results()).hasSize(1);
        assertThat(result.usage().promptTokens()).isZero();
        assertThat(result.usage().totalTokens()).isEqualTo(9007199254740993L);
    }

    @Test void absentUsageDoesNotTurnRankingSuccessIntoReportedZero() {
        var result = call(true, "{\"output\":{\"results\":[]}}");
        assertThat(result.results()).isEmpty();
        assertThat(result.usage().promptTokens()).isNull();
        assertThat(result.usage().totalTokens()).isNull();
        assertThat(result.usage().usageSource()).isEqualTo(UsageSource.UNKNOWN);
    }

    @Test void totalOnlyReportDoesNotInventAnInputOrOutputCount() {
        var result = call(true, "{\"output\":{\"results\":[]},\"usage\":{\"total_tokens\":79}}");
        assertThat(result.usage().promptTokens()).isNull();
        assertThat(result.usage().completionTokens()).isNull();
        assertThat(result.usage().totalTokens()).isEqualTo(79L);
        assertThat(result.usage().usageSource()).isEqualTo(UsageSource.PROVIDER_REPORTED);
    }

    @Test void invalidTokenValuesStayUnknownWithoutDiscardingRankingOrOtherReportedFields() {
        for (String invalid : List.of("1.5", "1e3", "-1", "9223372036854775808", "\"79\"", "true", "{}")) {
            var result = call(true, "{\"output\":{\"results\":[]},\"usage\":{\"prompt_tokens\":"
                    + invalid + ",\"total_tokens\":0}}");
            assertThat(result.results()).isEmpty();
            assertThat(result.usage().promptTokens()).as(invalid).isNull();
            assertThat(result.usage().totalTokens()).isZero();
        }
    }

    @Test void missingRankingStillRetainsActualUsage() {
        var result = call(true, "{\"usage\":{\"prompt_tokens\":79,\"total_tokens\":79}}");
        assertThat(result.results()).isNull();
        assertThat(result.usage().promptTokens()).isEqualTo(79L);
    }

    @Test void impossibleTotalBelowInputKeepsRankingButCannotBecomeChargeableUsage() {
        var result = call(true, "{\"output\":{\"results\":[]},\"usage\":{\"prompt_tokens\":79,\"total_tokens\":78}}");
        assertThat(result.results()).isEmpty();
        assertThat(result.usage().promptTokens()).isNull();
        assertThat(result.usage().totalTokens()).isNull();
        assertThat(result.usage().usageSource()).isEqualTo(UsageSource.UNKNOWN);
    }

    @Test void largerTotalDoesNotRewriteExplicitInputOrInventOutput() {
        var result = call(false, "{\"results\":[],\"usage\":{\"prompt_tokens\":79,\"total_tokens\":80}}");
        assertThat(result.usage().promptTokens()).isEqualTo(79L);
        assertThat(result.usage().totalTokens()).isEqualTo(80L);
        assertThat(result.usage().completionTokens()).isNull();
    }

    @Test void invalidRankingNeverBecomesAPartialSuccessAndKeepsReportedUsage() {
        for (String ranking : List.of(
                "[{\"index\":1.5,\"relevance_score\":0.8}]",
                "[{\"index\":\"1\",\"relevance_score\":0.8}]",
                "[{\"index\":3,\"relevance_score\":0.8}]",
                "[{\"index\":0,\"relevance_score\":\"0.8\"}]",
                "[{\"index\":0,\"relevance_score\":1.1}]",
                "[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}]",
                "[{\"index\":0,\"relevance_score\":0.2},{\"index\":1,\"relevance_score\":0.9}]")) {
            var result = call(true, "{\"output\":{\"results\":" + ranking + "},\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}");
            assertThat(result.results()).as(ranking).isNull();
            assertThat(result.usage().promptTokens()).isEqualTo(7L);
        }
    }

    @Test void factoryUsesExactFrozenBailianServiceInsteadOfHostnameSubstrings() {
        var factory = new RerankClientFactory(mock(PlatformModelHttpSupport.class));
        var nativeConfig = mock(PlatformModelConfig.class);
        when(nativeConfig.providerId()).thenReturn("aliyun-bailian");
        when(nativeConfig.serviceId()).thenReturn("bailian-cn-beijing-rerank");
        when(nativeConfig.endpoint()).thenReturn("https://workspace.cn-beijing.maas.aliyuncs.com");
        assertThat(factory.forConfig(nativeConfig)).isInstanceOf(DashScopeRerankClient.class);
        var custom = mock(PlatformModelConfig.class);
        when(custom.providerId()).thenReturn("custom");
        when(custom.serviceId()).thenReturn("custom-rerank");
        when(custom.endpoint()).thenReturn("https://dashscope.aliyuncs.com.foreign.example");
        assertThat(factory.forConfig(custom)).isInstanceOf(OpenAiCompatibleRerankClient.class);
    }

    @Test void genericLocalScoresKeepFiniteLogitsAndOriginalOrderWithoutBailianRangeRules() {
        var result = call(false, """
                {"results":[{"index":0,"relevance_score":-0.5},{"index":1,"relevance_score":2.5}],
                "usage":{"total_tokens":7}}
                """);
        assertThat(result.results()).containsExactly(new RerankProvider.RerankResult(0, -0.5),
                new RerankProvider.RerankResult(1, 2.5));
        assertThat(result.usage().totalTokens()).isEqualTo(7L);
    }

    private RerankClient.Response call(boolean dashScope, String response) {
        var http = mock(PlatformModelHttpSupport.class);
        var config = mock(PlatformModelConfig.class);
        when(config.modelId()).thenReturn("qwen3.7-text-rerank");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        when(http.client(config)).thenReturn(builder.build());
        when(http.endpoint(config, "/rerank")).thenReturn("https://provider.example/rerank");
        server.expect(requestTo("https://provider.example/rerank"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        RerankClient client = dashScope ? new DashScopeRerankClient(http) : new OpenAiCompatibleRerankClient(http);
        var result = client.postRerank(config, "query", List.of("document one", "document two", "document three"));
        server.verify();
        return result;
    }
}
