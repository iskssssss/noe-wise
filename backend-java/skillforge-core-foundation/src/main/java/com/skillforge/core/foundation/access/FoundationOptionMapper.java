package com.skillforge.core.foundation.access;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只选择当前动作所需的四个公开字段，过滤必须在分页之前完成。 */
@Mapper
public interface FoundationOptionMapper {
    /** 当前机构与资源管理权限的交集。 */
    List<FoundationOptionService.Option> resources(@Param("tenantId") String tenantId, @Param("kind") String kind,
            @Param("filter") FoundationAccess.ResourceFilter filter, @Param("query") FoundationOptionService.Query query);
    /** 当前账号体系和成员范围的交集。 */
    List<FoundationOptionService.Option> members(@Param("scope") String scope, @Param("filter") FoundationAccess.DataScope filter,
            @Param("activeOnly") boolean activeOnly, @Param("query") FoundationOptionService.Query query);
    /** 当前发送范围内确有收件人的部门和角色。 */
    List<FoundationOptionService.Option> recipientGroups(@Param("scope") String scope, @Param("kind") String kind,
            @Param("filter") FoundationAccess.DataScope filter, @Param("query") FoundationOptionService.Query query);
    /** 仅由平台功能授权入口调用。 */
    List<FoundationOptionService.Option> tenants(@Param("query") FoundationOptionService.Query query);
}
