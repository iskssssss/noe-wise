package com.skillforge.runtime.adapter.jdbc.session;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.adapter.jdbc.CurrentRuntimeTestDatabase;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.session.spi.*;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 当前仅冻结模型Profile的真实配置仓储；实际请求与结果不再走旧准备来源图。 */
@Testcontainers(disabledWithoutDocker=false)
class JdbcSessionPreparationsTest {
    private static final TenantId TENANT=new TenantId("1"),OTHER=new TenantId("2");
    @Container static final PostgreSQLContainer<?> POSTGRES=CurrentRuntimeTestDatabase.database("model_profiles");
    @BeforeAll static void schema() throws Exception {CurrentRuntimeTestDatabase.migrate(POSTGRES,"model_profile_test","model_profile_test_password");}
    @Test void profileReadNeverCreatesAndExactReplaySurvivesFactoryRebuild() {
        var original=profile(TENANT,"rebuild","系统指令");assertTrue(registry(TENANT).profile(original.reference()).isEmpty());
        assertEquals(original,registry(TENANT).registerProfile(original));assertEquals(Optional.of(original),registry(TENANT).profile(original.reference()));
        assertEquals(original,registry(TENANT).registerProfile(original));assertTrue(registry(TENANT).profile(new ResultRef("missing")).isEmpty());
    }
    @Test void sameReferenceCannotChangeFrozenBusinessConfiguration() {
        var original=profile(TENANT,"immutable","原指令");registry(TENANT).registerProfile(original);
        assertThrows(IllegalStateException.class,()->registry(TENANT).registerProfile(profile(TENANT,"immutable","改变指令")));
        assertEquals(Optional.of(original),registry(TENANT).profile(original.reference()));
    }
    @Test void fixedTenantAndDatabaseRlsKeepEqualProfileReferencesSeparate() {
        var one=profile(TENANT,"tenant-ref","机构一");var two=profile(OTHER,"tenant-ref","机构二");
        assertThrows(IllegalArgumentException.class,()->registry(TENANT).registerProfile(two));
        registry(TENANT).registerProfile(one);assertTrue(registry(OTHER).profile(one.reference()).isEmpty());registry(OTHER).registerProfile(two);
        assertEquals(Optional.of(one),registry(TENANT).profile(one.reference()));assertEquals(Optional.of(two),registry(OTHER).profile(one.reference()));
    }
    @Test void borrowedProfileReadUsesOnlyCallerSessionAndNeverCommitsItsPendingWrite() {
        var config=new Configuration(new Environment("borrowed-profile",new JdbcTransactionFactory(),dataSource()));
        JdbcSessionPreparations.configureProfiles(config);var factory=new SqlSessionFactoryBuilder().build(config);var original=profile(TENANT,"borrowed","借用事务");
        try(var session=factory.openSession(false)) {
            var mapper=session.getMapper(SessionPreparationsMapper.class);mapper.bindTenant(TENANT.value());
            assertEquals(1,mapper.insertProfile(original.reference().value(),SessionPersistenceCodec.encode(original)));
            assertEquals(Optional.of(original),JdbcSessionPreparations.profileBorrowed(session,TENANT,original.reference()));
            assertTrue(registry(TENANT).profile(original.reference()).isEmpty());session.rollback(true);
        }
        assertTrue(registry(TENANT).profile(original.reference()).isEmpty());
    }
    private static JdbcSessionPreparations registry(TenantId tenant){return new JdbcSessionPreparations.Factory(dataSource()).forTenant(tenant);}
    private static DataSource dataSource(){return CurrentRuntimeTestDatabase.source(POSTGRES,"model_profile_test","model_profile_test_password");}
    private static SessionPreparations.Profile profile(TenantId tenant,String ref,String prompt) {
        return new SessionPreparations.Profile(new ResultRef(ref),SessionPreparations.PROFILE_VERSION,SessionPreparations.AGENT_INPUT_RENDERER_VERSION,SessionAdmissionFacts.ContextKind.AGENT_INPUT,
                SessionPrompt.single("system",SessionPrompt.Type.HOST,"1",prompt),new ModelBindingRef(tenant,"binding",1),new RequestedModelStrategy("strategy","1"),
                new ResponseContract(new ResponseFormat.Text(),EnforcementLevel.STRICT,"supported"),new GenerationOptions(OptionalInt.of(100),OptionalDouble.empty()),ReasoningPolicy.DISABLED,PortalCallKind.MODEL_GENERATE,
                new ResultRef("tokenizer"),SessionContextPolicy.defaults(),100_000,100_000,100,false,Set.of(InboundKind.MESSAGE));
    }
}
