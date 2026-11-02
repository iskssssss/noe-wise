package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionStorePort.ChangeSessionStatus;
import com.skillforge.runtime.api.port.SessionStorePort.CreateSession;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/**
 * F1媒体专属历史fixture：通过正式codec、hash、row工厂及Mapper首次写入旧生命周期图。
 * 不改变生产storageNow、不更新已存在回执、不关闭RLS或不可变trigger，不供业务代码调用。
 */
public final class SessionMediaHistoricalLifecycleFixture {
    private SessionMediaHistoricalLifecycleFixture() { }

    /** 按正式生命周期逐阶段提交旧图，使每次deferred guard核对本次已提交输出。 */
    public static void persist(DataSource source, CreateSession create, ChangeSessionStatus activate,
            ChangeSessionStatus close, Instant createdAt) throws Exception {
        SessionOwner owner=create.owner();
        if(!Objects.equals(owner.sessionRef().tenantId().value(),TenantContext.getTenantId())
                ||!owner.equals(activate.owner())||!owner.equals(close.owner()))
            throw new IllegalArgumentException("historical_media_fixture_owner_mismatch");
        var configuration=new Configuration(new Environment("media-f1-historical-fixture",new JdbcTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource="mapper/AgentRuntimeSessionStoreMapper.xml";
        try(InputStream xml=SessionMediaHistoricalLifecycleFixture.class.getClassLoader().getResourceAsStream(resource)){
            if(xml==null)throw new IllegalStateException("formal_session_store_mapper_missing");
            new XMLMapperBuilder(xml,configuration,resource,configuration.getSqlFragments()).parse();
        }
        var factory=new SqlSessionFactoryBuilder().build(configuration);
        try(var session=factory.openSession(false)){
            var mapper=session.getMapper(SessionStoreMapper.class);mapper.bindTenant(owner.sessionRef().tenantId().value());
            mapper.ensureOwner(SessionOwnerRow.initial(owner));
            var root=mapper.lockOwner(owner.sessionRef().tenantId().value(),owner.target().kind().name(),owner.target().id(),owner.sessionRef().sessionId());
            if(root==null||!root.validatedStoreOwner().equals(owner))throw new IllegalStateException("historical_media_fixture_owner_not_visible");
            String encoded=SessionPersistenceCodec.encode(create);
            if(mapper.insertSession(SessionStoreRow.initial(create,encoded,SessionStoreHashes.fingerprint("create",encoded),createdAt))!=1)
                throw new IllegalStateException("historical_media_fixture_create_not_inserted");
            session.commit();
        }
        commitTransition(factory,activate,createdAt.plusSeconds(1));
        commitTransition(factory,close,createdAt.plusSeconds(2));
    }

    /** 每个CAS单独绑定租户、先锁owner再写输出；先提交ACTIVE，再开始CLOSED阶段。 */
    private static void commitTransition(SqlSessionFactory factory,ChangeSessionStatus command,Instant updatedAt){
        var owner=command.owner();
        try(var session=factory.openSession(false)){
            var mapper=session.getMapper(SessionStoreMapper.class);mapper.bindTenant(owner.sessionRef().tenantId().value());
            var root=mapper.lockOwner(owner.sessionRef().tenantId().value(),owner.target().kind().name(),owner.target().id(),owner.sessionRef().sessionId());
            if(root==null||!root.validatedStoreOwner().equals(owner))throw new IllegalStateException("historical_media_fixture_owner_not_visible");
            transition(mapper,command,updatedAt);
            session.commit();
        }
    }

    /** 正式不可变mutation先INSERT，随后按原owner/status/revision做实际CAS，不回写旧journal。 */
    private static void transition(SessionStoreMapper mapper,ChangeSessionStatus command,Instant updatedAt){
        var owner=command.owner();String tenant=owner.sessionRef().tenantId().value(),kind=owner.target().kind().name();
        mapper.lockSession(tenant,kind,owner.target().id(),owner.sessionRef().sessionId());
        String encoded=SessionPersistenceCodec.encode(command);long revision=Math.incrementExact(command.expectedRevision());
        if(mapper.insertMutation(SessionStoreMutationRow.from(command,encoded,SessionStoreHashes.fingerprint("cas",encoded),revision,updatedAt))!=1)
            throw new IllegalStateException("historical_media_fixture_mutation_not_inserted");
        if(mapper.updateSession(tenant,kind,owner.target().id(),owner.sessionRef().sessionId(),owner.requireUser().value(),
                command.expectedStatus().name(),command.targetStatus().name(),command.expectedRevision(),revision,
                updatedAt.getEpochSecond(),updatedAt.getNano())!=1)
            throw new IllegalStateException("historical_media_fixture_cas_not_applied");
    }
}
