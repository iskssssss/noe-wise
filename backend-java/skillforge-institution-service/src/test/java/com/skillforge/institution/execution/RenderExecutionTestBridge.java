package com.skillforge.institution.execution;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

/** 为跨包装配测试提供渲染专用执行夹具，不扩大生产入口可见性。 */
public final class RenderExecutionTestBridge {
    private RenderExecutionTestBridge() {}

    public static void returns(CodeExecutionToolHandler execution, Map<String,Object> result) throws Exception {
        when(execution.executeForRender(any(), any())).thenReturn(result);
    }

    public static void verifyCalls(CodeExecutionToolHandler execution, int count) throws Exception {
        verify(execution, times(count)).executeForRender(any(), any());
    }
}
