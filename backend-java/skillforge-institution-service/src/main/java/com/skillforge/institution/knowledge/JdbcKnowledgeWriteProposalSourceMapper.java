package com.skillforge.institution.knowledge;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 领域内部持久化入口；租户由拦截器在当前事务连接上绑定。 */
@Mapper
public interface JdbcKnowledgeWriteProposalSourceMapper {
    /** 按租户和 bundle 身份读取不可变证据结果；调用方校验唯一性。 */
    List<String> bundles(@Param("tenant") String tenant, @Param("bundle") String bundle);
    /** 读取已提交来源事件内容用于 EvidenceRef 校验，不信任请求自述。 */
    List<String> eventPayloads(@Param("tenant") String tenant, @Param("event") String event);
    /** 读取白名单 Observation 的类型、执行身份与安全输出；调用方校验一致性。 */
    List<Observation> observations(@Param("tenant") String tenant, @Param("event") String event);
    record Observation(String type, String executionId, String payload) { }
}
