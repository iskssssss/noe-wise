package com.skillforge.institution.memory;

import com.skillforge.runtime.adapter.jdbc.memory.MemoryTransitionGuard;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.springframework.stereotype.Component;

/** 自动衰减在Runtime已锁head的同一会话重验投影，统一保持head到projection的锁顺序。 */
@Component
public final class MemoryDecayTransitionGuard implements MemoryTransitionGuard {
    /** 自动时间衰减理由由宿主调度和本钩子共用；普通治理迁移不采用时间阈值。 */
    static final String TIME_DECAY_REASON = "memory_time_decay_threshold";

    /** 将宿主已有Mapper绑定到Runtime当前工厂；不使用Spring代理或另开连接。 */
    @Override
    public void configure(Configuration configuration) {
        String resource = "mapper/MemoryDecayMapper.xml";
        try (var stream = MemoryDecayTransitionGuard.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("memory_decay_guard_mapper_missing");
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception failure) {
            throw new IllegalStateException("memory_decay_guard_initialization_failure", failure);
        }
    }

    /** 只借用事务所有者传入的会话；不提交、回滚或关闭，缺失和NULL均拒绝自动衰减。 */
    @Override
    public boolean allows(SqlSession session, MemoryCommand.Transition command, MemoryFact current) {
        if (!TIME_DECAY_REASON.equals(command.reasonCode())) return true;
        if (command.target() != FactStatus.DECAYED || current.status() != FactStatus.ACTIVE) return false;
        try {
            return Boolean.TRUE.equals(session.getMapper(MemoryDecayMapper.class)
                    .decayReady(current.ref().tenantId().value(), current.ref().factId()));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("memory_decay_guard_read_failure", failure);
        }
    }
}
