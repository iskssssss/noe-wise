package com.skillforge.institution.auth;

import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.channel.constant.ChannelConstants;
import com.skillforge.core.shared.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Set;
import java.util.Arrays;
import org.springframework.http.HttpStatus;

/**
 * 渠道 IP 白名单校验工具（接口契约 §2.5）。
 *
 * <p>渠道 config 键 {@code ip_whitelist}：逗号分隔字符串；
 * 未配置或空 → 不校验；非空时请求源 IP（{@code getRemoteAddr()}）必须命中。
 */
public final class IpWhitelist {

    private IpWhitelist() {
    }

    /**
     * 校验请求源 IP 是否在渠道白名单内，不在时抛 403。
     *
     * @param channel 渠道
     * @param request 当前 HTTP 请求
     */
    public static void requireAllowed(ChannelDO channel, HttpServletRequest request) {
        Object raw = channel.getConfig() == null
                ? null : channel.getConfig().get(ChannelConstants.IP_WHITELIST);
        Set<String> allowed = parse(raw);
        if (allowed.isEmpty()) {
            return;
        }
        String remote = request.getRemoteAddr();
        if (remote != null && allowed.contains(remote)) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "渠道 IP 不在白名单");
    }

    /**
     * 解析白名单配置为集合。
     *
     * @param raw config 中的 ip_whitelist 值（逗号分隔字符串 / null）
     * @return 去空白后的 IP 集合；空或缺失返回空集合
     */
    private static Set<String> parse(Object raw) {
        if (raw == null) {
            return Collections.emptySet();
        }
        if (raw instanceof String text && !text.isBlank()) {
            return Arrays.stream(text.split(","))
                    .map(String::trim).filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.toSet());
        }
        return Collections.emptySet();
    }
}
