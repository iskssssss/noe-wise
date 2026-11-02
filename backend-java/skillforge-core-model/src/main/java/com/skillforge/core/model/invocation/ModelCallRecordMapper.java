package com.skillforge.core.model.invocation;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 本领域实际模型调用材料；身份列与外调账本精确关联。 */
@Mapper
public interface ModelCallRecordMapper {
    record StatusRow(String invocationId, String attemptId, boolean requestRecorded, boolean outcomeRecorded) { }
    List<StatusRow> statuses(@Param("tenant") long tenant, @Param("keys") List<ModelCallRecordReader.Key> keys,
                            @Param("requestSchema") String requestSchema, @Param("outcomeSchema") String outcomeSchema);
    Row find(@Param("tenant") long tenant, @Param("invocation") String invocation, @Param("attempt") String attempt);
    int insertRequest(@Param("tenant") long tenant, @Param("invocation") String invocation, @Param("attempt") String attempt,
                      @Param("schema") String schema, @Param("body") String body);
    int saveOutcome(@Param("tenant") long tenant, @Param("invocation") String invocation, @Param("attempt") String attempt,
                    @Param("schema") String schema, @Param("body") String body);

    final class Row {
        public long tenantId;
        public String invocationId;
        public String attemptId;
        public String requestSchema;
        public String requestJson;
        public String outcomeSchema;
        public String outcomeJson;
        @Override public String toString() { return "ModelCallRecordRow[REDACTED]"; }
    }
}
