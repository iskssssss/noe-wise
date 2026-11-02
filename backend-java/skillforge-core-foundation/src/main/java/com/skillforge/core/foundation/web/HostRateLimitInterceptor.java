package com.skillforge.core.foundation.web;

import com.skillforge.core.shared.exception.ApiException;

import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 基于 Redis 的请求限流拦截器。
 *
 * <p>以「租户 + 用户/IP + 分钟窗口」为维度对 API 请求计数，超过配置阈值时返回
 * 429 并携带 {@code Retry-After} 响应头，防止单租户或单用户滥用接口。
 */
public class HostRateLimitInterceptor implements HandlerInterceptor {

    private static final DefaultRedisScript<Long> INCREMENT_WINDOW = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);

    /** Redis 操作模板，用于原子自增计数。 */
    private final StringRedisTemplate redisTemplate;

    /** 版本化运营配置。 */
    private final OperationalSettingsService settings;

    private final Clock clock;

    /**
     * 构造限流拦截器。
     *
     * @param redisTemplate Redis 操作模板
     * @param settings      版本化运营配置
     */
    public HostRateLimitInterceptor(
            StringRedisTemplate redisTemplate, OperationalSettingsService settings) {
        this(redisTemplate, settings, Clock.systemUTC());
    }

    /** 显式时间来源用于统一固定窗口；默认入口继续使用系统UTC时钟。 */
    public HostRateLimitInterceptor(
            StringRedisTemplate redisTemplate, OperationalSettingsService settings, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.settings = settings;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 请求进入控制器前执行限流判断。
     *
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler  目标处理器对象
     * @return true 表示放行，false 表示已写入 429 响应
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 异步 dispatch（SseEmitter 等）跳过：主请求已完成限流计数，异步线程无 Sa-Token 上下文
        if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) {
            return true;
        }
        // 构造限流 key：租户、用户（或 IP）、当前分钟窗口
        String tenant = TenantContext.getTenantId();
        String user;
        try {
            user = StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : request.getRemoteAddr();
        } catch (cn.dev33.satoken.exception.SaTokenException e) {
            // 错误页派发等无上下文阶段按未登录处理
            user = request.getRemoteAddr();
        }
        String key = "rate:" + (tenant == null ? "" : tenant) + ":" + user + ":"
                + (clock.millis() / 60_000);
        // 原子自增并设置 60 秒过期，保证窗口计数独立
        Long count = redisTemplate.execute(INCREMENT_WINDOW, List.of(key), "60");
        int limit = settings.read("rate_limits").integer("requests_per_minute_default");
        if (count != null && count > limit) {
            // 超过阈值：返回 429 与统一错误结构
            response.setHeader("Retry-After", "60");
            throw new ApiException(org.springframework.http.HttpStatus.valueOf(429), "请求过于频繁，请稍后重试");
        }
        return true;
    }
}
