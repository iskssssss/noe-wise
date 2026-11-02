package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.spi.SessionTokenCounter.Profile;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 固定租户的受信tokenizer配置登记；保存完整目标，但不代替真实model binding解析。 */
public final class JdbcSessionTokenProfiles {
    private final TenantId tenant;
    private final SqlSessionFactory sessions;

    public JdbcSessionTokenProfiles(DataSource source,TenantId tenant){
        this(new Factory(source), tenant);
    }

    private JdbcSessionTokenProfiles(Factory factory, TenantId tenant) {
        this.tenant = Objects.requireNonNull(tenant);
        this.sessions = factory.sessions;
    }

    /** MyBatis 配置不含租户状态；每次绑定只创建轻量对象，事务内仍显式绑定租户。 */
    public static final class Factory {
        private final SqlSessionFactory sessions;

        public Factory(DataSource source) {
            Objects.requireNonNull(source);
            var config = new Configuration(new Environment("session-token-profiles", new JdbcTransactionFactory(), source));
            config.setMapUnderscoreToCamelCase(true);
            String resource = "mapper/AgentRuntimeSessionTokenProfilesMapper.xml";
            try (InputStream xml = JdbcSessionTokenProfiles.class.getClassLoader().getResourceAsStream(resource)) {
                if (xml == null) throw invalid();
                new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
            } catch (Exception failure) {
                throw new IllegalStateException("session_token_profiles_initialization_failure", failure);
            }
            sessions = new SqlSessionFactoryBuilder().build(config);
        }

        public JdbcSessionTokenProfiles forTenant(TenantId tenant) {
            return new JdbcSessionTokenProfiles(this, tenant);
        }
    }

    /** 同租户同ref全值不可变；受信配置变更须使用新的ref。 */
    public Profile register(Profile profile){
        Objects.requireNonNull(profile);requireTenant(profile);
        try(var session=sessions.openSession(false)){
            var mapper=mapper(session);mapper.lock(profile.reference().value());var row=mapper.find(profile.reference().value());
            if(row!=null){var original=restore(profile.reference(),row);if(!original.equals(profile))throw invalid();session.commit();return original;}
            if(mapper.insert(profile.reference().value(),SessionPersistenceCodec.encode(profile))!=1)throw invalid();
            session.commit();return profile;
        }catch(RuntimeException failure){throw new IllegalStateException("session_token_profile_registration_rejected", failure);}
    }

    /** 缺失只返回空，不生成或猜测编码、开销、版本和target。 */
    public Optional<Profile> read(ResultRef reference){
        Objects.requireNonNull(reference);
        try(var session=sessions.openSession(false)){
            var row=mapper(session).find(reference.value());return row==null?Optional.empty():Optional.of(restore(reference,row));
        }catch(RuntimeException failure){throw new IllegalStateException("session_token_profile_read_rejected", failure);}
    }
    private Profile restore(ResultRef reference,SessionTokenProfileRow row){
        if(!tenant.value().equals(row.tenantId)||!reference.value().equals(row.profileRef))throw invalid();
        var profile=SessionPersistenceCodec.decode(row.profileData,Profile.class);requireTenant(profile);
        if(!reference.equals(profile.reference()))throw invalid();return profile;
    }
    private void requireTenant(Profile profile){if(!tenant.equals(profile.target().binding().tenantId()))throw invalid();}
    private SessionTokenProfilesMapper mapper(SqlSession session){var mapper=session.getMapper(SessionTokenProfilesMapper.class);mapper.bindTenant(tenant.value());return mapper;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("session_token_profile_invalid");}
}
interface SessionTokenProfilesMapper {
    String bindTenant(@Param("tenant")String tenant);
    String lock(@Param("ref")String ref);
    SessionTokenProfileRow find(@Param("ref")String ref);
    int insert(@Param("ref")String ref,@Param("data")String data);
}
final class SessionTokenProfileRow {public String tenantId,profileRef,profileData;}
