package com.skillforge.core.foundation.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.assets.FoundationFileContracts;
import com.skillforge.core.foundation.assets.FoundationFileService;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 任务受理与工作者授权/租约拒绝的回归；不把 Mapper 替身当作真实数据库竞争证据。 */
class FoundationJobServiceTest {
    private FoundationJobMapper mapper;
    private FoundationAccess access;
    private FoundationFileService files;
    private CsvTransferHandler handler;
    private FoundationJobService jobs;
    private final String fileId=UUID.randomUUID().toString();
    /** 固定合法主体与材料，每例不依赖其他用例状态。 */
    @BeforeEach void setup(){mapper=mock(FoundationJobMapper.class);access=mock(FoundationAccess.class);files=mock(FoundationFileService.class);handler=mock(CsvTransferHandler.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true,null,"1"));when(access.withSubject(any(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(1)).get());when(handler.code()).thenReturn("example");when(handler.title()).thenReturn("示例");when(handler.columns()).thenReturn(List.of("code","label"));when(handler.importPermission()).thenReturn("foundation.example.manage");when(handler.exportPermission()).thenReturn("foundation.example.read");when(handler.validateRow(anyMap(),anyInt(),anyList())).thenReturn(List.of());var metadata=new FoundationFileContracts.FileView(fileId,"input.csv","text/csv",20,"digest",0,OffsetDateTime.now());when(files.download(fileId)).thenReturn(new FoundationFileContracts.Download(metadata,"code,label\r\na,测试\r\n".getBytes(StandardCharsets.UTF_8)));var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());jobs=new FoundationJobService(mapper,access,files,mock(FeatureGate.class),mock(ResourceQuota.class),new ObjectMapper(),manager,List.of(handler));}
    /** 作用域恢复用例不向后续测试留下线程租户。 */
    @AfterEach void clearTenant(){TenantContext.clear();}
    @Test void preflightCollectsRowErrorsWithoutWrites(){when(handler.validateRow(anyMap(),eq(2),anyList())).thenReturn(List.of(new FoundationJobContracts.RowError(2,"code","编码重复")));var checked=jobs.preflight(new FoundationJobContracts.Preflight("example",fileId,Map.of()));assertFalse(checked.valid());assertEquals(1,checked.rowCount());assertEquals("code",checked.errors().getFirst().field());verify(handler,never()).importRow(anyMap(),anyString(),anyInt(),anyList());verify(mapper,never()).insert(any());}
    @Test void invalidPreflightCannotBecomeQueuedJob(){when(handler.validateRow(anyMap(),anyInt(),anyList())).thenReturn(List.of(new FoundationJobContracts.RowError(2,"label","名称缺失")));assertEquals("import_preflight_failed",assertThrows(ApiException.class,()->jobs.submit(new FoundationJobContracts.Submit("example","import",fileId,Map.of(),"same"))).getCode());verify(mapper,never()).insert(any());}
    @Test void sameKeyWithDifferentRequestDoesNotOverwriteExistingJob(){var old=job();old.setRequestDigest("different");when(mapper.byKey("same")).thenReturn(old);assertEquals("job_idempotency_conflict",assertThrows(ApiException.class,()->jobs.submit(new FoundationJobContracts.Submit("example","export",null,Map.of(),"same"))).getCode());verify(mapper,never()).insert(any());}
    @Test void expiredLeaseCannotDispatchAnyImportRow(){var row=job();when(mapper.claim(anyString())).thenAnswer(i->{row.setLeaseOwner(i.getArgument(0));return row;});when(mapper.lock(row.getId())).thenReturn(row);when(mapper.guardLease(eq(row.getId()),anyString())).thenReturn(false);assertTrue(jobs.runNext(true));verify(handler,never()).importRow(anyMap(),anyString(),anyInt(),anyList());verify(mapper,never()).receipt(anyString(),anyInt());}
    @Test void revokedDomainPermissionFailsBeforeSideEffect(){var row=job();when(mapper.claim(anyString())).thenAnswer(i->{row.setLeaseOwner(i.getArgument(0));return row;});doThrow(new ApiException(HttpStatus.FORBIDDEN,"撤权")).when(access).require("foundation.example.manage");assertTrue(jobs.runNext(true));verify(handler,never()).importRow(anyMap(),anyString(),anyInt(),anyList());verify(mapper).finish(eq(row.getId()),anyString(),eq("failed"),eq("撤权"),isNull(),eq(1));}
    @Test void committedRowReceiptSkipsReplay(){var row=workingJob();when(mapper.hasReceipt(row.getId(),2)).thenReturn(true);assertTrue(jobs.runNext(true));verify(handler,never()).importRow(anyMap(),anyString(),anyInt(),anyList());verify(mapper,never()).receipt(anyString(),anyInt());verify(mapper).finish(eq(row.getId()),anyString(),eq("succeeded"),isNull(),isNull(),eq(1));}
    /** 成功恢复身份存在，但不新增空错误正文；行效果和位图仍由同一事务负责。 */
    @Test void successfulRowStoresOnlyCompactIdentity(){var row=workingJob();when(mapper.receipt(row.getId(),2)).thenReturn(1);assertTrue(jobs.runNext(true));verify(handler).importRow(anyMap(),eq(row.getId()),eq(2),anyList());verify(mapper).receipt(row.getId(),2);verify(mapper,never()).rowError(anyString(),anyInt(),anyString());}
    /** 位图身份未写入时不能宣告成功，以事务失败保持领域效果可回滚。 */
    @Test void missingReceiptCannotReportSuccess(){var row=workingJob();when(mapper.receipt(row.getId(),2)).thenReturn(0);assertTrue(jobs.runNext(true));verify(mapper).finish(eq(row.getId()),anyString(),eq("failed"),anyString(),isNull(),eq(1));verify(mapper,never()).finish(eq(row.getId()),anyString(),eq("succeeded"),any(),any(),anyInt());}
    /** 恢复位图和逐行错误共同存在，失败定位不因成功回执压缩而丢失。 */
    @Test void invalidRowPreservesErrorLocation(){var row=workingJob();when(handler.validateRow(anyMap(),eq(2),anyList())).thenReturn(List.of(new FoundationJobContracts.RowError(2,"label","名称无效")));when(mapper.receipt(row.getId(),2)).thenReturn(1);when(mapper.rowError(eq(row.getId()),eq(2),anyString())).thenReturn(1);when(mapper.progress(eq(row.getId()),anyString())).thenAnswer(i->{row.setErrorRows(1);row.setCompletedRows(1);return 1;});when(mapper.errors(eq(row.getId()),anyLong(),anyInt())).thenReturn(List.of("[{\"row_number\":2,\"field\":\"label\",\"message\":\"名称无效\"}]"));when(files.createResult(anyString(),any())).thenReturn(new FoundationFileContracts.FileView(fileId,"errors.csv","text/csv",20,"digest",0,OffsetDateTime.now()));assertTrue(jobs.runNext(true));verify(handler,never()).importRow(anyMap(),anyString(),anyInt(),anyList());verify(mapper).rowError(eq(row.getId()),eq(2),contains("名称无效"));verify(mapper).finish(eq(row.getId()),anyString(),eq("failed"),contains("部分行"),eq(fileId),eq(1));}
    /** 已撤权主体无法重新绑定认证身份，但失败收尾仍须从持久任务恢复RLS租户后还原调用线程。 */
    @Test void failedTenantJobRestoresPersistedRlsScope(){var row=job();row.setTenantId(7L);when(mapper.claim(anyString())).thenAnswer(i->{row.setLeaseOwner(i.getArgument(0));return row;});doThrow(new ApiException(HttpStatus.FORBIDDEN,"撤权")).when(access).require("foundation.example.manage");when(mapper.finish(eq(row.getId()),anyString(),eq("failed"),anyString(),isNull(),anyInt())).thenAnswer(i->{assertEquals("7",TenantContext.getTenantId());return 1;});TenantContext.setTenantId("8");assertTrue(jobs.runNext(false));assertEquals("8",TenantContext.getTenantId());verify(mapper).finish(eq(row.getId()),anyString(),eq("failed"),anyString(),isNull(),eq(1));}
    /** 数据库到期退役后才释放文件与回执；同事务失败可重试，调用线程作用域必须还原。 */
    @Test void expiredTenantMaterialsReleaseUnderPersistedScope(){var row=job();row.setTenantId(7L);row.setState("failed");row.setExpiresAt(OffsetDateTime.now().minusDays(1));when(mapper.expired(100)).thenReturn(List.of(row));when(mapper.lock(row.getId())).thenReturn(row);when(mapper.expire(row.getId())).thenAnswer(i->{assertEquals("7",TenantContext.getTenantId());return 1;});TenantContext.setTenantId("8");jobs.cleanup(false);assertEquals("8",TenantContext.getTenantId());var order=inOrder(mapper,files);order.verify(mapper).expire(row.getId());order.verify(files).releaseExpiredJobReferences(row.getId(),7L);order.verify(mapper).deleteReceipts(row.getId());order.verify(mapper).deleteReceiptBlocks(row.getId());}
    /** 使用合法租约的通用工作者夹具；Mapper替身不证明实际提交原子性。 */
    private FoundationJobDO workingJob(){var row=job();when(mapper.claim(anyString())).thenAnswer(i->{row.setLeaseOwner(i.getArgument(0));return row;});when(mapper.lock(row.getId())).thenReturn(row);when(mapper.guardLease(eq(row.getId()),anyString())).thenReturn(true);when(mapper.finish(anyString(),anyString(),anyString(),nullable(String.class),nullable(String.class),anyInt())).thenReturn(1);return row;}
    /** 保存主体和材料，不从测试运行线程 JWT 取身份。 */
    private FoundationJobDO job(){var row=new FoundationJobDO();row.setId(UUID.randomUUID().toString());row.setMemberId("1");row.setHandler("example");row.setOperation("import");row.setInputFileId(fileId);row.setOptionsJson("{}");row.setState("running");row.setTotalRows(1);return row;}
}
