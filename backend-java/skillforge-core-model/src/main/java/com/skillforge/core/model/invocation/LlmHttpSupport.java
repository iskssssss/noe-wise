package com.skillforge.core.model.invocation;

import com.skillforge.core.foundation.application.CryptoService;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * LLM 外部调用公共支撑（HTTP 错误转译 / API Key 解密 / 鉴权客户端构建）。
 *
 * <p>API Key 解析由宿主后台模型门面、Embedding 与 Rerank 调用共同复用，
 * REST 客户端只供尚未迁入 Runtime 的 Embedding 与 Rerank 使用。
 */
@Component
public class LlmHttpSupport {

    /** 加密服务，用于解密 API Key。 */
    private final CryptoService cryptoService;

    /**
     * 构造 LLM HTTP 支撑组件。
     *
     * @param cryptoService 加密服务
     */
    public LlmHttpSupport(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    /**
     * 将外部错误转为稳定的安全类别，不返回供应商正文或原始异常消息。
     *
     * @param e 异常
     * @return 友好错误信息
     */
    public String friendlyError(Exception e) {
        if (e instanceof HttpStatusCodeException hse) {
            return "模型服务请求被拒绝（HTTP " + hse.getStatusCode().value() + "）";
        }
        return "模型服务连接失败";
    }

    /**
     * 解析加密存储的 API Key；任何缺失、空值或解密失败均拒绝继续外调。
     *
     * @param stored 存储值
     * @return 明文 Key
     */
    public String resolveApiKey(String stored) {
        try {
            if (stored == null || stored.isBlank()) {
                throw new IllegalArgumentException("credential_missing");
            }
            String resolved = cryptoService.decrypt(stored);
            if (resolved == null || resolved.isBlank()) {
                throw new IllegalArgumentException("credential_empty");
            }
            return resolved;
        } catch (RuntimeException invalidCredential) {
            throw new IllegalStateException("llm_credential_unavailable");
        }
    }

}
