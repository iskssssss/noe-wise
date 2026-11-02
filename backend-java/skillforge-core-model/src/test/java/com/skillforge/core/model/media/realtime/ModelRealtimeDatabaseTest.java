package com.skillforge.core.model.media.realtime;

import com.skillforge.core.model.media.ModelMediaJob.Owner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 XML、完整迁移与受限角色验证实时身份、单次音频登记和终态不被迟到回执覆盖。 */
@Testcontainers(disabledWithoutDocker = false)
class ModelRealtimeDatabaseTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withUsername("skillforge").withPassword("synthetic-realtime-database");
    private static SqlSessionFactory sessions;

    @BeforeAll static void prepare() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var databaseOwner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        databaseOwner.execute("CREATE ROLE realtime_test LOGIN PASSWORD 'synthetic-realtime-restricted' NOSUPERUSER NOBYPASSRLS");
        databaseOwner.execute("GRANT USAGE ON SCHEMA public TO realtime_test");
        databaseOwner.execute("GRANT SELECT,INSERT,UPDATE ON nw_model_realtime_sessions TO realtime_test");
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "realtime_test", "synthetic-realtime-restricted");
        var configuration = new Configuration(new Environment("realtime-database", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/models/ModelRealtimeMapper.xml";
        try (var xml = ModelRealtimeDatabaseTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(xml);
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test void keepsOneOperationAndOneAudioSequenceAndPersistsExactTerminalUsage() {
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(ModelRealtimeMapper.class);
            mapper.bindTenant("7");
            var row = row("9001", "7");
            assertEquals(1, mapper.insert(row));
            assertEquals(0, mapper.insert(row));
            assertEquals(1, mapper.started(row.id, row.createdAt));
            assertEquals(1, mapper.claimAudio(row.ownerId, row.id, 0, row.createdAt));
            assertEquals(0, mapper.claimAudio(row.ownerId, row.id, 0, row.createdAt));
            assertEquals(1, mapper.find(row.ownerId, row.id).nextAudioSequence);
            assertEquals(1, mapper.finish(row.ownerId, row.id, row.createdAt));
            assertEquals(0, mapper.finish(row.ownerId, row.id, row.createdAt));
            row.status = "SUCCEEDED"; row.finalText = "准确保存中文识别结果";
            row.inputTokens = 0L; row.outputTokens = null; row.durationSeconds = new BigDecimal("1.2345678912345");
            assertEquals(1, mapper.complete(row));
            var restored = mapper.operation(row.ownerId, row.operationId);
            assertEquals(row.finalText, restored.finalText);
            assertEquals(row.durationSeconds, restored.durationSeconds);
            assertEquals(row.credentialEncrypted, restored.credentialEncrypted);
            assertEquals(0L, restored.inputTokens); assertNull(restored.outputTokens);
            row.status = "UNKNOWN";
            assertEquals(0, mapper.complete(row));
            assertEquals("SUCCEEDED", mapper.find(row.ownerId, row.id).status);
            session.commit();
        }
    }

    @Test void ownerAndRlsDenyOtherActorSessionAndTenantWithoutPublishingCredentials() {
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(ModelRealtimeMapper.class);
            mapper.bindTenant("17");
            var row = row("9002", "17");
            assertEquals(1, mapper.insert(row));
            assertNull(mapper.find(new Owner("17", "9", "another-session").key(), row.id));
            mapper.bindTenant("18");
            assertNull(mapper.find(row.ownerId, row.id));
            assertEquals(0, mapper.claimAudio(row.ownerId, row.id, 0, row.createdAt));
            var wrongTenant = row("9003", "17");
            assertThrows(RuntimeException.class, () -> mapper.insert(wrongTenant));
            session.rollback();
        }
    }

    @Test void originalKnownReceiptCanResolveUnknownAndLaterUnknownCannotOverwriteIt() {
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(ModelRealtimeMapper.class);
            mapper.bindTenant("27");
            var row = row("9004", "27");
            mapper.insert(row);
            row.status = "UNKNOWN"; assertEquals(1, mapper.complete(row));
            row.status = "SUCCEEDED"; assertEquals(1, mapper.complete(row));
            row.status = "UNKNOWN"; assertEquals(0, mapper.complete(row));
            assertEquals("SUCCEEDED", mapper.find(row.ownerId, row.id).status);
            session.rollback();
        }
    }

    private static ModelRealtimeMapper.Row row(String id, String tenant) {
        var row = new ModelRealtimeMapper.Row();
        row.id = id; row.tenantId = tenant; row.ownerId = new Owner(tenant, "9", "realtime-session").key();
        row.operationId = UUID.randomUUID().toString(); row.commandSha256 = "0".repeat(64);
        row.kind = "SPEECH_RECOGNITION"; row.modelRevision = 0; row.providerId = "aliyun-bailian";
        row.serviceId = "bailian-cn-beijing-asr"; row.modelId = "paraformer-fixture";
        row.endpoint = "https://dashscope.aliyuncs.com"; row.credentialEncrypted = "encrypted-credential";
        row.providerTaskId = UUID.randomUUID().toString(); row.timeoutSeconds = 60; row.sampleRateHz = 16000;
        row.status = "PENDING"; row.createdAt = Instant.parse("2026-10-25T00:00:00.123456Z"); row.updatedAt = row.createdAt;
        row.deadline = row.createdAt.plusSeconds(60);
        return row;
    }
}
