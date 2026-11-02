package com.skillforge.core.foundation.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.assets.FoundationFileContracts;
import com.skillforge.core.foundation.assets.FoundationFileService;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** 真实数据库逐行事务、故障回滚和持久恢复；文件/权限替身仅提供已授权材料，不证明HTTP身份链。 */
class FoundationJobPersistenceIntegrationTest {
    private static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    /** 外部S03 lane不启动容器；父拥有数据库生命周期与资源核验。 */
    private static final String EXTERNAL_URL=System.getenv().keySet().stream().anyMatch(key->key.startsWith("SKILLFORGE_S03_EXTERNAL_"))?required("SKILLFORGE_S03_EXTERNAL_JDBC_URL"):null;
    private static JdbcTemplate admin;
    private static JdbcTemplate business;
    private static HikariDataSource application;
    private static DataSourceTransactionManager transactions;
    private static FoundationJobMapper mapper;
    private FoundationAccess access;
    private FoundationFileService files;
    private String file;
    private String job;

    /** 全量Flyway只在新fixture中执行，业务连接永不使用privileged迁移身份。 */
    @BeforeAll static void schema() throws Exception {
        if(EXTERNAL_URL==null) DATABASE.start();
        String url=EXTERNAL_URL==null?DATABASE.getJdbcUrl():EXTERNAL_URL;
        var source=new DriverManagerDataSource(url,EXTERNAL_URL==null?DATABASE.getUsername():required("SKILLFORGE_S03_EXTERNAL_ADMIN_USER"),EXTERNAL_URL==null?DATABASE.getPassword():required("SKILLFORGE_S03_EXTERNAL_ADMIN_PASSWORD"));
        admin=new JdbcTemplate(source);
        if(EXTERNAL_URL==null) admin.execute("CREATE ROLE skillforge NOSUPERUSER NOBYPASSRLS");
        else verifyExternalFixture();
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        String user=EXTERNAL_URL==null?"jobs_test":required("SKILLFORGE_S03_EXTERNAL_DB_USER");
        if(EXTERNAL_URL==null) {
            admin.execute("CREATE ROLE jobs_test LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS");
            admin.execute("GRANT USAGE ON SCHEMA public TO jobs_test");
            admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO jobs_test");
        }
        // 一万行仍逐行独立提交；复用物理连接，避免短连接在 Windows 累积 TIME_WAIT 耗尽端口。
        application=new HikariDataSource();application.setJdbcUrl(url);application.setUsername(user);
        application.setPassword(EXTERNAL_URL==null?"test-only-password":required("SKILLFORGE_S03_EXTERNAL_DB_PASSWORD"));
        application.setMaximumPoolSize(2);application.setMinimumIdle(0);
        business=new JdbcTemplate(application); transactions=new DataSourceTransactionManager(application);
        assertFalse(business.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
        var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(new TenantRlsInterceptor());
        var factory=new SqlSessionFactoryBean();factory.setDataSource(application);factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/foundation/FoundationJobMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(FoundationJobMapper.class);
        // 合成领域消费者独立保存业务效果；同scope RLS与主任务复合FK保证错误租户不能写入。
        admin.execute("CREATE TABLE s03_job_effects(scope_key text NOT NULL,job_id varchar(36) NOT NULL,row_number integer NOT NULL,label text NOT NULL,PRIMARY KEY(job_id,row_number),FOREIGN KEY(scope_key,job_id) REFERENCES nw_foundation_admin_jobs(scope_key,id))");
        admin.execute("ALTER TABLE s03_job_effects ENABLE ROW LEVEL SECURITY");admin.execute("ALTER TABLE s03_job_effects FORCE ROW LEVEL SECURITY");
        admin.execute("CREATE POLICY job_effect_scope ON s03_job_effects USING(scope_key=current_setting('app.tenant_id',true)) WITH CHECK(scope_key=current_setting('app.tenant_id',true))");
        assertTrue(user.matches("[a-z][a-z0-9_]{0,62}"));admin.execute("GRANT SELECT,INSERT ON s03_job_effects TO "+user);
        if(Boolean.TRUE.equals(admin.queryForObject("SELECT to_regprocedure('s03_host_measure.record_row()') IS NOT NULL",Boolean.class)))
            admin.execute("CREATE TRIGGER s03_measure_committed_rows AFTER INSERT OR UPDATE OR DELETE ON s03_job_effects FOR EACH ROW EXECUTE FUNCTION s03_host_measure.record_row()");
        admin.update("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target,is_active) VALUES(701,'host-jobs-test','CSV组合验证',0.99,true)");
        admin.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'CSV机构','host-jobs-seven',701),(8,'其他机构','host-jobs-eight',701)");
    }

