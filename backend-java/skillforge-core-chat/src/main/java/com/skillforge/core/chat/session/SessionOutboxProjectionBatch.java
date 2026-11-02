package com.skillforge.core.chat.session;

import java.util.List;
import java.util.Objects;

/**
 * 已持锁来源投影的本次计算结果；输出、序号与状态必须在原事务一起生效。
 * @param stateJson 下一次增量处理需要的公开投影状态
 * @param outputs 来源顺序生成的输出命令，空列表仍可推进无公开变化的来源水位
 */
public record SessionOutboxProjectionBatch(String stateJson, List<SessionOutboxOutput> outputs) {
    public SessionOutboxProjectionBatch {
        Objects.requireNonNull(stateJson, "stateJson");
        outputs = List.copyOf(outputs);
    }
}
