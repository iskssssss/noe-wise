package com.skillforge.core.workflow.mapper;

import com.skillforge.core.workflow.model.WorkflowInstallationDO;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 同一租户/工作流安装串行写入，修订不可变，当前指针 CAS 切换。 */
public interface WorkflowInstallationMapper {
    void lock(@Param("tenant") long tenant, @Param("workflow") String workflow);
    int insert(WorkflowInstallationDO revision);
    WorkflowInstallationDO find(@Param("tenant") long tenant, @Param("instance") long instance,
            @Param("revision") long revision);
    /** 管理读只加载指定安装身份；缺失版本或容器仍保留安装行供服务按原顺序拒绝。 */
    List<BuilderRead> findBuilderReads(@Param("tenant") long tenant,
            @Param("identities") List<InstallationIdentity> identities);

    record InstallationIdentity(Long instanceId, Long revision) { }

    /** definitionPresent 只表示正文存在，不把运行定义或槽绑定全文带入管理投影。 */
    record BuilderRead(Long tenantId, Long instanceId, Long installationRevision,
            Long definitionVersionId, Map<String, Object> config, String customPrompt,
            Long boundDefinitionVersionId, Long capabilityVersionId, boolean definitionPresent,
            Long containerId, String workflowId) { }
    boolean publishedTarget(@Param("tenant") long tenant, @Param("instance") long instance,
            @Param("revision") long revision, @Param("workflow") String workflow);
    PublishedTarget target(@Param("tenant") long tenant,@Param("instance") long instance,@Param("revision") long revision,
            @Param("definition") long definition);
    record PublishedTarget(long definitionId,long capabilityId,long capabilityVersionId,long timeoutMs) {}
    /** 稳定实例的新执行选版；安装业务配置和当前发布定义独立解析。 */
    CurrentTarget currentTarget(@Param("tenant") long tenant, @Param("instance") long instance);
    record CurrentTarget(long definitionId, long capabilityId, long capabilityVersionId, long timeoutMs,
            long installationRevision, String workflowId) { }
    int activate(@Param("tenant") long tenant, @Param("instance") long instance,
            @Param("expected") long expected, @Param("revision") long revision,
            @Param("definition") long definition);
}
