package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.port.SessionOwner;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 机构压缩冷却只读来源；只返回准确owner的压缩事件，不水合完整Run。 */
@Mapper
public interface AgentRuntimeCompactionMapper {
    List<Fact> forOwner(@Param("owner") SessionOwner owner, @Param("eventType") String eventType,
            @Param("after") long after, @Param("limit") int limit);

    record Fact(long sequence, String schemaId, String canonicalJson) { }
}
