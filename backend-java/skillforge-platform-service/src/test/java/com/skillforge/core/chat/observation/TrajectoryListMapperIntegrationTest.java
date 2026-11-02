package com.skillforge.core.chat.observation;

import com.skillforge.core.chat.trajectory.TrajectoryListMapper;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 正式迁移与真实 typed Store 事实验证检索候选，不用禁用约束的夹具代替生产归属。 */
@Testcontainers
class TrajectoryListMapperIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("trajectory_list").withUsername("skillforge").withPassword("test-only");
    private static TrajectoryListMapper mapper;
    private static JdbcTemplate jdbc;

    @BeforeAll static void initialize() throws Exception {
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target) VALUES(910000,'trajectory-list','轨迹检索测试',0.9900)");
        new SessionObservationFixture(jdbc).populate(301L, "trajectory-visible", "list-root-a", "list-root-b", 1, 2, 3);
        new SessionObservationFixture(jdbc).populate(302L, "trajectory-foreign", "list-root-c", "list-root-d", 1, 2, 3);
        var factory = new org.mybatis.spring.SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/TrajectoryListMapper.xml"));
        mapper = new org.mybatis.spring.SqlSessionTemplate(factory.getObject()).getMapper(TrajectoryListMapper.class);
    }

    @Test void tenantAndVisibleSessionsAreAppliedBeforeCandidatePaging() {
        var all = mapper.candidates(parameters(301L, null, null, null, 0, 100));
        assertEquals(2, all.size());
        assertTrue(all.stream().allMatch(row -> row.tenantId() == 301L && row.sessionId().equals("trajectory-visible")));
        assertEquals("list-root-b", all.getFirst().rootInputId());
        assertEquals(1, mapper.candidates(parameters(301L, null, List.of("trajectory-visible"), null, 1, 1)).size());
        assertTrue(mapper.candidates(parameters(301L, null, List.of(), null, 0, 100)).isEmpty());
        assertTrue(mapper.candidates(parameters(301L, null, List.of("trajectory-foreign"), null, 0, 100)).isEmpty());
        assertTrue(mapper.candidates(parameters(301L, 302L, null, null, 0, 100)).isEmpty());
        assertEquals(2, mapper.candidates(parameters(301L, null, null, 910301L, 0, 100)).size());
        assertTrue(mapper.candidates(parameters(301L, null, null, 910302L, 0, 100)).isEmpty());
    }

    @Test void rootStartUsesExactSecondAndNanoWithInclusiveStartExclusiveEnd() {
        var root = mapper.candidates(parameters(301L, null, null, null, 0, 100)).getFirst();
        Instant started = Instant.ofEpochSecond(root.startedSecond(), root.startedNano());
        var inclusive = new TrajectoryListMapper.Candidates(301L, null, null, null, null,
                started.getEpochSecond(), started.getNano(), started.plusSeconds(1).getEpochSecond(),
                started.getNano(), 0, 100);
        assertTrue(mapper.candidates(inclusive).stream().anyMatch(row -> row.rootInputId().equals(root.rootInputId())));
        var exclusive = new TrajectoryListMapper.Candidates(301L, null, null, null, null,
                started.minusSeconds(1).getEpochSecond(), started.getNano(), started.getEpochSecond(),
                started.getNano(), 0, 100);
        assertFalse(mapper.candidates(exclusive).stream().anyMatch(row -> row.rootInputId().equals(root.rootInputId())));
        assertTrue(mapper.readAt().isAfter(started.minusSeconds(1)));
    }

    private static TrajectoryListMapper.Candidates parameters(Long scopeTenant, Long tenant,
            List<String> visible, Long agentId, long offset, int limit) {
        return new TrajectoryListMapper.Candidates(scopeTenant, tenant, null, agentId, visible,
                0, 0, Instant.now().plusSeconds(60).getEpochSecond(), 0, offset, limit);
    }
}
