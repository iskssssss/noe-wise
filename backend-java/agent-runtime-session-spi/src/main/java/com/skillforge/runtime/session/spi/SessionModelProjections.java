package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.ResultRef;
import java.util.*;

/** 模型安全投影的纯冻结策略；raw结果只由typed ExecutionStore保存，不保留第二份Projection journal。 */
public interface SessionModelProjections {
    /** 固定转换程序版本及完整资源预算；由受信装配登记，不能从模型响应指定。 */
    record Policy(ResultRef reference, String version, long maxOutputCharacters,
                  int maxCodeUnits, int maxNodes, int maxDepth) {
        public Policy {
            Objects.requireNonNull(reference); Objects.requireNonNull(version);
            if (version.isBlank() || maxOutputCharacters < 2 || maxCodeUnits <= 0 || maxNodes <= 0
                    || maxDepth <= 0 || maxDepth > 128) throw invalid();
        }
    }

    Optional<Policy> policy(ResultRef reference);
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("session_model_projection_invalid");}
}
