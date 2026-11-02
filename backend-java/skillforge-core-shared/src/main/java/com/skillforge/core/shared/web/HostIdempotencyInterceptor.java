package com.skillforge.core.shared.web;

import com.skillforge.core.shared.exception.ApiException;

import com.skillforge.common.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 基于 Redis 的幂等拦截器。
 *
 * <p>对携带 {@code Idempotency-Key} 请求头的写操作进行去重：同一租户下相同
 * 幂等键只允许首次请求通过，重复请求返回 409，避免网络重试造成重复写库。
 */
public class HostIdempotencyInterceptor implements HandlerInterceptor {

    /** 需要幂等保护的写方法集合。 */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PATCH", "PUT", "DELETE");

    /** Redis 操作模板，用于原子写入幂等标记。 */
    private final StringRedisTemplate redisTemplate;
    private final Set<String> receiptPaths;

    /**
     * 构造幂等拦截器。
     *
     * @param redisTemplate Redis 操作模板
     */
    public HostIdempotencyInterceptor(StringRedisTemplate redisTemplate, Set<String> receiptPaths) {
        this.redisTemplate = redisTemplate;
        this.receiptPaths = Set.copyOf(receiptPaths);
    }

    /**
     * 请求进入控制器前执行幂等检查。
     *
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler  目标处理器对象
     * @return true 表示放行，false 表示已写入 409 响应
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 已有业务回执的入口必须让领域账本核验指纹并重放，不抢先消费其幂等键。
        String path = request.getRequestURI();
        if (receiptPaths.contains(path)) return true;
        // 仅对写方法生效
        if (!WRITE_METHODS.contains(request.getMethod())) {
            return true;
        }
        String idempotencyKey = request.getHeader("Idempotency-Key");
        // 未携带幂等键时不做限制
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }
        // 以已认证租户/身份/方法/路径 + 幂等键作为 Redis 键，24 小时内生效
        String tenant = TenantContext.getTenantId() == null ? "" : TenantContext.getTenantId();
        String actor = com.skillforge.common.context.RequestContext.getUserId();
        var principal = com.skillforge.common.context.ServicePrincipalContext.get();
        var management = com.skillforge.common.context.ManagementContext.get();
        if (management != null) actor = "management_principal:" + management.principalId();
        else if (principal != null) actor = "integration:" + principal.integrationId();
        else if (com.skillforge.common.context.ChannelContext.getChannelId() != null)
            actor = "channel:" + com.skillforge.common.context.ChannelContext.getChannelId();
        if (actor == null) actor = "anonymous:" + request.getRemoteAddr();
        String redisKey = "idem:" + tenant + ":" + actor + ":" + request.getMethod() + ":" + path + ":" + idempotencyKey.trim();
        Boolean first = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", Duration.ofHours(24));
        if (!Boolean.TRUE.equals(first)) {
            // 重复请求：返回 409 与统一错误结构
            throw new ApiException(org.springframework.http.HttpStatus.valueOf(409), "重复请求");
        }
        return true;
    }
}
