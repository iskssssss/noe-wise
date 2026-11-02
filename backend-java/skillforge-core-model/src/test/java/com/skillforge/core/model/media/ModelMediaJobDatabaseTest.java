package com.skillforge.core.model.media;

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

/** 正式Flyway链、实际XML Mapper、受限数据库角色共同验证任务身份、CAS和资产RLS。 */
@Testcontainers(disabledWithoutDocker = false)
class ModelMediaJobDatabaseTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            // 一次性初始化不等待全盘同步；测试运行中的数据库提交仍保留 fsync。
            .withEnv("POSTGRES_INITDB_ARGS", "--no-sync")
            .withUsername("skillforge").withPassword("synthetic-model-media-database");
    private static SqlSessionFactory sessions;

    @BeforeAll static void prepare() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("CREATE ROLE media_tenant_test LOGIN PASSWORD 'synthetic-restricted-media' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO media_tenant_test");
        owner.execute("GRANT SELECT,INSERT,UPDATE ON nw_model_media_jobs,nw_model_media_assets TO media_tenant_test");
        owner.execute("GRANT SELECT,INSERT ON nw_model_media_input_sources,nw_model_media_input_channels,nw_model_media_input_languages TO media_tenant_test");
        owner.execute("GRANT SELECT ON nw_platform_models TO media_tenant_test");
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "media_tenant_test", "synthetic-restricted-media");
        var configuration = new Configuration(new Environment("model-media-database", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/models/ModelMediaJobMapper.xml";
        try (var xml = ModelMediaJobDatabaseTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(xml);
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test void preservesFixedFieldsAndExactDurationAndRejectsLateUpdates() {
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(ModelMediaJobMapper.class);
            mapper.bindTenant("7");
            var row = row("1001", "7", "first-session");
            assertEquals(1, mapper.insert(row));
            assertEquals(0, mapper.insert(row));
            mapper.insertSource(row.id, 0, "REFERENCE_IMAGE", "encrypted-private-reference");
            mapper.insertChannel(row.id, 0, 1);
            mapper.insertLanguage(row.id, 0, "zh");
            var original = mapper.operation(row.ownerId, row.operationId);
            assertEquals(row.credentialEncrypted, original.credentialEncrypted);
            assertEquals(row.inputText, original.inputText);
            assertEquals(row.createdAt, original.createdAt);
            assertEquals(row.enableItn, original.enableItn);
            assertEquals(row.language, original.language);
            row.status = "SUCCEEDED";
            row.durationSeconds = new BigDecimal("1.23456789123456789");
            row.inputTokens = 0L;
            assertEquals(1, mapper.update(row, 0));
            assertEquals(0, mapper.update(row, 0));
            var saved = mapper.find(row.ownerId, row.id);
            assertEquals(row.durationSeconds, saved.durationSeconds);
            assertEquals(0L, saved.inputTokens);
            assertNull(saved.outputTokens);
            assertEquals(1L, saved.lockVersion);
            assertNull(mapper.find(new ModelMediaJob.Owner("7", "9", "other-session").key(), row.id));
            session.commit();
        }
    }

    @Test void assetsInheritTaskRlsAndInlineBodiesRequireCompleteStoredMetadata() {
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(ModelMediaJobMapper.class);
            mapper.bindTenant("17");
            var row = row("1002", "17", "asset-session");
            mapper.insert(row);
            var asset = new ModelMediaJobMapper.AssetRow();
            asset.id = "2001"; asset.jobId = row.id; asset.kind = "AUDIO";
            asset.available = true; asset.mediaType = "audio/mpeg"; asset.name = "speech.mp3";
            asset.sizeBytes = 8L; asset.sha256 = "1".repeat(64);
            assertEquals(1, mapper.insertAsset(asset));
            assertTrue(mapper.assets(row.id).getFirst().available);
            assertNull(mapper.assets(row.id).getFirst().sourceEncrypted);
            session.commit();
            // 与生产每次固定Owner事务一致，用独立受限连接验证数据库RLS，避免同SqlSession一级缓存。
            try (var otherSession = sessions.openSession(false)) {
                assertNotSame(session.getConnection(), otherSession.getConnection());
                var otherMapper = otherSession.getMapper(ModelMediaJobMapper.class);
                otherMapper.bindTenant("18");
                assertNull(otherMapper.find(row.ownerId, row.id));
                assertTrue(otherMapper.assets(row.id).isEmpty());
                asset.id = "2002";
                assertThrows(RuntimeException.class, () -> otherMapper.insertAsset(asset));
                otherSession.rollback();
            }
        }
    }

    private static ModelMediaJobMapper.Row row(String id, String tenant, String session) {
        var row = new ModelMediaJobMapper.Row();
        row.id = id; row.tenantId = tenant; row.ownerId = new ModelMediaJob.Owner(tenant, "9", session).key();
        row.operationId = UUID.randomUUID().toString(); row.commandSha256 = "0".repeat(64);
        row.modelRevision = 0; row.providerId = "aliyun-bailian"; row.serviceId = "bailian-cn-beijing-media";
        row.profileVersion = "fixture"; row.modelId = "qwen-image-fixture"; row.kind = "IMAGE_GENERATION";
        row.endpoint = "https://dashscope.aliyuncs.com"; row.credentialEncrypted = "encrypted-frozen-credential";
        row.timeoutSeconds = 60; row.status = "UNKNOWN"; row.inputText = "用户原始提示词\n保留换行";
        row.language = "zh"; row.enableItn = true;
        row.createdAt = Instant.parse("2026-10-25T00:00:00.123456Z"); row.updatedAt = row.createdAt;
        return row;
    }
}
