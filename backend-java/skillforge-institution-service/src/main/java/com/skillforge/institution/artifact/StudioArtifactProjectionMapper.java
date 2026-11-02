package com.skillforge.institution.artifact;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读查询 Agent 委派子 Run 与产品会话的宿主绑定；SQL 统一维护在同名 XML。 */
@Mapper
public interface StudioArtifactProjectionMapper {
    String bindTenant(@Param("tenant") String tenant);

    List<Row> children(@Param("tenant") String tenant,
                       @Param("productSession") String productSession);

    Row find(@Param("tenant") String tenant,
             @Param("childSession") String childSession,
             @Param("childRun") String childRun);

    /** 子 Run、父主 Agent Run 与来源 Agent 的不可变绑定投影。 */
    final class Row {
        public String childSessionId;
        public String childRunId;
        public String parentRunId;
        public String productSessionId;
        public String originTargetKind;
        public String originTargetId;
        public String sourceAgentId;
        public long endUserId;
    }
}
