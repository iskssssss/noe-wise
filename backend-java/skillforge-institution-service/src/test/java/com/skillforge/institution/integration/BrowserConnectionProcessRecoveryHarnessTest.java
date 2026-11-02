package com.skillforge.institution.integration;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 浏览器进程恢复入口复用当前typed A/B JVM场景和外层自有PG/Redis。
 * 实际生产BrowserClient、Handler、Wait/Attempt与当前授权保持；确定HTTP协议端不宣称真实扩展或bootJar验收。
 * 父进程先停止所有定时扫描与执行Worker，恢复只由B进程完成。
 */
@Tag("runtime-process-restart")
@Tag("runtime-latency")
@EnabledIfEnvironmentVariable(named="SKILLFORGE_RUNTIME_PROCESS_RESTART",matches="1")
@EnabledIfEnvironmentVariable(named="SKILLFORGE_S03_EXTERNAL_JDBC_URL",matches=".+")
class BrowserConnectionProcessRecoveryHarnessTest extends RuntimeExecutionProcessRestartE2ETest {
    /** 同一typed场景保留独立精确selector，不维护旧graph或第二套恢复实现。 */
    @Test
    @Timeout(value=10,unit=TimeUnit.MINUTES)
    void originalWaitingRunResumesOnceAfterRealInstitutionProcessRestart() throws Exception {
        realJvmRestartResumesPersistedBrowserWaitExactlyOnce();
    }
}
