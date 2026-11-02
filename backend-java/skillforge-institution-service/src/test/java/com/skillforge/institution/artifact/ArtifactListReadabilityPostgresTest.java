package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.core.shared.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 生产产物迁移、真实Mapper与受限角色；不连接应用数据库或启动应用服务。 */
@Testcontainers(disabledWithoutDocker = false)
class ArtifactListReadabilityPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String POLICY = ArtifactPublicInspection.POLICY_VERSION;
    private static final byte[] BODY = "受管文件原文".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Queries QUERIES = new Queries();
    private static JdbcTemplate admin;
    private static DriverManagerDataSource reader;
    private static ArtifactMapper mapper;
    @TempDir Path root;
    private ArtifactStorage storage;

    @BeforeAll static void schema() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        // 仅宿主表用最小键；所有被验证产物/持有表使用原迁移与原约束。
        admin.execute("CREATE TABLE nw_tenants(id bigint PRIMARY KEY);"
                + "CREATE TABLE nw_chat_sessions(tenant_id bigint NOT NULL,session_id text NOT NULL,PRIMARY KEY(tenant_id,session_id));"
                + "ALTER TABLE nw_chat_sessions ENABLE ROW LEVEL SECURITY;ALTER TABLE nw_chat_sessions FORCE ROW LEVEL SECURITY;"
                + "CREATE POLICY tenant_scope ON nw_chat_sessions USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::bigint)");
        for (String file : List.of("V153__execution_artifacts.sql", "V172__execution_artifact_inputs.sql",
                "V231__final_deliverable_lifecycle.sql", "V245__artifact_public_output_inspection.sql")) {
            try (var input = ArtifactListReadabilityPostgresTest.class.getClassLoader().getResourceAsStream("db/migration/" + file)) {
                assertNotNull(input, file);
                admin.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        admin.execute("CREATE ROLE artifact_list_reader LOGIN PASSWORD 'synthetic-list-test' NOSUPERUSER NOBYPASSRLS;"
                + "GRANT USAGE ON SCHEMA public TO artifact_list_reader;GRANT SELECT ON ALL TABLES IN SCHEMA public TO artifact_list_reader");
        reader = new DriverManagerDataSource(PG.getJdbcUrl(), "artifact_list_reader", "synthetic-list-test");
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(reader);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/ArtifactMapper.xml"));
        factory.setPlugins(new TenantRlsInterceptor(), QUERIES);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ArtifactMapper.class);
    }

    @BeforeEach void fixture() {
        admin.execute("TRUNCATE nw_tenants,nw_chat_sessions,nw_execution_artifacts,nw_task_output_plans,nw_deliverables CASCADE");
        admin.update("INSERT INTO nw_tenants VALUES(7),(8)");
        admin.update("INSERT INTO nw_chat_sessions VALUES(7,'main'),(7,'child'),(8,'foreign')");
        TenantContext.setTenantId("7");
        storage = new ArtifactStorage(mapper, JSON, new DataSourceTransactionManager(reader), root.toString(),
                mock(RunSettingsService.class), mock(StudioArtifactProjection.class), mock(ArtifactPublicInspection.class));
        QUERIES.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); QUERIES.clear(); }

    @Test void batchExactlyMatchesOriginalForRetentionInspectionStatusAndTenantBoundaries() throws Exception {
        List<String> ids = new ArrayList<>();
        String live = artifact(7, "main", "live", false, "ready", "valid"); ids.add(live);
        String expired = artifact(7, "main", "expired", true, "ready", "valid"); ids.add(expired);
        String hold = artifact(7, "main", "hold", true, "ready", "valid"); ids.add(hold);
        output("hold-run", "main", "planned", null);
        admin.update("INSERT INTO nw_deliverable_file_holds VALUES(7,'hold-run',?)", file(hold));
        String representation = artifact(7, "main", "representation", true, "ready", "valid"); ids.add(representation);
        String source = artifact(7, "main", "source", true, "ready", "valid"); ids.add(source);
        output("published-run", "main", "finalized", "settled");
        admin.update("INSERT INTO nw_task_results(tenant_id,run_id,session_id,status) VALUES(7,'published-run','main','pending')");
        admin.update("INSERT INTO nw_deliverables(tenant_id,id,session_id,logical_key) VALUES(7,'deliverable','main','report')");
        admin.update("INSERT INTO nw_deliverable_revisions(tenant_id,id,deliverable_id,revision_no,run_id,title,kind,fingerprint,change_type)"
                + " VALUES(7,'revision','deliverable',1,'published-run','报告','file','fingerprint','new')");
        admin.update("INSERT INTO nw_deliverable_representations(tenant_id,id,revision_id,role,format,primary_preview)"
                + " VALUES(7,'representation','revision','source','txt',false)");
        admin.update("INSERT INTO nw_deliverable_representation_files VALUES(7,'representation',?,'report.txt','报告.txt',0)", file(representation));
        admin.update("INSERT INTO nw_deliverable_representation_sources VALUES(7,'representation',?,?)", file(source), sha(BODY));
        admin.update("UPDATE nw_task_results SET status='published' WHERE tenant_id=7 AND run_id='published-run'");
        String evidence = artifact(7, "child", "evidence", true, "ready", "valid"); ids.add(evidence);
        admin.update("INSERT INTO nw_task_evidence(tenant_id,id,run_id,file_id,summary) VALUES(7,'evidence','published-run',?,'合法证据')", file(evidence));
        String oldEvidence = artifact(7, "main", "expired-evidence", true, "ready", "valid"); ids.add(oldEvidence);
        admin.update("INSERT INTO nw_task_evidence(tenant_id,id,run_id,file_id,summary,expires_at)"
                + " VALUES(7,'old-evidence','published-run',?,'已到期证据',now()-interval '1 day')", file(oldEvidence));
        String pendingGoal = artifact(7, "main", "goal-pending", true, "ready", "valid"); ids.add(pendingGoal);
        output("run-goal-pending", "main", "finalized", "pending");
        for (String mode : List.of("missing", "hash", "size", "policy"))
            ids.add(artifact(7, "main", "inspection-" + mode, false, "ready", mode));
        ids.add(artifact(7, "main", "file-expired", false, "expired", "valid"));
        ids.add(artifact(7, "main", "file-deleted", false, "deleted", "valid"));
        String child = artifact(7, "child", "child-live", false, "ready", "valid"); ids.add(child);
        String foreign = artifact(8, "foreign", "foreign", false, "ready", "valid"); ids.add(foreign);
        var actual = new HashSet<>(mapper.readableArtifactIds("7", ids, POLICY));
        assertEquals(java.util.Set.of(live, hold, representation, source, evidence, pendingGoal, child), actual);
        for (String id : ids) assertEquals(mapper.anyFileReadable("7", id, POLICY), actual.contains(id), id);
        assertTrue(mapper.readableArtifactIds("8", ids, POLICY).isEmpty(), "伪造tenant参数不能越过真实RLS上下文");
        try (var connection = reader.getConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
            assertTrue(result.next()); assertTrue(result.getBoolean(1));
        }
        String failedArtifact=artifact(7,"main","artifact-failed",false,"ready","valid");
        admin.update("UPDATE nw_execution_artifacts SET status='failed' WHERE id=?",failedArtifact);
        var beforeTool=storage.list("7","main").stream()
                .filter(artifact -> "main".equals(artifact.sessionId()) && "ready".equals(artifact.status()))
                .sorted(java.util.Comparator.comparing(ArtifactMetadata::expiresAt).thenComparing(ArtifactMetadata::id)).toList();
        assertEquals(java.util.Set.of(live,hold,representation,source,pendingGoal),new HashSet<>(beforeTool.stream().map(ArtifactMetadata::id).toList()));
        assertFalse(beforeTool.stream().anyMatch(artifact -> evidence.equals(artifact.id()) || failedArtifact.equals(artifact.id())));
        var available=storage.listForTool("7","main",0,100);
        assertEquals(beforeTool,available.artifacts(),"tool SQL must preserve the complete original Handler filtering and ordering chain");
        assertEquals((long)beforeTool.size(),available.total());
        assertEquals(0L,storage.listForTool("8","foreign",0,100).total(),"tenant parameter cannot bypass current RLS");
        TenantContext.setTenantId("8");
        assertEquals(List.of(foreign), mapper.readableArtifactIds("8", ids, POLICY));
        assertEquals(List.of(foreign),storage.listForTool("8","foreign",0,100).artifacts().stream().map(ArtifactMetadata::id).toList());
    }

    @Test void realStorageListsUseThreeQueriesAtDifferentSizesAndOneWhenEmpty() throws Exception {
        for (int i = 0; i < 20; i++) artifact(7, "main", "bulk-" + i, false, "ready", "valid");
        artifact(7, "child", "one", false, "ready", "valid");
        for (String session : List.of("child", "main")) {
            QUERIES.clear();
            var inputs = mapper.sessionInputs("7", session);
            var rows = mapper.list("7", session, POLICY);
            var original = new HashMap<String, String>();
            for (var row : rows) original.put(row.id(), mapper.anyFileReadable("7", row.id(), POLICY) ? row.status() : "expired");
            assertTrue(inputs.isEmpty());
            assertEquals(2 + rows.size(), QUERIES.total());
            QUERIES.clear();
            var listed = storage.list("7", session);
            assertEquals(rows.size(), listed.size());
            listed.forEach(item -> assertEquals(original.get(item.id()), item.status()));
            assertEquals(3, QUERIES.total());
            assertEquals(1, QUERIES.count("readableArtifactIds"));
            assertEquals(0, QUERIES.count("anyFileReadable"));
        }
        QUERIES.clear();
        assertEquals(21, storage.listSessions("7", List.of("main", "child")).size());
        assertEquals(3, QUERIES.total());
        QUERIES.clear(); assertTrue(storage.list("7", "absent").isEmpty()); assertEquals(1, QUERIES.total());
        assertEquals(0, QUERIES.count("sessionInputs")); assertEquals(0, QUERIES.count("readableArtifactIds"));
        QUERIES.clear(); assertTrue(storage.listSessions("7", List.of("absent")).isEmpty()); assertEquals(1, QUERIES.total());
        assertEquals(0, QUERIES.count("sessionInputsForSessions")); assertEquals(0, QUERIES.count("readableArtifactIds"));
    }

    @Test void singleMetadataAndActualFileReadStillCheckCurrentReadability() throws Exception {
        String id = artifact(7, "main", "actual-read", false, "ready", "valid");
        ArtifactRow row = mapper.find("7", id);
        assertEquals("ready", storage.list("7", "main").getFirst().status());
        QUERIES.clear();
        assertArrayEquals(BODY, storage.verifiedFile(row, "report.txt", 1024).content());
        assertTrue(QUERIES.count("fileReadable") > 0);
        assertTrue(QUERIES.count("anyFileReadable") > 0);
        assertEquals(0, QUERIES.count("readableArtifactIds"));
        admin.update("UPDATE nw_execution_artifact_files SET expires_at=now()-interval '1 day' WHERE tenant_id=7 AND artifact_id=?", id);
        QUERIES.clear();
        assertEquals("expired", storage.metadata(row).status());
        assertEquals(1, QUERIES.count("anyFileReadable"));
        assertEquals(HttpStatus.GONE, assertThrows(ApiException.class,
                () -> storage.verifiedFile(row, "report.txt", 1024)).getStatus());
    }

    @Test void listStillRejectsManifestAndInputsDigestCorruption() throws Exception {
        String id = artifact(7, "main", "bad-manifest", false, "ready", "valid");
        admin.update("UPDATE nw_execution_artifacts SET sha256=? WHERE id=?", "0".repeat(64), id);
        assertThrows(IllegalStateException.class, () -> storage.list("7", "main"));
        String other = artifact(7, "child", "bad-inputs", false, "ready", "valid");
        admin.update("UPDATE nw_execution_artifacts SET inputs_sha256=? WHERE id=?", "0".repeat(64), other);
        assertThrows(IllegalStateException.class, () -> storage.listSessions("7", List.of("child")));
    }

    @Test void toolPageReadsOneOfTwentyManifestsAndOnlyItsProvenanceRows() throws Exception {
        String parent=artifact(7,"child","page-parent",false,"ready","valid");
        var provenance=new ArtifactProvenance(parent,"report.txt",BODY.length,sha(BODY));
        for(int index=0;index<20;index++) {
            String id=artifact(7,"main","page-"+index,false,"ready","valid");
            admin.update("INSERT INTO nw_execution_artifact_inputs VALUES(7,?,0,?,'report.txt',?,?)",id,parent,BODY.length,sha(BODY));
            admin.update("UPDATE nw_execution_artifacts SET inputs_sha256=? WHERE id=?",sha(JSON.writeValueAsBytes(List.of(provenance))),id);
        }
        var expiry=Instant.now().plusSeconds(3600);
        admin.update("UPDATE nw_execution_artifacts SET expires_at=? WHERE tenant_id=7 AND session_id='main'",java.sql.Timestamp.from(expiry));
        QUERIES.clear();var full=storage.list("7","main");assertEquals(20,full.size());assertEquals(3,QUERIES.total());
        var fullRows=material(QUERIES.query("list"));var fullInputs=material(QUERIES.query("sessionInputs"));
        QUERIES.clear();var page=storage.listForTool("7","main",0,1);
        assertEquals(20L,page.total());assertEquals(1,page.artifacts().size());assertEquals(3,QUERIES.total());
        var expected=full.stream().sorted(java.util.Comparator.comparing(ArtifactMetadata::expiresAt).thenComparing(ArtifactMetadata::id)).toList();
        assertEquals(List.of(expected.getFirst()),page.artifacts());assertEquals(List.of(provenance),page.artifacts().getFirst().artifactInputs());
        var pageRows=material(QUERIES.query("listToolPage"));var pageInputs=material(QUERIES.query("inputsForArtifacts"));
        assertEquals(20,fullRows.rows());assertEquals(1,pageRows.rows());assertEquals(20,fullInputs.rows());assertEquals(1,pageInputs.rows());
        assertTrue(fullRows.bytes()>pageRows.bytes());assertTrue(fullInputs.bytes()>pageInputs.bytes());
        assertFalse(QUERIES.query("countToolAvailable").bound().getSql().contains("manifest"));
        System.out.println("ARTIFACT_TOOL_PAGE_READ total=20 limit=1 full_manifest_rows="+fullRows.rows()+" page_manifest_rows="+pageRows.rows()
                +" full_input_rows="+fullInputs.rows()+" page_input_rows="+pageInputs.rows()+" baseline_row_json_bytes="+(fullRows.bytes()+fullInputs.bytes())
                +" page_row_json_bytes="+(pageRows.bytes()+pageInputs.bytes())+" boundary=selected-material-not-latency-or-disk");
        assertEquals(List.of(expected.get(1)),storage.listForTool("7","main",1,1).artifacts());
        QUERIES.clear();var past=storage.listForTool("7","main",20,1);assertEquals(20L,past.total());assertTrue(past.artifacts().isEmpty());assertEquals(1,QUERIES.total());
        QUERIES.clear();assertEquals(0L,storage.listForTool("7","absent",0,1).total());assertEquals(1,QUERIES.total());
    }

    @Test void toolPageChecksSelectedMaterialsWhileOrdinaryListsStillCheckAll() throws Exception {
        var ids=List.of(artifact(7,"main","page-good",false,"ready","valid"),artifact(7,"main","page-bad",false,"ready","valid")).stream().sorted().toList();
        admin.update("UPDATE nw_execution_artifacts SET expires_at=? WHERE tenant_id=7 AND session_id='main'",java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        var original=mapper.find("7",ids.get(1));
        admin.update("UPDATE nw_execution_artifacts SET sha256=? WHERE id=?","0".repeat(64),ids.get(1));
        assertEquals(ids.getFirst(),storage.listForTool("7","main",0,1).artifacts().getFirst().id());
        assertEquals("artifact_manifest_digest_mismatch",assertThrows(IllegalStateException.class,()->storage.listForTool("7","main",1,1)).getMessage());
        assertThrows(IllegalStateException.class,()->storage.list("7","main"));
        admin.update("UPDATE nw_execution_artifacts SET sha256=?,inputs_sha256=? WHERE id=?",original.sha256(),"0".repeat(64),ids.get(1));
        assertEquals(ids.getFirst(),storage.listForTool("7","main",0,1).artifacts().getFirst().id());
        assertEquals("artifact_inputs_digest_mismatch",assertThrows(IllegalStateException.class,()->storage.listForTool("7","main",1,1)).getMessage());
        assertThrows(IllegalStateException.class,()->storage.listSessions("7",List.of("main")));
    }

    private record Material(long rows,long bytes) { }
    private Material material(Queries.Query query) throws Exception {
        assertNotNull(query);
        try(var connection=reader.getConnection()) {
            connection.setAutoCommit(false);
            try(var bind=connection.prepareStatement("SELECT set_config('app.tenant_id','7',true)")){bind.execute();}
            try(var aggregate=connection.prepareStatement("SELECT count(*),coalesce(sum(octet_length(row_to_json(r)::text)),0) FROM ("+query.bound().getSql()+") r")) {
                new DefaultParameterHandler(query.statement(),query.parameters(),query.bound()).setParameters(aggregate);
                try(var result=aggregate.executeQuery()){assertTrue(result.next());return new Material(result.getLong(1),result.getLong(2));}
            } finally {connection.rollback();}
        }
    }

    private String artifact(long tenant, String session, String key, boolean expired, String status, String inspection) throws Exception {
        String id = UUID.nameUUIDFromBytes((tenant + ":" + key).getBytes(StandardCharsets.UTF_8)).toString();
        String manifest = JSON.writeValueAsString(List.of(new ArtifactMetadata.File("report.txt", BODY.length, sha(BODY))));
        Instant deadline = Instant.now().plusSeconds(expired ? -3600 : 3600);
        admin.update("INSERT INTO nw_execution_artifacts(id,tenant_id,session_id,run_id,invocation_id,type,name,size,sha256,manifest,inputs_sha256,created_at,expires_at,status)"
                + " VALUES(?,?,?,?,?,'file',?,?,?,?::jsonb,?,now(),?,'ready')", id, tenant, session, "run-" + key,
                "invocation-" + key, key, BODY.length, sha(manifest.getBytes(StandardCharsets.UTF_8)), manifest,
                sha("[]".getBytes(StandardCharsets.UTF_8)), java.sql.Timestamp.from(deadline));
        admin.update("INSERT INTO nw_execution_artifact_files VALUES(?,?,?,?,?,?,?,?)", tenant, file(id), id,
                "report.txt", BODY.length, sha(BODY), java.sql.Timestamp.from(deadline), status);
        if (!inspection.equals("missing")) admin.update("INSERT INTO nw_execution_artifact_public_inspections"
                + "(tenant_id,artifact_id,path,size,sha256,scope_digest,policy_version) VALUES(?,?,'report.txt',?,?,?,?)",
                tenant, id, inspection.equals("size") ? BODY.length + 1 : BODY.length,
                inspection.equals("hash") ? "0".repeat(64) : sha(BODY), "1".repeat(64), inspection.equals("policy") ? "prior-policy" : POLICY);
        Path directory = root.resolve("ready").resolve(Long.toString(tenant)).resolve(id);
        Files.createDirectories(directory); Files.write(directory.resolve("report.txt"), BODY);
        return id;
    }

    private void output(String run, String session, String state, String goal) {
        admin.update("INSERT INTO nw_task_output_plans(tenant_id,run_id,session_id,owner_session_id,run_identity,state,plan,goal_completion_state)"
                + " VALUES(7,?,?,?,'{}'::jsonb,?,'{}'::jsonb,?)", run, session, session, state, goal);
    }
    private static String file(String artifact) { return artifact + ":report.txt"; }
    private static String sha(byte[] value) { return ArtifactStorage.hex(ArtifactStorage.hash().digest(value)); }

    /** STATEMENT local cache makes each counted Mapper query a real business SELECT. */
    @Intercepts(@Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    public static final class Queries implements Interceptor {
        private final Map<String, Integer> calls = new HashMap<>();
        private final Map<String, Query> queries = new HashMap<>();
        private record Query(MappedStatement statement,BoundSql bound,Object parameters) { }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String id = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (id.startsWith(ArtifactMapper.class.getName() + ".")) {
                String method=id.substring(id.lastIndexOf('.')+1);calls.merge(method,1,Integer::sum);
                var statement=(MappedStatement)invocation.getArgs()[0];Object parameters=invocation.getArgs()[1];
                queries.put(method,new Query(statement,statement.getBoundSql(parameters),parameters));
            }
            return invocation.proceed();
        }
        void clear() { calls.clear(); queries.clear(); }
        Query query(String method) { return queries.get(method); }
        int count(String method) { return calls.getOrDefault(method, 0); }
        int total() { return calls.values().stream().mapToInt(Integer::intValue).sum(); }
    }
}
