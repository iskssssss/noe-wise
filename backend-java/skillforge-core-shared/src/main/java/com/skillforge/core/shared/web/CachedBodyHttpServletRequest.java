package com.skillforge.core.shared.web;

import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 请求体一次性缓存包装器。
 *
 * <p>构造时预读原始请求体并缓存；此后 {@link #getInputStream()} 每次返回
 * 基于缓存的新流（多消费者安全）——外部认证拦截器（HMAC 验签 / channel_id
 * 一致性校验）与 controller 反序列化可各自独立读取。
 *
 * <p>替代 Spring {@code ContentCachingRequestWrapper}：其 getInputStream()
 * 仅创建一次包装流，拦截器读尽后 controller 收到已消费的流（请求体丢失）。
 *
 * <p>仅用于非 multipart 请求：Tomcat 的 part 解析依赖原始输入流，预读会破坏
 * 文件上传（multipart 由 {@link ExternalAuthBodyCachingFilter} 直接透传）。
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    /** 缓存的请求体字节。 */
    private final byte[] cachedBody;

    /**
     * 构造并预读请求体。
     *
     * @param request 原始请求
     * @param maxBytes 原始字节硬限，独立于 multipart/form 参数限制
     * @throws IOException 读流失败
     */
    public CachedBodyHttpServletRequest(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("缓存字节预算无效");
        }
        if (request.getContentLengthLong() > maxBytes) throw tooLarge();
        // 未知 Content-Length（如 chunked）仍必须按实际字节拒绝；最多保留预算加一个探测字节。
        byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) throw tooLarge();
        this.cachedBody = body;
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "非 multipart 请求体超过缓存字节上限");
    }

    /**
     * 获取缓存的请求体。
     *
     * @return 请求体字节的独立快照，调用方修改不能改变已验签正文
     */
    public byte[] getCachedBody() {
        return cachedBody.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream body = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return body.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // 同步读取，不提供异步监听
            }

            @Override
            public int read() {
                return body.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
