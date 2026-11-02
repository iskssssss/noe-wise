package com.skillforge.institution;

import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 显式冻结测试策略，避免单测依赖当前平台数据库。 */
public final class TestOperationalSettings {
    public static RunSettingsService runs(boolean enabled) {
        var service=mock(RunSettingsService.class);
        var frozen=new RunSettingsService.FrozenConfig(null,0,Map.of("sandbox",1L,"artifacts",1L), null, com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited(), "profile");
        when(service.captureTool(anyString(),anyString(),anyString())).thenReturn(frozen);
        when(service.read(eq(frozen),eq("artifacts"))).thenReturn(new OperationalSettingsService.Snapshot(1,
                Map.of("retention_days",7,"max_files",256,"transfer_timeout_seconds",10)));
        var values=new java.util.LinkedHashMap<String,Object>();
        values.put("enabled",enabled);values.put("execution_timeout_seconds",60);
        values.put("workspace_retention_hours",24);
        values.put("output_capture_bytes",4096);
        values.put("max_artifact_files",256);values.put("max_concurrent_executions",2);
        values.put("memory_limit","512m");values.put("cpu_limit",1);values.put("pids_limit",128);
        when(service.read(eq(frozen),eq("sandbox"))).thenReturn(new OperationalSettingsService.Snapshot(1,values));
        return service;
    }
}
