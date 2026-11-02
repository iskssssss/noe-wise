package com.skillforge.core.capability;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 编辑草稿的持久化操作；定义锁和版本竞争由同一宿主事务持有。 */
@Mapper
public interface SkillDraftMapper {
    /** 按已授权归属读取指定记录，可在当前事务中持有行锁；无匹配记录返回空值。 */
    Row find(@Param("tenantId") Long tenantId, @Param("id") long id, @Param("lock") boolean lock);

    /** 读取指定技能的未发布草稿；归属不匹配或不存在时返回空值。 */
    Row active(@Param("tenantId") Long tenantId, @Param("definitionId") long definitionId);

    /** 在当前事务锁定归属匹配的技能定义；返回取得的定义 ID，无匹配返回 null，锁随事务释放。 */
    Long lockDefinition(@Param("tenantId") Long tenantId, @Param("id") long id);

    /** 写入已编码的归属明确草稿；返回影响行数，数据库约束失败由事务回滚。 */
    int insert(@Param("tenantId") Long tenantId, @Param("id") long id,
               @Param("definitionId") long definitionId, @Param("baseVersionId") Long baseVersionId,
               @Param("content") String content);

    /** 按草稿版本条件保存；返回影响行数，版本竞争或已发布时返回零。 */
    int save(@Param("tenantId") Long tenantId, @Param("id") long id,
             @Param("revision") long revision, @Param("content") String content);

    int restore(@Param("tenantId") Long tenantId, @Param("id") long id,
                @Param("revision") long revision, @Param("baseVersionId") Long baseVersionId,
                @Param("content") String content);

    /** 按草稿版本记录发布结果；竞争失败返回零，不重复发布。 */
    int published(@Param("tenantId") Long tenantId, @Param("id") long id,
                  @Param("revision") long revision, @Param("versionId") long versionId,
                  @Param("definitionRevision") long definitionRevision);

    record Row(long id, long definitionId, Long baseVersionId, long revision, String content,
               Long publishedVersionId, Long publishedDefinitionRevision, Instant updatedAt) { }
}
