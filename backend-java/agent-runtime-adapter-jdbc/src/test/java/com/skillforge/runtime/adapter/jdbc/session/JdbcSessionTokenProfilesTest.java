package com.skillforge.runtime.adapter.jdbc.session;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.adapter.jdbc.CurrentRuntimeTestDatabase;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.session.spi.SessionTokenCounter.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V117真实PostgreSQL不可变配置、固定租户、并发登记与损坏读取边界。 */
@Testcontainers(disabledWithoutDocker=false)
class JdbcSessionTokenProfilesTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=CurrentRuntimeTestDatabase.database("token_profiles");
    static final TenantId TENANT=new TenantId("1"),OTHER=new TenantId("2");
    @BeforeAll static void schema() throws Exception {
        CurrentRuntimeTestDatabase.migrate(POSTGRES,"token_profile_test","token_profile_test_password");
    }
    @Test void readNeverCreatesAndRebuildKeepsTheEntireOriginalProfile()throws Exception{
        var profile=profile(TENANT,"rebuild");var registry=registry(TENANT);
        assertTrue(registry.read(profile.reference()).isEmpty());
        assertEquals(profile,registry.register(profile));assertEquals(Optional.of(profile),registry(TENANT).read(profile.reference()));
        assertEquals(profile,registry(TENANT).register(profile));
        assertTrue(registry.read(new ResultRef("absent")).isEmpty());
        try(var connection=dataSource().getConnection();var statement=connection.createStatement()){
            statement.execute("SELECT set_config('app.tenant_id','1',false)");
            try(var rows=statement.executeQuery("SELECT count(*) FROM agent_runtime_session_token_profiles WHERE profile_ref='absent'")){rows.next();assertEquals(0,rows.getLong(1));}
        }
    }
    @Test void concurrentRegistrationHasOneValueAndEverySameReferenceChangeConflicts()throws Exception{
        var original=profile(TENANT,"concurrent");
        try(var workers=Executors.newFixedThreadPool(2)){
            var a=workers.submit(()->registry(TENANT).register(original));var b=workers.submit(()->registry(TENANT).register(original));
            assertEquals(original,a.get(10,TimeUnit.SECONDS));assertEquals(original,b.get(10,TimeUnit.SECONDS));
        }
        var target=original.target();var changedTarget=new FrozenModelTarget(target.binding(),target.effectiveModelId(),target.capabilities(),target.timeout().plusNanos(1),target.resolutionRef());
        for(var changed:List.of(
                new Profile(original.reference(),"v2",original.tokenizerVersion(),original.encoding(),target,original.roleCosts(),3,4,10,4096),
                new Profile(original.reference(),original.version(),"different-tokenizer",original.encoding(),target,original.roleCosts(),3,4,10,4096),
                new Profile(original.reference(),original.version(),original.tokenizerVersion(),"different-encoding",target,original.roleCosts(),3,4,10,4096),
                new Profile(original.reference(),original.version(),original.tokenizerVersion(),original.encoding(),changedTarget,original.roleCosts(),3,4,10,4096),
                new Profile(original.reference(),original.version(),original.tokenizerVersion(),original.encoding(),target,new RoleCosts(9,2,3),3,4,10,4096),
                new Profile(original.reference(),original.version(),original.tokenizerVersion(),original.encoding(),target,original.roleCosts(),3,5,10,4096),
                new Profile(original.reference(),original.version(),original.tokenizerVersion(),original.encoding(),target,original.roleCosts(),3,4,10,4097))){
            assertThrows(IllegalStateException.class,()->registry(TENANT).register(changed));
        }
        assertEquals(Optional.of(original),registry(TENANT).read(original.reference()));
    }
    @Test void fixedTenantRejectsForeignTargetAndDatabaseRlsBlocksCrossTenantWrites()throws Exception{
        var first=profile(TENANT,"shared-ref");var second=profile(OTHER,"shared-ref");
        var shared = new JdbcSessionTokenProfiles.Factory(dataSource());
        assertThrows(IllegalArgumentException.class,()->shared.forTenant(TENANT).register(second));
        shared.forTenant(TENANT).register(first);assertTrue(shared.forTenant(OTHER).read(first.reference()).isEmpty());
        shared.forTenant(OTHER).register(second);assertEquals(Optional.of(first),shared.forTenant(TENANT).read(first.reference()));assertEquals(Optional.of(second),shared.forTenant(OTHER).read(first.reference()));
        try(var connection=dataSource().getConnection();var statement=connection.createStatement()){
            statement.execute("SELECT set_config('app.tenant_id','1',false)");
            try(var rows=statement.executeQuery("SELECT count(*) FROM agent_runtime_session_token_profiles WHERE tenant_id='2'")){rows.next();assertEquals(0,rows.getLong(1));}
            assertThrows(SQLException.class,()->statement.execute("INSERT INTO agent_runtime_session_token_profiles VALUES('2','rls-forbidden','data',clock_timestamp())"));
            assertThrows(SQLException.class,()->statement.execute("UPDATE agent_runtime_session_token_profiles SET profile_data='changed' WHERE profile_ref='shared-ref'"));
            assertThrows(SQLException.class,()->statement.execute("DELETE FROM agent_runtime_session_token_profiles WHERE profile_ref='shared-ref'"));
        }
    }
    @Test void malformedOrMismatchedStoredBytesAreRejectedRatherThanRepaired()throws Exception{
        insert("corrupt","not-base64!");
        insert("wrong-ref",SessionPersistenceCodec.encode(profile(TENANT,"other-ref")));
        insert("wrong-tenant",SessionPersistenceCodec.encode(profile(OTHER,"wrong-tenant")));
        for(String ref:List.of("corrupt","wrong-ref","wrong-tenant"))assertThrows(IllegalStateException.class,()->registry(TENANT).read(new ResultRef(ref)));
    }
    private static void insert(String ref,String data)throws Exception{
        try(var connection=dataSource().getConnection();var bind=connection.createStatement()){
            bind.execute("SELECT set_config('app.tenant_id','1',false)");
            try(var statement=connection.prepareStatement("INSERT INTO agent_runtime_session_token_profiles(tenant_id,profile_ref,profile_data) VALUES('1',?,?)")){
                statement.setString(1,ref);statement.setString(2,data);assertEquals(1,statement.executeUpdate());
            }
        }
    }
    private static Profile profile(TenantId tenant,String ref){
        var target=new FrozenModelTarget(new ModelBindingRef(tenant,"binding",1),"explicit-model",new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT),true,false,new ResultRef("capability")),Duration.ofSeconds(5),new ResultRef("resolution"));
        return new Profile(new ResultRef(ref),"v1","jtokkit:1.1.0","cl100k_base",target,new RoleCosts(1,2,3),3,4,10,4096);
    }
    private static javax.sql.DataSource dataSource(){return CurrentRuntimeTestDatabase.source(POSTGRES,"token_profile_test","token_profile_test_password");}
    private static JdbcSessionTokenProfiles registry(TenantId tenant){return new JdbcSessionTokenProfiles(dataSource(),tenant);}
}
