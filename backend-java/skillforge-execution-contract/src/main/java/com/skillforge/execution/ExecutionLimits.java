package com.skillforge.execution;

/** 代码、程序和 Workflow 执行传输的统一容量；权限和预算仍归调用宿主。 */
public final class ExecutionLimits {
    public static final int DOCKER_CONTROL_TIMEOUT_SECONDS = 15;
    public static final int WORKSPACE_CLEANUP_INTERVAL_MINUTES = 60;
    public static final int DEFAULT_TIMEOUT_SECONDS = 900;
    public static final int MAX_TIMEOUT_SECONDS = 3_600;
    public static final int CONTROL_RESPONSE_BYTES = 16 * 1024 * 1024;
    public static final int PUBLIC_RESULT_BYTES = 8 * 1024 * 1024;
    public static final String PUBLIC_RESULT_FILE = "result.json";
    public static final int WORKFLOW_RESULT_BYTES = 8 * 1024 * 1024;
    public static final int OUTPUT_CAPTURE_BYTES = 4 * 1024 * 1024;
    /** 单次执行接收的会话附件数量；会话入口使用相同限制。 */
    public static final int MAX_ATTACHMENT_INPUTS = 16;
    public static final int MAX_FILES = 2_048;
    public static final long TRANSFER_BYTES = 1024L * 1024 * 1024;
    public static final int DEFAULT_TRANSFER_TIMEOUT_SECONDS = 900;
    public static final int MAX_TRANSFER_TIMEOUT_SECONDS = 3_600;
    public static final int PROGRAM_CALLS = 1_024;
    public static final int PROGRAM_MESSAGE_BYTES = 64 * 1024;
    public static final int STREAM_BUFFER_BYTES = 64 * 1024;
    public static final String REQUEST_ENTRY = "request.json";
    public static final String MEMORY_LIMIT = "1g";
    public static final java.math.BigDecimal CPU_LIMIT = new java.math.BigDecimal("2");
    public static final int PIDS_LIMIT = 256;
    public static final int CONCURRENT_EXECUTIONS = 4;

    private ExecutionLimits() { }
}
