package com.skillforge.core.agent.systemagent;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 平台系统 Agent 可挂载资源与工具部署的窄持久化边界；SQL 集中在 XML。 */
@Mapper
public interface PlatformAgentResourceMapper {

    List<String> optionSkills();

    List<String> optionTools();

    List<String> optionDeployments();

    List<String> deployments();
    String draft(@Param("id") String id);
    List<String> history(@Param("id") String id);
    int createDraft(@Param("id") String id, @Param("config") String config);
    int saveDraft(@Param("id") String id, @Param("expectedRevision") long expectedRevision,
            @Param("config") String config);
    int publishDraft(@Param("id") String id, @Param("expectedRevision") long expectedRevision,
            @Param("revision") long revision);
    int reserveReceipt(@Param("actor") String actor, @Param("requestId") String requestId,
            @Param("fingerprint") String fingerprint);
    Receipt receipt(@Param("actor") String actor, @Param("requestId") String requestId);
    int completeReceipt(@Param("actor") String actor, @Param("requestId") String requestId,
            @Param("response") String response);
    record Receipt(String fingerprint, String response) { }

    String deployment(@Param("id") String id);

    String deploymentRevision(@Param("id") String id, @Param("revision") long revision);

    String capability(@Param("type") String type, @Param("versionId") String versionId);
    String currentCapability(@Param("type") String type, @Param("definitionId") String definitionId);

    /** 为已受理的系统 Agent 运行打开最小只读 RLS 视图，不变更 tenant 或管理员上下文。 */
    String bindRuntimeRead();


    int appendRevision(
            @Param("id") String id,
            @Param("revision") long revision,
            @Param("name") String name,
            @Param("toolVersionId") String toolVersionId,
            @Param("toolDefinitionId") String toolDefinitionId,
            @Param("providerType") String providerType,
            @Param("providerRef") String providerRef,
            @Param("environment") String environment,
            @Param("active") boolean active,
            @Param("actor") String actor);

    int audit(
            @Param("id") String id,
            @Param("revision") long revision,
            @Param("operation") String operation,
            @Param("actor") String actor,
            @Param("payload") String payload);
}
