package com.skillforge.core.foundation.integration;

import com.skillforge.core.foundation.access.FoundationAccess.DataScope;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 管理主体、固定目标、幂等回执及平台当前对象范围的唯一 SQL 写入边界。 */
@Mapper
public interface ManagementMapper {
    /** 按稳定 ID 读取当前主体和固定权限。 */
    ManagementRows.Principal principal(@Param("principalId") long principalId);
    /** 验签按摘要读取当前主体，不读取任何旧 Application。 */
    ManagementRows.Principal forAuthentication(@Param("keyHash") String keyHash);
    /** 管理目录先按创建人员范围过滤再分页。 */
    List<ManagementRows.Principal> principals(@Param("filter") DataScope filter, @Param("limit") int limit, @Param("offset") long offset);
    /** 与分页完全相同过滤的总量。 */
    long count(@Param("filter") DataScope filter);
    /** 已授权新主体入库，秘密只有摘要及密文。 */
    int insertPrincipal(ManagementRows.Principal row);
    /** 批量写入精确动作。 */
    int insertScopes(@Param("principalId") long principalId, @Param("scopes") List<String> scopes);
    /** 写入一种固定目标，值均来自服务端严格校验。 */
    int insertTargets(@Param("principalId") long principalId, @Param("kind") String kind, @Param("targets") List<String> targets);
    /** 轮换只更新预期 active 修订，CAS 失败不得覆盖。 */
    int rotate(@Param("principalId") long principalId, @Param("expectedRevision") long expectedRevision,
            @Param("keyHash") String keyHash, @Param("keyPrefix") String keyPrefix, @Param("secret") String secret);
    /** 撤销同时提高修订及凭据版本，清空加密秘密。 */
    int revoke(@Param("principalId") long principalId, @Param("expectedRevision") long expectedRevision);
    /** 唯一幂等键插入在当前事务内持有；重复请求等待同一行提交。 */
    int claimReceipt(@Param("actorRef") String actorRef, @Param("operation") String operation,
            @Param("objectScope") String objectScope, @Param("key") String key, @Param("fingerprint") String fingerprint);
    /** 同一幂等行串行核验原请求，只有完成结果能够提交。 */
    ManagementRows.Receipt receipt(@Param("actorRef") String actorRef, @Param("operation") String operation,
            @Param("objectScope") String objectScope, @Param("key") String key);
    /** 保存原操作安全响应；result 不允许含明文或加密秘密。 */
    int completeReceipt(@Param("actorRef") String actorRef, @Param("operation") String operation,
            @Param("objectScope") String objectScope, @Param("key") String key, @Param("result") String result);
    /** 校验固定机构/定义真实存在；kind 由内部白名单生成。 */
    boolean targetExists(@Param("kind") String kind, @Param("id") long id);
    /** 平台当前人员、部门、角色目标授权；平台权限动作仍由 FoundationAccess 提供。 */
    List<Long> allowedTargets(@Param("kind") String kind, @Param("action") String action, @Param("filter") DataScope filter);
    /** 判断单一对象的当前管理范围；授权闭包先于对象公开详情。 */
    boolean targetAllowed(@Param("kind") String kind, @Param("id") long id, @Param("action") String action, @Param("filter") DataScope filter);
    /** 命名空间由明确人员/角色授予，不由 provision 动作推断全局范围。 */
    boolean namespaceAllowed(@Param("namespace") String namespace, @Param("action") String action, @Param("filter") DataScope filter);
    /** 列出精确动作当前授予的命名空间，复用同一人员/部门/角色授权谓词。 */
    List<String> allowedNamespaces(@Param("action") String action, @Param("filter") DataScope filter);
    /** 当前人员能否安全管理该主体，按创建人员/部门范围过滤。 */
    boolean principalAllowed(@Param("principalId") long principalId, @Param("filter") DataScope filter);
}
