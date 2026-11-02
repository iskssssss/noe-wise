package com.skillforge.core.foundation.settings;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 运营配置版本、当前指针与平台审计账本映射器。 */
@Mapper
public interface OperationalSettingsMapper {

    /** 注册分组的当前指针快照；空集合由调用方跳过。 */
    List<OperationalSettingHeadRow> selectCurrents(@Param("groupKeys") List<String> groupKeys);

    /** 只读取已捕获的精确分组/修订，不重新连接当前指针。 */
    List<OperationalSettingVersionRow> selectVersions(@Param("heads") List<OperationalSettingHeadRow> heads);

    OperationalSettingHeadRow selectCurrent(@Param("groupKey") String groupKey);
    /** 当前事务内共享锁阻止对应分组指针切换，锁在调用方事务结束时释放。 */
    OperationalSettingHeadRow lockCurrentForShare(@Param("groupKey") String groupKey);

    OperationalSettingVersionRow selectVersion(
            @Param("groupKey") String groupKey, @Param("revision") long revision);

    List<OperationalSettingVersionRow> selectHistory(@Param("groupKey") String groupKey);

    int insertVersion(
            @Param("groupKey") String groupKey,
            @Param("revision") long revision,
            @Param("values") Map<String, Object> values,
            @Param("actorId") Long actorId);

    int insertHead(
            @Param("groupKey") String groupKey,
            @Param("revision") long revision,
            @Param("actorId") Long actorId);

    int compareAndSetHead(
            @Param("groupKey") String groupKey,
            @Param("expectedRevision") long expectedRevision,
            @Param("newRevision") long newRevision,
            @Param("actorId") long actorId);

    int insertAudit(
            @Param("groupKey") String groupKey,
            @Param("operation") String operation,
            @Param("fromRevision") long fromRevision,
            @Param("toRevision") long toRevision,
            @Param("targetRevision") Long targetRevision,
            @Param("actorId") long actorId,
            @Param("payload") Map<String, Object> payload);
}
