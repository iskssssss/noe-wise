package com.skillforge.runtime.adapter.jdbc.memory;

import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/** 宿主投影约束的MyBatis事务钩子；已先锁head，禁止另开会话或提交/回滚/关闭当前会话。 */
@FunctionalInterface
public interface MemoryTransitionGuard {
    /** 只在适配器初始化时注册宿主自己的辅助Mapper；不得在此访问数据库。 */
    default void configure(Configuration configuration) { }

    /** 在已绑定租户的当前Memory会话中检查附加迁移条件；拒绝不能被当成已迁移。 */
    boolean allows(SqlSession session, MemoryCommand.Transition command, MemoryFact current);
}
