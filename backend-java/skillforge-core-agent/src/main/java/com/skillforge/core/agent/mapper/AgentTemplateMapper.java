package com.skillforge.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentDefinitionOperationRow;

/** Agent 模板 MyBatis-Plus 映射接口。 */
public interface AgentTemplateMapper extends BaseMapper<AgentTemplateDO> {
    /** 调用方已按目录授权限定身份，仅查询展示列。 */
    java.util.List<com.skillforge.core.agent.model.AgentTemplateDisplay> selectDisplayByIds(
            @org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids);
    /** 源机构运营状态只用于共享源准入，不赋予成员或跨机构业务读取权。 */
    boolean sourceTenantAvailable(@org.apache.ibatis.annotations.Param("tenantId") Long tenantId);
    /** 在定义锁内读取持久原回执，调用方已经验证当前动作和目标范围。 */
    AgentDefinitionOperationRow definitionOperation(
            @org.apache.ibatis.annotations.Param("actor") String actor,
            @org.apache.ibatis.annotations.Param("operation") String operation,
            @org.apache.ibatis.annotations.Param("scope") String scope,
            @org.apache.ibatis.annotations.Param("key") String key);
    /** 与原业务变更及审计同事务插入完整安全回执，不存在独立pending提交。 */
    int appendDefinitionOperation(AgentDefinitionOperationRow row);
    /** 同锁内读取或记录机构原保存键封存事实。 */
    boolean definitionSaveFenced(@org.apache.ibatis.annotations.Param("tenant") long tenant,@org.apache.ibatis.annotations.Param("actor") String actor,
            @org.apache.ibatis.annotations.Param("operation") String operation,@org.apache.ibatis.annotations.Param("scope") String scope,@org.apache.ibatis.annotations.Param("key") String key);
    int fenceDefinitionSave(@org.apache.ibatis.annotations.Param("tenant") long tenant,@org.apache.ibatis.annotations.Param("actor") String actor,
            @org.apache.ibatis.annotations.Param("operation") String operation,@org.apache.ibatis.annotations.Param("scope") String scope,
            @org.apache.ibatis.annotations.Param("key") String key,@org.apache.ibatis.annotations.Param("definitionId") Long definitionId);
    /** 恢复完整草稿内容，显式写入可空展示字段；不更新发布指针、身份或安装计数。 */
    int restoreDraftContent(AgentTemplateDO row);
    Long lockFamily(@org.apache.ibatis.annotations.Param("templateKey") String templateKey);
    int incrementInstallCount(@org.apache.ibatis.annotations.Param("templateId") Long templateId);
    int appendPublication(@org.apache.ibatis.annotations.Param("templateId") Long templateId,
            @org.apache.ibatis.annotations.Param("version") String version,
            @org.apache.ibatis.annotations.Param("snapshot") String snapshot);
    String currentPublication(@org.apache.ibatis.annotations.Param("templateId") Long templateId);
    java.util.List<String> currentPublishedAll();
    java.util.List<String> currentPublishedLibrary();
    /** 读取稳定模板身份下的精确发布版本，不遍历其他历史。 */
    String publication(@org.apache.ibatis.annotations.Param("templateId") Long templateId,
            @org.apache.ibatis.annotations.Param("version") String version);
    java.util.List<String> publications(@org.apache.ibatis.annotations.Param("templateId") Long templateId);
}
