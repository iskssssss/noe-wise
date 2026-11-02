package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.spi.SessionPreparations;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 不可变模型准备配置仓储；请求、结果和运行状态均由 ExecutionStore 独立拥有。 */
public final class JdbcSessionPreparations implements SessionPreparations {
    private static final String RESOURCE = "mapper/AgentRuntimeSessionPreparationsMapper.xml";
    private final TenantId tenant;
    private final SqlSessionFactory sessions;

    /** 将轻量租户视图绑定到已创建的只含配置映射的工厂。 */
    private JdbcSessionPreparations(Factory factory, TenantId tenant) {
        this.tenant = Objects.requireNonNull(tenant);
        this.sessions = factory.sessions;
    }

    /** 无租户缓存的配置工厂；不创建旧准备来源、Run 快照或调用账本。 */
    public static final class Factory {
        private final SqlSessionFactory sessions;
        private final JdbcSessionTokenProfiles.Factory tokens;

        /** 宿主提供受 RLS 约束的 DataSource，租户在每次事务中明确绑定。 */
        public Factory(DataSource source) {
            Objects.requireNonNull(source);
            tokens = new JdbcSessionTokenProfiles.Factory(source);
            var configuration = new Configuration(new Environment("session-profiles", new JdbcTransactionFactory(), source));
            configureProfiles(configuration);
            sessions = new SqlSessionFactoryBuilder().build(configuration);
        }

        /** 只返回本次租户视图，不缓存租户或认证。 */
        public JdbcSessionPreparations forTenant(TenantId tenant) { return new JdbcSessionPreparations(this, tenant); }

        /** Tokenizer 配置属于同一冻结配置发布边界。 */
        public JdbcSessionTokenProfiles tokenProfiles(TenantId tenant) { return tokens.forTenant(tenant); }
    }

    /** 同租户同引用全值不可变，配置变化必须发布新引用。 */
    public Profile registerProfile(Profile profile) {
        Objects.requireNonNull(profile);
        requireTenant(profile.binding().tenantId());
        try (var session = sessions.openSession(false)) {
            var mapper = mapper(session);
            mapper.lockProfile(profile.reference().value());
            var prior = profileBorrowed(session, tenant, profile.reference());
            if (prior.isPresent()) {
                if (!prior.orElseThrow().equals(profile)) throw invalid();
                session.commit();
                return prior.orElseThrow();
            }
            if (mapper.insertProfile(profile.reference().value(), SessionPersistenceCodec.encode(profile)) != 1) throw invalid();
            session.commit();
            return profile;
        } catch (RuntimeException failure) { throw new IllegalStateException("session_profile_registration_rejected", failure); }
    }

    /** 缺失配置返回空；不能从当前默认值重建历史。 */
    @Override public Optional<Profile> profile(ResultRef reference) {
        Objects.requireNonNull(reference);
        try (var session = sessions.openSession(false)) {
            mapper(session);
            return profileBorrowed(session, tenant, reference);
        } catch (RuntimeException failure) { throw new IllegalStateException("session_profile_read_rejected", failure); }
    }

    /** 复用调用方已绑定租户的同一事务，不创建连接、不提交、不重新绑定租户。 */
    public static Optional<Profile> profileBorrowed(SqlSession session, TenantId tenant, ResultRef reference) {
        Objects.requireNonNull(session); Objects.requireNonNull(tenant); Objects.requireNonNull(reference);
        var data = session.getMapper(SessionPreparationsMapper.class).profile(reference.value());
        if (data == null) return Optional.empty();
        var value = SessionPersistenceCodec.decode(data, Profile.class);
        if (!value.reference().equals(reference) || !value.binding().tenantId().equals(tenant)) throw invalid();
        return Optional.of(value);
    }

    /** 将配置专有映射注册到调用方 MyBatis 配置，不注册旧 Session 持久化映射。 */
    public static void configureProfiles(Configuration configuration) {
        if (configuration.hasMapper(SessionPreparationsMapper.class)) return;
        try (InputStream xml = JdbcSessionPreparations.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (xml == null) throw new IllegalStateException("session_profile_mapper_missing");
            new XMLMapperBuilder(xml, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        } catch (java.io.IOException failure) { throw new IllegalStateException("session_profile_initialization_failure", failure); }
    }

    /** 独立配置仓储拥有本次事务的租户绑定。 */
    private SessionPreparationsMapper mapper(SqlSession session) {
        var mapper = session.getMapper(SessionPreparationsMapper.class);
        mapper.bindTenant(tenant.value());
        return mapper;
    }

    /** 发布来源和冻结引用都必须属于本次租户。 */
    private void requireTenant(TenantId actual) { if (!tenant.equals(actual)) throw invalid(); }
    /** 固定错误不披露配置正文或凭据。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("session_preparation_profile_invalid"); }
}

/** SQL 仅维护在配置专有 XML 中，不映射运行或准备树。 */
interface SessionPreparationsMapper {
    String bindTenant(@Param("tenant") String tenant);
    String lockProfile(@Param("ref") String ref);
    String profile(@Param("ref") String ref);
    int insertProfile(@Param("ref") String ref, @Param("data") String data);
}
