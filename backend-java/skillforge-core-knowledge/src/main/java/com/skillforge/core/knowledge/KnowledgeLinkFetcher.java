package com.skillforge.core.knowledge;

import java.time.Duration;

/**
 * Knowledge Runtime 对已发现外部链接的单次受控读取边界。
 *
 * <p>实现负责网络与安全守卫；调用方负责决定链接是否已被当前请求发现、是否有预算，
 * 以及是否把成功响应提升为 EvidenceItem。</p>
 */
public interface KnowledgeLinkFetcher {

    /**
     * 读取一个已授权链接，不应自动跟随重定向。
     *
     * @param url       已发现的 HTTP(S) URL
     * @param maxChars  响应正文最大字符数
     * @param timeout   由可信调用剩余时间计算的执行窗口
     * @return 有界响应
     */
    FetchResult fetch(String url, int maxChars, Duration timeout);

    /** 单链接响应摘要；正文已经过大小限制。 */
    record FetchResult(String url, int statusCode, String contentType, String body, boolean redirected, boolean truncated) {
        public FetchResult {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("url must not be blank");
            }
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("statusCode must be a valid HTTP status");
            }
            contentType = contentType == null ? "" : contentType;
            body = body == null ? "" : body;
        }

        public boolean successful() {
            return statusCode >= 200 && statusCode < 300 && !redirected;
        }
    }
}
