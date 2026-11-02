package com.skillforge.runtime.adapter.model;

import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.util.Objects;

/** 在真实模型请求发送前登记唯一尝试，并在供应商结果确定后完成审计与计量。 */
public interface ModelInvocationAudit {

    /**
     * 在网络发送前取得本次 Runtime attempt 的唯一执行权。
     *
     * @return {@code false} 表示该 attempt 已登记，调用方不得重发
     */
    boolean start(ModelCall call, Provider provider);

    /** 供应商已返回可验证结果；usage 保留完整终态快照，计量事实以 usageReported 的 revision 归并。 */
    void succeeded(ModelCall call, Provider provider, ModelUsage usage);

    /**
     * 供应商已报告的用量快照，与调用成功状态相互独立。
     * revision 在同一 attempt 内从 1 起严格递增；同一 revision 的重放由宿主归并。
     */
    default void usageReported(ModelCall call, Provider provider, long revision, ModelUsage usage) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(provider, "provider");
        if (revision <= 0) throw new IllegalArgumentException("usage revision must be positive");
        Objects.requireNonNull(usage, "usage");
    }

    /** 供应商明确拒绝或调用前确定失败。 */
    void failed(ModelCall call, Provider provider, String reasonCode);

    /** 供应商是否收到请求或是否产生费用无法确认。 */
    void unresolved(ModelCall call, Provider provider, String reasonCode);

    /** 未装配宿主审计时仍保留 Adapter 的独立使用能力。 */
    static ModelInvocationAudit noop() {
        return Noop.INSTANCE;
    }

    /** 冻结的供应商与服务身份；不包含端点、凭据或请求载荷。 */
    record Provider(String providerId, String serviceId, String modelId) {
        public Provider {
            providerId = requireText(providerId, "providerId");
            serviceId = requireText(serviceId, "serviceId");
            modelId = requireText(modelId, "modelId");
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
            return value;
        }
    }

    enum Noop implements ModelInvocationAudit {
        INSTANCE;

        @Override public boolean start(ModelCall call, Provider provider) { return true; }
        @Override public void succeeded(ModelCall call, Provider provider, ModelUsage usage) { }
        @Override public void failed(ModelCall call, Provider provider, String reasonCode) { }
        @Override public void unresolved(ModelCall call, Provider provider, String reasonCode) { }
    }
}
