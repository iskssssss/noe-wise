package com.skillforge.core.chat.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionDO;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Session Webhook 订阅 Mapper。 */
public interface SessionEventSubscriptionMapper extends BaseMapper<SessionEventSubscriptionDO> {
    /** 当前租户的全会话或指定会话活跃订阅；仅返回建立投递责任所需ID，不读取URL和秘密。 */
    List<SessionEventSubscriptionDO> activeForSession(@Param("tenantId") long tenantId,
            @Param("sessionId") String sessionId);
}