    /** 每例只重置本次新库的合成材料，原始受理与效果fixture独立于统计窗口。 */
    @BeforeEach void seed() {
        TenantContext.clear();
        admin.execute("DROP TRIGGER IF EXISTS s03_reject_receipt ON nw_foundation_admin_job_receipt_blocks");
        admin.execute("TRUNCATE s03_job_effects,nw_foundation_admin_job_rows,nw_foundation_admin_job_receipt_blocks,nw_foundation_admin_jobs,nw_foundation_file_references,nw_foundation_files CASCADE");
        file=UUID.randomUUID().toString();job=UUID.randomUUID().toString();
        admin.update("INSERT INTO nw_foundation_files(id,tenant_id,created_by,name,mime_type,size_bytes,sha256,blob_key,state,source_kind) VALUES(?,7,'11','input.csv','text/csv',1,?,?,'ready','upload')",file,"a".repeat(64),file);
        access=mock(FoundationAccess.class);files=mock(FoundationFileService.class);
        when(access.currentSubject()).thenAnswer(invocation->new FoundationAccess.Subject(false,TenantContext.getTenantId(),"11"));
        when(access.withSubject(any(),any())).thenAnswer(invocation->{var subject=(FoundationAccess.Subject)invocation.getArgument(0);String previous=TenantContext.getTenantId();try{TenantContext.setTenantId(subject.tenantId());return ((Supplier<?>)invocation.getArgument(1)).get();}finally{if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);}});
        when(files.createResult(anyString(),any())).thenAnswer(invocation->{String id=UUID.randomUUID().toString();byte[] bytes=invocation.getArgument(1);business.update("INSERT INTO nw_foundation_files(id,tenant_id,created_by,name,mime_type,size_bytes,sha256,blob_key,state,source_kind) VALUES(?,7,'11',?,'text/csv',?,?,?,'ready','job_result')",id,invocation.getArgument(0),bytes.length,"a".repeat(64),id);return metadata(id,bytes.length);});
    }
    /** 还原线程scope，避免后续lane继承机构身份。 */
    @AfterEach void clear() { TenantContext.clear(); }
    /** 先释放本测试的业务连接，再停止自有容器；借用的S03数据库仍归父清理。 */
    @AfterAll static void close() {
        try { if(application!=null) application.close(); }
        finally { if(EXTERNAL_URL==null) DATABASE.stop(); }
    }

    /** 行效果已执行但回执触发器拒绝时，真实事务必须同时回滚效果和恢复身份。 */
    @Test void receiptFailureRollsBackTheDomainEffect() {
        queued("label\r\none\r\n",1);
        admin.execute("CREATE OR REPLACE FUNCTION s03_reject_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled receipt failure'; END $$");
        admin.execute("CREATE TRIGGER s03_reject_receipt BEFORE INSERT ON nw_foundation_admin_job_receipt_blocks FOR EACH ROW EXECUTE FUNCTION s03_reject_receipt()");
        TenantContext.setTenantId("8");assertTrue(service().runNext(false));assertEquals("8",TenantContext.getTenantId());
        assertEquals(0,count("s03_job_effects"));assertEquals(0,count("nw_foundation_admin_job_receipt_blocks"));
        assertEquals("failed",admin.queryForObject("SELECT state FROM nw_foundation_admin_jobs WHERE id=?",String.class,job));
        assertEquals(0,admin.queryForObject("SELECT completed_rows FROM nw_foundation_admin_jobs WHERE id=?",Integer.class,job));
    }

    /** 重建组件从已提交位图恢复；已提交行不重放、错误仍定位到逻辑行、无变化progress不UPDATE。 */
    @Test void reconstructedWorkerSkipsCommittedEffectsAndPreservesErrorLocation() {
        queued("label\r\none\r\ntwo\r\nbad\r\n",3);
        admin.update("INSERT INTO s03_job_effects(scope_key,job_id,row_number,label) VALUES('7',?,2,'one')",job);
        admin.update("INSERT INTO nw_foundation_admin_job_receipt_blocks(job_id,scope_key,block_number,completed_bits) VALUES(?,'7',0,set_bit(decode(repeat('00',32),'hex'),0,1))",job);
        admin.update("UPDATE nw_foundation_admin_jobs SET state='running',lease_owner=?,lease_until=now()-interval '1 second' WHERE id=?",UUID.randomUUID().toString(),job);
        TenantContext.setTenantId("8");assertTrue(service().runNext(false));assertEquals("8",TenantContext.getTenantId());
        assertEquals(2,count("s03_job_effects"));assertEquals(1,count("nw_foundation_admin_job_rows"));
        assertEquals(3,admin.queryForObject("SELECT sum(bit_count(completed_bits))::integer FROM nw_foundation_admin_job_receipt_blocks WHERE job_id=?",Integer.class,job));
        assertEquals(3,admin.queryForObject("SELECT completed_rows FROM nw_foundation_admin_jobs WHERE id=?",Integer.class,job));
        assertEquals("failed",admin.queryForObject("SELECT state FROM nw_foundation_admin_jobs WHERE id=?",String.class,job));
        TenantContext.setTenantId("7");assertEquals(List.of(new FoundationJobContracts.RowError(4,"label","受控坏行")),service().errors(job,1,20));
        assertFalse(service().runNext(false));assertEquals(2,count("s03_job_effects"));
        new TransactionTemplate(transactions).executeWithoutResult(status->{mapper.bind("7",false);assertTrue(mapper.hasReceipt(job,2));assertTrue(mapper.hasReceipt(job,4));assertEquals(0,mapper.receipt(job,2));assertEquals(0,mapper.progress(job,"other-owner"));});
        TenantContext.setTenantId("8");assertNull(mapper.find(job));assertFalse(mapper.hasReceipt(job,2));assertEquals(0,mapper.receipt(job,5));
    }

    /** 同一受控10000成功行走真实worker；计量由外部窗口触发器记录，不预设固定progress次数。 */
    @Test void tenThousandSuccessfulRowsUseFortyBlocksAndNoSuccessDetails() throws Exception {
        StringBuilder csv=new StringBuilder("label\r\n");for(int i=0;i<10000;i++)csv.append("value-").append(i).append("\r\n");
        queued(csv.toString(),10000);
        String window=beginMeasurement(csv.toString());
        TenantContext.setTenantId("7");assertTrue(service().runNext(false));
        assertEquals(10000,count("s03_job_effects"));assertEquals(40,count("nw_foundation_admin_job_receipt_blocks"));assertEquals(0,count("nw_foundation_admin_job_rows"));
        assertEquals("succeeded",admin.queryForObject("SELECT state FROM nw_foundation_admin_jobs WHERE id=?",String.class,job));
        assertEquals(10000,admin.queryForObject("SELECT completed_rows FROM nw_foundation_admin_jobs WHERE id=?",Integer.class,job));
        assertEquals(0,mapper.receipt(job,10002));
        if(window!=null) {
            String result=admin.queryForObject("SELECT s03_host_measure.end_window(?)::text",String.class,window);
            var document=new ObjectMapper().readTree(result);
            ((com.fasterxml.jackson.databind.node.ObjectNode)document).put("source_role","candidate_production_worker_with_fixture_handler");
            ((com.fasterxml.jackson.databind.node.ObjectNode)document).put("receipt_payload_bytes",admin.queryForObject("SELECT COALESCE(sum(octet_length(completed_bits)),0) FROM nw_foundation_admin_job_receipt_blocks",Long.class));
            Path output=Path.of(required("SKILLFORGE_S03_EXTERNAL_MEASURE_DIR"));Files.createDirectories(output);Files.writeString(output.resolve("host-jobs-10000.json"),document.toPrettyString(),StandardCharsets.UTF_8);
        }
    }

    /** 同一持久任务比较完整读与列表投影；选项大小遵循入口10项、每值512字符的边界。 */
    @Test void listPagesKeepEverySummaryFieldWithoutReadingExecutionOptions() throws Exception {
        queued("label\r\none\r\n",10);
        var options=new java.util.LinkedHashMap<String,String>();
        for(int index=0;index<10;index++)options.put("option_"+index,"x".repeat(512));
        String optionsJson=new ObjectMapper().writeValueAsString(options);
        admin.update("UPDATE nw_foundation_admin_jobs SET options_json=?::jsonb,state='failed',completed_rows=8,error_rows=2,error_message='可纠正的导入错误',result_file_id=?,created_at='2026-10-01T00:00:00Z',expires_at='2026-11-01T00:00:00Z' WHERE id=?",optionsJson,file,job);
        String older=UUID.randomUUID().toString();
        admin.update("INSERT INTO nw_foundation_admin_jobs(id,tenant_id,member_id,handler,operation,input_file_id,options_json,idempotency_key,request_digest,total_rows,state,created_at) VALUES(?,7,'11','s03_fixture','import',?,'{}'::jsonb,?,? ,3,'queued','2026-09-01T00:00:00Z')",older,file,"key-"+older,"b".repeat(64));
        TenantContext.setTenantId("7");
        var tx=new TransactionTemplate(transactions);
        var complete=tx.execute(status->{mapper.bind("7",false);return mapper.find(job);});
        assertEquals(options,new ObjectMapper().readValue(complete.getOptionsJson(),Map.class));
        var expected=new FoundationJobContracts.JobView(job,"s03_fixture","import","failed",10,8,2,"可纠正的导入错误",file,complete.getCreatedAt(),complete.getExpiresAt());
        var service=service();
        var first=service.list(1,1);var second=service.list(2,1);
        assertEquals(List.of(expected),first.items());assertEquals(2,first.total());assertEquals(1,first.page());assertEquals(1,first.pageSize());
        assertEquals(2,second.total());assertEquals(2,second.page());assertEquals(List.of(service.detail(older)),second.items());
        assertEquals(expected,service.detail(job));
        var narrow=tx.execute(status->{mapper.bind("7",false);return mapper.list(0,1).getFirst();});
        assertNull(narrow.getOptionsJson());assertNull(narrow.getRequestDigest());assertNull(narrow.getIdempotencyKey());
        assertNull(narrow.getInputFileId());assertNull(narrow.getMemberId());assertNull(narrow.getLeaseOwner());
        long omittedOptionBytes=complete.getOptionsJson().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(omittedOptionBytes>5120);
        // 同输入负控：原SELECT *或恢复读取仍返回完整options；旧列表实现会在上述assertNull失败。
        var locked=tx.execute(status->{mapper.bind("7",false);return mapper.lock(job);});
        assertEquals(complete.getOptionsJson(),locked.getOptionsJson());
        assertEquals(complete.getRequestDigest(),locked.getRequestDigest());
        TenantContext.setTenantId("8");assertTrue(service.list(1,1).items().isEmpty());
        assertEquals(0,service.list(1,1).total());
        System.out.printf("foundation-job same-input: fullRows=1 listRows=1 omittedOptionsUtf8Bytes=%d listOptionsUtf8Bytes=0%n",omittedOptionBytes);
    }

    /** 真实双连接锁竞争：详情读取已提交快照，写路径仍保持排他行锁。 */
    @Test void detailReadsCommittedSummaryWithoutWaitingForOrHoldingAWriteLock() throws Exception {
        queued("label\r\none\r\n",10);
        TenantContext.setTenantId("7");
        var service=service();var expected=service.detail(job);var tx=new TransactionTemplate(transactions);
        try(var writer=application.getConnection()) {
            writer.setAutoCommit(false);
            try(var settings=writer.createStatement()) {
                settings.execute("SET LOCAL app.tenant_id='7'");settings.execute("SET LOCAL app.platform_admin='false'");
            }
            try(var update=writer.prepareStatement("UPDATE nw_foundation_admin_jobs SET total_rows=11 WHERE id=? AND scope_key='7'")) {
                update.setString(1,job);assertEquals(1,update.executeUpdate());
            }
            // 同输入负控：原锁定路径在真实写锁下必须超时，排除fixture未持锁造成假通过。
            var blocked=assertThrows(org.springframework.dao.DataAccessException.class,()->tx.execute(status->{
                business.execute("SET LOCAL lock_timeout='500ms'");mapper.bind("7",false);return mapper.lock(job);
            }));
            assertEquals("55P03",assertInstanceOf(java.sql.SQLException.class,blocked.getMostSpecificCause()).getSQLState());
            var observed=tx.execute(status->{business.execute("SET LOCAL lock_timeout='500ms'");return service.detail(job);});
            assertEquals(expected,observed);
            writer.commit();
        }
        assertEquals(11,service.detail(job).totalRows());
        // 外层事务仍未结束时，详情不能阻止第二连接取得真实排他锁。
        var outer=transactions.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
        try {
            assertEquals(11,service.detail(job).totalRows());
            try(var contender=application.getConnection()) {
                contender.setAutoCommit(false);
                try(var settings=contender.createStatement()) {
                    settings.execute("SET LOCAL app.tenant_id='7'");settings.execute("SET LOCAL app.platform_admin='false'");
                }
                try(var lock=contender.prepareStatement("SELECT id FROM nw_foundation_admin_jobs WHERE id=? AND scope_key='7' FOR UPDATE NOWAIT")) {
                    lock.setString(1,job);try(var result=lock.executeQuery()){assertTrue(result.next());assertEquals(job,result.getString(1));}
                }
                contender.rollback();
            }
        } finally {transactions.rollback(outer);}
        var missing=assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->service.detail(UUID.randomUUID().toString()));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,missing.getStatus());assertEquals("管理任务不存在",missing.getMessage());
        TenantContext.setTenantId("8");
        var foreign=assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->service.detail(job));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,foreign.getStatus());assertEquals(missing.getCode(),foreign.getCode());
        verify(access,atLeastOnce()).require("foundation.jobs.read");
    }

    /** 固定受理fixture不借用submit的预检结果；worker必须实际重新校验每行。 */
    private void queued(String csv,int rows) {
        byte[] bytes=csv.getBytes(StandardCharsets.UTF_8);when(files.download(file)).thenReturn(new FoundationFileContracts.Download(metadata(file,bytes.length),bytes));
        admin.update("INSERT INTO nw_foundation_admin_jobs(id,tenant_id,member_id,handler,operation,input_file_id,options_json,idempotency_key,request_digest,total_rows,state) VALUES(?,7,'11','s03_fixture','import',?,'{}'::jsonb,?,? ,?,'queued')",job,file,"key-"+job,"a".repeat(64),rows);
    }
    /** 每次调用创建新服务，恢复不复用旧worker内存进度。 */
    private FoundationJobService service() { return new FoundationJobService(mapper,access,files,mock(FeatureGate.class),mock(ResourceQuota.class),new ObjectMapper(),transactions,List.of(new EffectHandler())); }
    /** 通过正式handler边界写入合成领域表；故障用例验证其与回执共享真实事务。 */
    private final class EffectHandler implements CsvTransferHandler {
        /** 稳定受控消费者身份。 */ @Override public String code(){return "s03_fixture";}
        /** fixture显示名称。 */ @Override public String title(){return "CSV事务fixture";}
        /** 固定单列表头。 */ @Override public List<String> columns(){return List.of("label");}
        /** 替身提供已授权边界，不声明真实IAM验收。 */ @Override public String importPermission(){return "foundation.example.manage";}
        /** 不支持导出。 */ @Override public String exportPermission(){return null;}
        /** 无可变选项。 */ @Override public void validateOptions(Map<String,String> options){assertTrue(options.isEmpty());}
        /** bad是本fixture唯一可纠正错误，保留公开逻辑行号。 */ @Override public List<FoundationJobContracts.RowError> validateRow(Map<String,String> options,int row,List<String> cells){return cells.getFirst().equals("bad")?List.of(new FoundationJobContracts.RowError(row,"label","受控坏行")):List.of();}
        /** 唯一键不掩盖重放；若已提交行被重新执行则真实DB拒绝。 */ @Override public void importRow(Map<String,String> options,String id,int row,List<String> cells){business.update("INSERT INTO s03_job_effects(scope_key,job_id,row_number,label) VALUES(?,?,?,?)",TenantContext.getTenantId(),id,row,cells.getFirst());}
        /** 不支持导出。 */ @Override public List<List<String>> exportRows(Map<String,String> options){throw new UnsupportedOperationException();}
    }
    /** 管理员仅查询结果，不能代替业务角色执行被测副作用。 */
    private static int count(String table){return admin.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    /** 排除setup/fixture写入后才开启计数，CSV摘要与固定工作量共同约束可比条件。 */
    private String beginMeasurement(String csv) throws Exception {
        if(System.getenv("SKILLFORGE_S03_EXTERNAL_MEASURE_DIR")==null)return null;
        if(EXTERNAL_URL==null)throw new IllegalStateException("量化必须绑定父受控外部lane");
        String window="jobs-"+UUID.randomUUID();
        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(csv.getBytes(StandardCharsets.UTF_8)));
        var connectionSettings=Map.of("admin",connectionSettings(admin),"business",connectionSettings(business));
        String controls=new ObjectMapper().writeValueAsString(Map.of("scenario","csv_10000_success","csv_sha256",digest,"rows",10000,"threads",1,"transaction","per_row","fixture_handler","s03_fixture","excludes","setup_iam_files_and_fixture_writes","input_binding",required("SKILLFORGE_S03_EXTERNAL_INPUT_BINDING"),"connection_settings",connectionSettings));
        admin.queryForObject("SELECT s03_host_measure.begin_window(?,?::jsonb)",Object.class,window,controls);return window;
    }
    /** 只由外部量化分支调用；分别观察两个实际DataSource的新连接，不设置或推断服务器参数。 */
    private static Map<String,String> connectionSettings(JdbcTemplate template) throws Exception {
        try(var connection=template.getDataSource().getConnection();var statement=connection.createStatement();
                var rows=statement.executeQuery("SELECT current_setting('synchronous_commit'),current_setting('server_version'),current_setting('server_version_num')")) {
            assertTrue(rows.next(),"measurement connection settings must be observable");
            var settings=Map.of("synchronous_commit",rows.getString(1),"server_version",rows.getString(2),"server_version_num",rows.getString(3));
            assertFalse(rows.next(),"measurement connection settings must return one row");
            return settings;
        }
    }
    /** 合成受控文件投影。 */
    private static FoundationFileContracts.FileView metadata(String id,int size){return new FoundationFileContracts.FileView(id,"input.csv","text/csv",size,"a".repeat(64),0,OffsetDateTime.now());}
    /** 外部新库及父随机token/lane必须全部绑定，已消费DB不能再次重置。 */
    private static void verifyExternalFixture(){String database=admin.queryForObject("SELECT current_database()",String.class);assertTrue(database!=null&&database.matches("sf_s03_host_jobs_[a-z0-9_]+"));assertEquals(1,admin.queryForObject("SELECT count(*) FROM public.s03_host_fixture_guard WHERE lane='host_jobs' AND token=?",Integer.class,required("SKILLFORGE_S03_EXTERNAL_FIXTURE_TOKEN")));assertEquals(0,admin.queryForObject("SELECT count(*) FROM public.nw_foundation_admin_jobs",Integer.class));}
    /** 缺失参数拒绝，不输出凭据。 */
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("缺少外部fixture参数 "+name);return value;}
}
