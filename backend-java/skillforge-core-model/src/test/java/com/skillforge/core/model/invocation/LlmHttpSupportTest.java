package com.skillforge.core.model.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.foundation.application.CryptoService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/** LLM HTTP 错误脱敏与密钥失败关闭测试。 */
class LlmHttpSupportTest {

    @Test
    void friendlyErrorDoesNotExposeProviderMessages() {
        LlmHttpSupport support = new LlmHttpSupport(mock(CryptoService.class));
        HttpClientErrorException structured = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "bad", HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"provider-private-secret\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        HttpClientErrorException stringError = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "bad", HttpHeaders.EMPTY,
                "{\"error\":\"credential-private-secret\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThat(support.friendlyError(structured)).isEqualTo("模型服务请求被拒绝（HTTP 400）");
        assertThat(support.friendlyError(stringError)).isEqualTo("模型服务请求被拒绝（HTTP 401）");
        assertThat(support.friendlyError(new Exception("network-private-secret")))
                .isEqualTo("模型服务连接失败");
    }

    @Test
    void apiKeyResolutionFailsClosedWithoutPlaintextFallback() {
        CryptoService crypto = mock(CryptoService.class);
        LlmHttpSupport support = new LlmHttpSupport(crypto);
        when(crypto.decrypt("encrypted")).thenReturn("plain");
        assertThat(support.resolveApiKey("encrypted")).isEqualTo("plain");
        when(crypto.decrypt("stored-private-secret"))
                .thenThrow(new IllegalStateException("decrypt-private-secret"));
        assertThatThrownBy(() -> support.resolveApiKey("stored-private-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("llm_credential_unavailable")
                .hasNoCause();
        assertThatThrownBy(() -> support.resolveApiKey(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("llm_credential_unavailable");
    }
}
