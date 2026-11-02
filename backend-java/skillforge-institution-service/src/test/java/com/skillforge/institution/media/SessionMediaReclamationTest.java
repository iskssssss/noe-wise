package com.skillforge.institution.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.attachment.SessionAttachmentBodyMapper;
import com.skillforge.institution.attachment.SessionAttachmentBodyStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 资源门禁的媒体闭包与锁序；真实owner/受限PG竞争另走S03新库入口，不以Mock证明数据库安全。 */
class SessionMediaReclamationTest {
    private final SessionMediaMapper mapper=mock(SessionMediaMapper.class);
    private final SessionAttachmentBodyStore bodies=mock(SessionAttachmentBodyStore.class);
    private final SessionAttachmentBodyMapper bodyMapper=mock(SessionAttachmentBodyMapper.class);
    private final SessionMediaReclamation reclamation=new SessionMediaReclamation(mapper,bodies,bodyMapper);
    private final SessionMediaMapper.UnusedUpload candidate=new SessionMediaMapper.UnusedUpload("root","product","runtime");

    /** 只绑定当前测试可信租户，外层身份与角色在真实PG入口中验证。 */
    @BeforeEach void tenant(){TenantContext.setTenantId("7");}
    /** 每例结束恢复进程线程上下文。 */
    @AfterEach void clear(){TenantContext.clear();}

    /** 无终态事实或Runtime真实引用时不触碰tenant/media/body。 */
    @Test void ownerGateDenialStopsBeforeMediaLocks(){
        assertEquals("runtime_protected",reclamation.retire("7",candidate).result());
        verify(mapper).unusedMediaOwnerGate("7","product","runtime","root");
        verifyNoInteractions(bodies,bodyMapper);
        verify(mapper,never()).lockUnusedUploadClosure(anyString(),anyString(),anyString(),anyInt());
    }

    /** 精确未用原图与规范化后代释放；共享正文最终是否可删仍由正式GC核所有metadata。 */
    @Test void exactUnusedClosureUsesOwnerThenTenantThenMetadataAndPreservesOriginalIds(){
        allowed(List.of(row("root",null,"source-v1",null,null),row("child","root","normalize-v1",null,null)));
        when(mapper.releaseUnusedUploadClosure("7","product","runtime","root",List.of("root","child"))).thenReturn(2);
        when(bodyMapper.reclaimUnreferenced("7",100)).thenReturn(1);
        assertEquals(new SessionMediaReclamation.Retirement(2,1,"retired"),reclamation.retire("7",candidate));
        var ordered=inOrder(mapper,bodies,bodyMapper);
        ordered.verify(mapper).unusedMediaOwnerGate("7","product","runtime","root");
        ordered.verify(bodies).lockTenant("7");
        ordered.verify(mapper).lockUnusedUploadClosure("7","runtime","root",101);
        ordered.verify(mapper).releaseUnusedUploadClosure("7","product","runtime","root",List.of("root","child"));
        ordered.verify(bodyMapper).reclaimUnreferenced("7",100);
    }

    /** pin/Run/未知变换/跨产品来源分别保护，不能只检查root自身。 */
    @Test void everyProtectedDescendantStopsWholeClosure(){
        for(var protectedChild:List.of(row("child","root","normalize-v1",null,Instant.EPOCH),
                row("child","root","normalize-v1","run",null),row("child","root","crop-v1:0:0:1:1",null,null),
                new SessionMediaMapper.UnusedMediaRow("child","foreign","runtime",null,"root","normalize-v1","bytea.v1",null))){
            allowed(List.of(row("root",null,"source-v1",null,null),protectedChild));
            assertEquals("media_protected",reclamation.retire("7",candidate).result());
        }
        verify(mapper,never()).releaseUnusedUploadClosure(anyString(),anyString(),anyString(),anyString(),anyList());
        verifyNoInteractions(bodyMapper);
    }

    /** 元数据竞争或SQL重查拒绝不能降格成局部成功，外层事务须回滚全部引用解除。 */
    @Test void partialReleaseThrowsAndNeverCollectsBody(){
        allowed(List.of(row("root",null,"source-v1",null,null),row("child","root","normalize-v1",null,null)));
        when(mapper.releaseUnusedUploadClosure("7","product","runtime","root",List.of("root","child"))).thenReturn(1);
        assertThrows(IllegalStateException.class,()->reclamation.retire("7",candidate));
        verifyNoInteractions(bodyMapper);
    }

    /** 调度不能从未绑定/其它租户上下文借用候选扩大资源范围。 */
    @Test void wrongTenantIsRejectedBeforeAnyQuery(){
        TenantContext.setTenantId("17");assertThrows(SecurityException.class,()->reclamation.retire("7",candidate));
        verifyNoInteractions(mapper,bodies,bodyMapper);
    }

    /** 当前闭包夹具只构造媒体保护字段，不伪造真实owner门禁通过的数据库事实。 */
    private void allowed(List<SessionMediaMapper.UnusedMediaRow> closure){
        when(mapper.unusedMediaOwnerGate("7","product","runtime","root")).thenReturn(true);
        when(mapper.lockUnusedUploadClosure("7","runtime","root",101)).thenReturn(closure);
    }
    /** 保留准确owner/source/transform身份，便于分别构造每种保护。 */
    private static SessionMediaMapper.UnusedMediaRow row(String id,String source,String transform,String run,Instant referenced){
        return new SessionMediaMapper.UnusedMediaRow(id,"product","runtime",run,source,transform,"bytea.v1",referenced);
    }
}
