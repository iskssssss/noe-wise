package com.skillforge.systemagent.runtime;

import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.port.SessionOwner;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 只持久化平台 Runtime 的 owner与冻结业务来源；Run/Action/Attempt仅由typed ExecutionStore拥有。 */
final class SystemAgentRuntimeStore {
    private final SqlSessionFactory sessions;

    SystemAgentRuntimeStore(DataSource source) {
        var configuration = new Configuration(new Environment("system-agent-runtime",
                new JdbcTransactionFactory(), Objects.requireNonNull(source)));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/SystemAgentRuntimeMapper.xml";
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("system_agent_runtime_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception failure) {
            throw new IllegalStateException("system_agent_runtime_store_initialization_failed", failure);
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    void register(SessionOwner owner, String integrationId, InputId input, long generation, String sourceData) {
        try (var session = sessions.openSession(TransactionIsolationLevel.READ_COMMITTED)) {
            Mapper mapper = mapper(session, owner);
            mapper.lock(owner, input.value());
            mapper.insertOwner(owner, integrationId);
            OwnerRow persistedOwner = mapper.owner(owner);
            if (persistedOwner == null || persistedOwner.endUserId != owner.requireUser().value()
                    || !integrationId.equals(persistedOwner.integrationId)) {
                throw failure("system_agent_runtime_owner_mismatch");
            }
            mapper.insertSource(owner, input.value(), generation, sourceData);
            SourceRow source = mapper.source(owner, input.value());
            if (source == null || source.generation != generation || source.endUserId != owner.requireUser().value()) {
                throw failure("system_agent_runtime_source_mismatch");
            }
            session.commit();
        }
    }

    Optional<SourceRow> source(SessionOwner owner, InputId input) {
        try (var session = sessions.openSession(false)) {
            SourceRow row = mapper(session, owner).source(owner, input.value());
            session.commit();
            return Optional.ofNullable(row);
        }
    }

    private static Mapper mapper(SqlSession session, SessionOwner owner) {
        Mapper mapper = session.getMapper(Mapper.class);
        mapper.bindTenant(owner.sessionRef().tenantId().value());
        return mapper;
    }

    private static IllegalStateException failure(String reason) { return new IllegalStateException(reason); }


    interface Mapper {
        String bindTenant(@Param("tenant") String tenant);
        String lock(@Param("owner") SessionOwner owner, @Param("input") String input);
        int insertOwner(@Param("owner") SessionOwner owner, @Param("integration") String integration);
        OwnerRow owner(@Param("owner") SessionOwner owner);
        int insertSource(@Param("owner") SessionOwner owner, @Param("input") String input,
                         @Param("generation") long generation, @Param("data") String data);
        SourceRow source(@Param("owner") SessionOwner owner, @Param("input") String input);
        Instant now();
    }

    public static final class OwnerRow {
        public long endUserId;
        public String integrationId;
    }

    public static final class SourceRow {
        public String tenantId;
        public String sessionId;
        public String agentId;
        public long endUserId;
        public String inputId;
        public long generation;
        public String sourceData;
        public Instant createdAt;
    }

}
