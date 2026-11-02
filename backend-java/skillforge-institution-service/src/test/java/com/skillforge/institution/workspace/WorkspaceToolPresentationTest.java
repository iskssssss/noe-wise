package com.skillforge.institution.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.chat.contract.ProcessPresentation;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 过程展示按完整控制回执和备份事实表达，不能误判缺少success的成功回执。 */
class WorkspaceToolPresentationTest {
    @Test
    void confirmedWorkspaceReceiptsKeepSuccessPendingFailureAndUnknownDistinct() throws Exception {
        var service=mock(SessionWorkspaceService.class);
        var context=mock(PlatformToolExecutionContext.class);
        var args=Map.<String,Object>of();
        var files=new WorkspaceFilesToolHandler(service);
        var checkpoints=new WorkspaceCheckpointToolHandler(service);

        when(service.files(args,context)).thenReturn(Map.of("revision",2L,"path","proof.txt"));
        assertEquals("工作区文件操作完成。",presentation(files.execute(args,context)).summary());
        when(service.files(args,context)).thenReturn(Map.of("success",false,"error","workspace_content_withheld"));
        assertEquals("工作区文件操作未完成。",presentation(files.execute(args,context)).summary());

        when(service.checkpoint(args,context)).thenReturn(Map.of("phase","backup_pending","revision",2L,"backupRevision",0L));
        assertEquals("备份请求已受理，云端备份正在处理。",presentation(checkpoints.execute(args,context)).summary());
        when(service.checkpoint(args,context)).thenReturn(Map.of("phase","backup_failed","revision",2L,"backupRevision",0L));
        assertEquals("云端备份失败，本地工作区状态以回执为准。",presentation(checkpoints.execute(args,context)).summary());
        when(service.checkpoint(args,context)).thenReturn(Map.of("success",false,"error","workspace_request_rejected"));
        assertEquals("工作区快照操作未完成。",presentation(checkpoints.execute(args,context)).summary());

        var unknown=new ExternalInvocationGuard.OutcomeUnknownException("workspace_outcome_unknown",null);
        when(service.files(args,context)).thenThrow(unknown);
        when(service.checkpoint(args,context)).thenThrow(unknown);
        assertSame(unknown,assertThrows(ExternalInvocationGuard.OutcomeUnknownException.class,()->files.execute(args,context)));
        assertSame(unknown,assertThrows(ExternalInvocationGuard.OutcomeUnknownException.class,()->checkpoints.execute(args,context)));
    }

    /** 断言只检查面向用户的过程说明，不读取或公开工作区正文。 */
    private ProcessPresentation presentation(Map<String,Object> result) {
        return assertInstanceOf(ProcessPresentation.class,result.get("public_presentation"));
    }
}
