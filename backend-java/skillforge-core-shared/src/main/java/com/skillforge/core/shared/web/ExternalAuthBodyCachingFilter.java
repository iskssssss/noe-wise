package com.skillforge.core.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求体缓存过滤器。
 *
 * <p>将 {@code /api/v1/**} 请求包装为 {@link CachedBodyHttpServletRequest}（构造时
 * 按独立字节预算预读缓存，已声明长度和实际流均检查）：外部认证拦截器（HMAC 验签 / channel_id 一致性校验）需要读取请求体，
 * 而拦截器读流会消费 body；缓存后每次 {@code getInputStream()} 均返回新流，
 * controller 反序列化不受影响。
 *
 * <p>multipart 请求直接透传（Tomcat part 解析依赖原始流，预读会破坏文件上传）；
 * Open Platform二进制附件/图片使用有界原始字节缓存；原生multipart上传仍直接透传。
 */
@Component
@EnableConfigurationProperties(HttpBodyCachingProperties.class)
public class ExternalAuthBodyCachingFilter extends OncePerRequestFilter {
    private final int maxCachedBodyBytes;
    private final int maxCachedBinaryBodyBytes;

    public ExternalAuthBodyCachingFilter(HttpBodyCachingProperties properties) {
        this.maxCachedBodyBytes = properties.maxCachedBodyBytes();
        this.maxCachedBinaryBodyBytes = properties.maxCachedBinaryBodyBytes();
    }


    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isMultipart(request)) {
            chain.doFilter(request, response);
            return;
        }
        int limit = isOpenPlatformBinaryUpload(request) ? maxCachedBinaryBodyBytes : maxCachedBodyBytes;
        chain.doFilter(new CachedBodyHttpServletRequest(request, limit), response);
    }

    /** 只给两个正式原始文件入口采用宿主注入的二进制缓存上限，普通JSON和其他路径仍用认证前缓存预算。 */
    private boolean isOpenPlatformBinaryUpload(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return false;
        String path = request.getRequestURI();
        if (!"/api/v1/open-platform/sessions/attachments".equals(path)
                && !"/api/v1/open-platform/sessions/media".equals(path)) return false;
        String type = request.getContentType();
        return type != null && "application/octet-stream".equalsIgnoreCase(type.split(";", 2)[0].strip());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    /**
     * 判断是否为 multipart 请求。
     *
     * @param request 当前 HTTP 请求
     * @return Content-Type 以 multipart/ 开头返回 true
     */
    private boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
    }
}
