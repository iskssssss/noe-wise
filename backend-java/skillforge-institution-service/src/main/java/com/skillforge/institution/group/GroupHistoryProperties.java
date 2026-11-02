package com.skillforge.institution.group;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** SSE生命周期和当前授权复验节奏；显式非法配置阻止组件启动，不静默修正。 */
@ConfigurationProperties("skillforge.groups.history")
public record GroupHistoryProperties(@DefaultValue("60s") Duration streamLifetime,
        @DefaultValue("15s") Duration heartbeat,@DefaultValue("5s") Duration recheckInterval) {
    public GroupHistoryProperties {
        for(var value:new Duration[]{streamLifetime,heartbeat,recheckInterval})
            if(value==null||value.isNegative()||value.isZero())throw new IllegalArgumentException("group_history_duration_invalid");
    }
}
