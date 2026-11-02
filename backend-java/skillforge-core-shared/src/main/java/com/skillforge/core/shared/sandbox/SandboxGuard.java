package com.skillforge.core.shared.sandbox;

import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 沙箱安全守卫。
 *
 * <p>在连接器 HTTP 执行前校验目标 URL：强制 HTTPS（调试环境允许 localhost HTTP）、
 * 按域名白名单过滤、解析并拦截内网/回环/链路本地等 SSRF 地址，防止 DNS rebinding。
 */
@Component
public class SandboxGuard {

    /** 应用配置。 */
    private final SkillForgeProperties properties;

    /**
     * 构造沙箱守卫。
     *
     * @param properties 应用配置
     */
    public SandboxGuard(SkillForgeProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验目标 URL 是否允许调用。
     *
     * @param url 目标 URL
     * @return 规范化后的 URL
     */
    public String assertSafe(String url) {
        if (url == null || url.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "目标 URL 为空");
        }
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "目标 URL 缺少主机");
        }
        boolean isLoopback = isLoopbackHost(host);
        if (!"https".equals(scheme) && !(isLoopback && properties.isDebug())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "仅允许 HTTPS 请求（调试环境允许 localhost HTTP）");
        }
        if (!isDomainAllowed(host) && !isLoopback) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "目标域名不在白名单中: " + host);
        }
        for (InetAddress address : resolveAll(host)) {
            if (!isSafeAddress(address, isLoopback)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "目标地址为内网/保留地址，已拦截: " + address.getHostAddress());
            }
        }
        return url;
    }

    /**
     * 判断主机是否为回环主机名。
     *
     * @param host 主机名
     * @return 是否回环
     */
    private boolean isLoopbackHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(lower) || "127.0.0.1".equals(lower) || "::1".equals(lower);
    }

    /**
     * 判断主机是否在配置的域名白名单内。
     *
     * @param host 主机名
     * @return 是否允许
     */
    private boolean isDomainAllowed(String host) {
        String raw = properties.getSandboxAllowedDomains();
        if (raw == null || raw.isBlank()) {
            return true;
        }
        List<String> allowed = Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
        String lower = host.toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(domain ->
                lower.equals(domain) || lower.endsWith("." + domain));
    }

    /**
     * 解析主机全部 IP，用于 DNS rebinding 防护。
     *
     * @param host 主机名
     * @return IP 列表
     */
    private List<InetAddress> resolveAll(String host) {
        try {
            return Arrays.asList(InetAddress.getAllByName(host));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "目标域名解析失败: " + host, e);
        }
    }

    /**
     * 判断 IP 是否允许访问。
     *
     * @param address    IP 地址
     * @param isLoopback 是否回环主机
     * @return 是否安全
     */
    private boolean isSafeAddress(InetAddress address, boolean isLoopback) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return isLoopback;
        }
        if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
            return false;
        }
        if (address instanceof Inet6Address ipv6) {
            byte[] bytes = ipv6.getAddress();
            if (bytes.length == 16 && (bytes[0] & 0xFF) == 0xfc) {
                return false;
            }
        }
        return true;
    }
}
