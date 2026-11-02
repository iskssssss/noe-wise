package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.SessionStorePort.*;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/**
 * 独立媒体保护用例的历史生命周期首次写入桥；不修改fresh06共享bridge或现存回执。
 * 先提交正式CREATED/ACTIVE图，再由typed Store写入，最后单独提交旧CLOSED事实。
 */
public final class SessionMediaRuntimeProtectionLifecycleFixture {
    private SessionMediaRuntimeProtectionLifecycleFixture() { }

    /** 使用正式codec/hash/row/Mapper创建本例旧ACTIVE图，每个阶段分别满足deferred输出guard。 */
    public static void persistActive(DataSource source,CreateSession create,ChangeSessionStatus activate,Instant old) throws Exception {
        if(!create.owner().equals(activate.owner())||activate.targetStatus()!=SessionStatus.ACTIVE)throw new IllegalArgumentException("media_protection_active_owner_mismatch");
        var factory=factory(source);var owner=create.owner();
        try(var session=factory.openSession(false)){
            var mapper=session.getMapper(SessionStoreMapper.class);lock(mapper,owner,true);
            String encoded=SessionPersistenceCodec.encode(create);
            if(mapper.insertSession(SessionStoreRow.initial(create,encoded,SessionStoreHashes.fingerprint("create",encoded),old))!=1)
                throw new IllegalStateException("media_protection_create_not_inserted");
            session.commit();
        }
        commitStatus(factory,activate,old.plusSeconds(1));
    }

    /** typed Run合法写入后首次插入原CLOSED mutation，保持31天关闭事实而不回写任何旧journal。 */
    public static void close(DataSource source,ChangeSessionStatus close,Instant closedAt) throws Exception {
        if(close.targetStatus()!=SessionStatus.CLOSED)throw new IllegalArgumentException("media_protection_close_required");
        commitStatus(factory(source),close,closedAt);
    }

    /** 只装配实际SessionStore XML；连接角色、完整schema与guard由外部Spring夹具检查。 */
    private static SqlSessionFactory factory(DataSource source) throws Exception {
        var configuration=new Configuration(new Environment("media-runtime-protection-lifecycle",new JdbcTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true);String resource="mapper/AgentRuntimeSessionStoreMapper.xml";
        try(InputStream xml=SessionMediaRuntimeProtectionLifecycleFixture.class.getClassLoader().getResourceAsStream(resource)){
            if(xml==null)throw new IllegalStateException("media_protection_formal_mapper_missing");
            new XMLMapperBuilder(xml,configuration,resource,configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 每次真实事务绑定可信租户并先锁完整owner；不能依赖池连接的残留身份。 */
    private static void lock(SessionStoreMapper mapper,SessionOwner owner,boolean create){
        if(!Objects.equals(TenantContext.getTenantId(),owner.sessionRef().tenantId().value()))throw new SecurityException("media_protection_tenant_mismatch");
        mapper.bindTenant(owner.sessionRef().tenantId().value());if(create)mapper.ensureOwner(SessionOwnerRow.initial(owner));
        var row=mapper.lockOwner(owner.sessionRef().tenantId().value(),owner.target().kind().name(),owner.target().id(),owner.sessionRef().sessionId());
        if(row==null||!row.validatedStoreOwner().equals(owner))throw new IllegalStateException("media_protection_owner_missing");
    }

    /** 不可变mutation先首次INSERT再真实CAS；每次独立提交，不覆盖journal或绕过时钟/trigger。 */
    private static void commitStatus(SqlSessionFactory factory,ChangeSessionStatus command,Instant at){
        var owner=command.owner();
        try(var session=factory.openSession(false)){
            var mapper=session.getMapper(SessionStoreMapper.class);lock(mapper,owner,false);
            mapper.lockSession(owner.sessionRef().tenantId().value(),owner.target().kind().name(),owner.target().id(),owner.sessionRef().sessionId());
            String encoded=SessionPersistenceCodec.encode(command);long revision=command.expectedRevision()+1;
            if(mapper.insertMutation(SessionStoreMutationRow.from(command,encoded,SessionStoreHashes.fingerprint("cas",encoded),revision,at))!=1
                    ||mapper.updateSession(owner.sessionRef().tenantId().value(),owner.target().kind().name(),owner.target().id(),owner.sessionRef().sessionId(),
                    owner.requireUser().value(),command.expectedStatus().name(),command.targetStatus().name(),command.expectedRevision(),revision,at.getEpochSecond(),at.getNano())!=1)
                throw new IllegalStateException("media_protection_session_cas_not_applied");
            session.commit();
        }
    }
}
