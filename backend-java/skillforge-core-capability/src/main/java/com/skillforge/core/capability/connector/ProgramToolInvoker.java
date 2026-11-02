package com.skillforge.core.capability.connector;

import com.skillforge.runtime.api.TypedPayload;

/** 真实sandbox工具RPC携带当前父Registered上下文；实现负责独立typed子Run。 */
@FunctionalInterface
public interface ProgramToolInvoker {
    Calls bind(PlatformToolExecutionContext parent);

    /** 仅属于一次真实程序调用；逐RPC仍须验证当前父调用和子派发资格。 */
    @FunctionalInterface
    interface Calls {
        TypedPayload invoke(int ordinal,String toolHandle,TypedPayload arguments);
    }

    /** 仅用于子调用派发前已确定的能力边界拒绝，不代表外调或父程序结果未知。 */
    final class AdmissionRejected extends RuntimeException {
        private final String reasonCode;

        public AdmissionRejected(String reasonCode) {
            super(requireReason(reasonCode));
            this.reasonCode = reasonCode;
        }

        public String reasonCode() { return reasonCode; }

        private static String requireReason(String reasonCode) {
            return switch (java.util.Objects.requireNonNull(reasonCode, "reasonCode")) {
                case "program_tool_not_available", "program_tool_requires_direct_call" -> reasonCode;
                default -> throw new IllegalArgumentException("program_admission_reason_invalid");
            };
        }
    }
}
