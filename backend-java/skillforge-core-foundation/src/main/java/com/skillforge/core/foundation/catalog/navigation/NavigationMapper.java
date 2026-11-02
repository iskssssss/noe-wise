package com.skillforge.core.foundation.catalog.navigation;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.skillforge.core.foundation.catalog.navigation.NavigationContracts.*;

/** owner 分区的导航配置存储；父关系外键拒绝孤儿和有子节点删除。 */
@Mapper
public interface NavigationMapper {
    /** 事务级 advisory 锁串行化同一树，避免并发环和初始化竞争。 */ void lockOwner(@Param("owner") String owner);
    /** 机构第一次配置复制只读模板，不改变其他机构。 */ int initialize(@Param("owner") String owner,@Param("tenant") String tenant,@Param("actor") String actor);
    /** 已初始化的空树不再回退模板。 */ boolean initialized(@Param("owner") String owner);
    /** 明确保存首次树初始化，即使之后删除所有节点。 */ int markInitialized(@Param("owner") String owner,@Param("tenant") String tenant);
    /** 单树有界节点。 */ List<Node> nodes(@Param("owner") String owner);
    /** CAS 新建。 */ int insert(@Param("owner") String owner,@Param("tenant") String tenant,@Param("command") SaveNode command,@Param("actor") String actor);
    /** CAS 修改；编码恒定。 */ int update(@Param("owner") String owner,@Param("command") SaveNode command,@Param("actor") String actor);
    /** 子节点检查。 */ long children(@Param("owner") String owner,@Param("code") String code);
    /** CAS 删除。 */ int delete(@Param("owner") String owner,@Param("command") DeleteNode command);
}
