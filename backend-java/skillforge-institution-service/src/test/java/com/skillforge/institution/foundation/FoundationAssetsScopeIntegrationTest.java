package com.skillforge.institution.foundation;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.assets.FoundationFileMapper;
import com.skillforge.core.foundation.jobs.FoundationJobMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.institution.integration.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 PostgreSQL 验证应用 SQL 在可绕过 RLS 的数据库账号下仍限制普通文件与任务归属。 */
class FoundationAssetsScopeIntegrationTest extends AbstractIntegrationTest {
    @Autowired FoundationFileMapper files;
    @Autowired FoundationJobMapper jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired PlatformTransactionManager manager;
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void bypassRlsConnectionStillSeparatesPlatformAndTwoTenantFilesJobsAndWrites() {
        TenantContext.clear();
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)),"此用例必须实际使用可绕过 RLS 的 Testcontainers 连接");
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            String first=createTenant(tenants,plans,"assets-first-"+UUID.randomUUID());
            String second=createTenant(tenants,plans,"assets-second-"+UUID.randomUUID());
            var platform=fixture(null); var firstRows=fixture(first); var secondRows=fixture(second);
            for (var actor : List.of(platform,firstRows,secondRows)) {
                bind(actor.tenant());
                assertEquals(List.of(actor.file()),files.list(0,20).stream().map(row->row.getId()).toList(),"文件列表 scope="+(actor.tenant()==null?"platform":actor.tenant()));
                assertEquals(1,files.count());
                assertEquals(List.of(actor.job()),jobs.list(0,20).stream().map(row->row.getId()).toList());
                assertEquals(1,jobs.count());
                assertEquals(actor.job(),jobs.byKey("shared-smoke-key").getId());
                assertEquals(actor.file(),files.lock(actor.file()).getId());
                for (var foreign : List.of(platform,firstRows,secondRows)) {
                    if (actor == foreign) continue;
                    assertNull(files.lock(foreign.file()));
                    assertNull(jobs.lock(foreign.job()));
                    assertNull(jobs.find(foreign.job()));
                    assertEquals(0,files.attach(foreign.file(),"foundation.job",actor.job()));
                    assertEquals(0,files.detach(foreign.file(),"foundation.job",foreign.job()));
                    assertEquals(0,files.markDeleting(foreign.file()));
                    assertEquals(0,files.releaseJobReferences(foreign.job()));
                    assertEquals(List.of(),files.references(foreign.file()));
                    assertEquals(0,jobs.cancel(foreign.job()));
                    assertFalse(jobs.hasReceipt(foreign.job(),2));
                    assertEquals(List.of(),jobs.errors(foreign.job(),0,20));
                    assertEquals(0,jobs.receipt(foreign.job(),3));
                    assertEquals(0,jobs.deleteReceipts(foreign.job()));
                    assertEquals(0,jobs.deleteReceiptBlocks(foreign.job()));
                }
                assertTrue(jobs.hasReceipt(actor.job(),2));
                assertEquals(1,files.references(actor.file()).size());
            }
            status.setRollbackOnly();
        });
    }
    /** 一万行恢复身份只有40个有界块；批次进度与重复行实际受影响行数可由PostgreSQL确认。 */
    @Test void compactReceiptsRecoverEveryRowAndSkipUnchangedProgress(){new TransactionTemplate(manager).executeWithoutResult(status->{String tenant=createTenant(tenants,plans,"compact-job-"+UUID.randomUUID());var rows=fixture(tenant);bind(tenant);String owner=UUID.randomUUID().toString();jdbc.update("UPDATE nw_foundation_admin_jobs SET state='running',total_rows=10000,lease_owner=?,lease_until=now()+interval '2 minutes' WHERE id=?",owner,rows.job());assertTrue(jobs.guardLease(rows.job(),owner));for(int row=3;row<=10001;row++)assertEquals(1,jobs.receipt(rows.job(),row));assertEquals(0,jobs.receipt(rows.job(),258));assertEquals(40,jdbc.queryForObject("SELECT count(*) FROM nw_foundation_admin_job_receipt_blocks WHERE job_id=?",Integer.class,rows.job()));for(int row:List.of(2,257,258,10001))assertTrue(jobs.hasReceipt(rows.job(),row));assertEquals(1,jobs.progress(rows.job(),owner));assertEquals(0,jobs.progress(rows.job(),owner));var current=jobs.lock(rows.job());assertEquals(10000,current.getCompletedRows());assertEquals(1,current.getErrorRows());jdbc.update("UPDATE nw_foundation_admin_jobs SET lease_until=now()-interval '1 second' WHERE id=?",rows.job());assertFalse(jobs.guardLease(rows.job(),owner));status.setRollbackOnly();});}

    /** 仅为 mapper 边界构造数据库事实，不把此替代真实账号初始化与 HTTP 认证验收。 */
    private Rows fixture(String tenant) {
        String file=UUID.randomUUID().toString(),job=UUID.randomUUID().toString(),scope=tenant==null?"platform":tenant;
        jdbc.update("INSERT INTO nw_foundation_files(id,tenant_id,created_by,name,mime_type,size_bytes,sha256,blob_key,state,source_kind) VALUES(?,?,'scope-test','scope.txt','text/plain',1,?,?,'ready','upload')",file,tenant==null?null:Long.valueOf(tenant),"a".repeat(64),file);
        jdbc.update("INSERT INTO nw_foundation_admin_jobs(id,tenant_id,member_id,handler,operation,options_json,idempotency_key,request_digest,state) VALUES(?,?,'scope-test','member_profiles','export','{}'::jsonb,'shared-smoke-key',?,'queued')",job,tenant==null?null:Long.valueOf(tenant),"a".repeat(64));
        jdbc.update("INSERT INTO nw_foundation_file_references(file_id,scope_key,owner_kind,owner_id) VALUES(?,?,'foundation.job',?)",file,scope,job);
        jdbc.update("INSERT INTO nw_foundation_admin_job_rows(job_id,scope_key,row_number,errors_json) VALUES(?,?,2,'[{\"row_number\":2,\"field\":\"name\",\"message\":\"bad\"}]'::jsonb)",job,scope);
        jdbc.update("INSERT INTO nw_foundation_admin_job_receipt_blocks(job_id,scope_key,block_number,completed_bits) VALUES(?,?,0,set_bit(decode(repeat('00',32),'hex'),0,1))",job,scope);
        return new Rows(tenant,file,job);
    }
    /** 同时模拟宿主可信线程上下文；真实 TenantRlsInterceptor 会在每条 MyBatis SQL 前重新绑定它。 */
    private void bind(String tenant) {
        if(tenant==null) TenantContext.clear(); else TenantContext.setTenantId(tenant);
        files.bind(tenant==null?"":tenant,tenant==null); jobs.bind(tenant==null?"":tenant,tenant==null);
    }
    private record Rows(String tenant,String file,String job) { }
}
