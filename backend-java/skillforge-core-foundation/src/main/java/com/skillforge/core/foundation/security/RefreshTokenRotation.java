package com.skillforge.core.foundation.security;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** 校验主体后原子消费刷新凭据；竞争失败不签发，消费后的签发失败不恢复旧凭据。 */
public final class RefreshTokenRotation {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private RefreshTokenRotation() { }

    /** 只删除仍属于已校验主体的原 key；失效账号由调用方拒绝，旧 key 自然到期。 */
    public static boolean consume(StringRedisTemplate redis, String key, String memberId) {
        Long result = redis.execute(CONSUME, List.of(key), memberId);
        if (result == null || result < 0 || result > 1)
            throw new IllegalStateException("刷新凭据消费结果无效");
        return result == 1;
    }
}
