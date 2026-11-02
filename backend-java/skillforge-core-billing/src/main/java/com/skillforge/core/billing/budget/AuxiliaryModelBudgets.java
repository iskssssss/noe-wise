package com.skillforge.core.billing.budget;

import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings;

import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.*;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import java.util.Objects;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;

/** 可信前台辅助调用；分类不来自请求 metadata，缺少任务来源时使用独立维护根。 */
@Service
public final class AuxiliaryModelBudgets {
    private static final String MAINTENANCE_PREFIX = "maintenance:";
    private static final String RESULT_PREFIX = "external-result:";
    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();
    private final ExecutionBudgetPort budgets;
    private final OperationalSettingsService settings;
    public AuxiliaryModelBudgets(ExecutionBudgetPort budgets, OperationalSettingsService settings) {
        this.budgets = Objects.requireNonNull(budgets); this.settings = Objects.requireNonNull(settings);
    }
    public record Request(long inputUpperBound, long outputLimit, String fingerprint) {
        public Request {
            if (inputUpperBound < 0 || outputLimit < 0 || fingerprint == null || fingerprint.isBlank())
                throw new IllegalArgumentException("auxiliary_budget_request_invalid");
        }
        /** 内容摘要绑定实际请求；长度前缀防止不同分段拼接成相同输入。 */
        public static Request freeze(long inputUpperBound, long outputLimit, String operation,
                String modelVersion, java.util.List<String> content) {
            try {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                var values = new java.util.ArrayList<String>();
                values.add(operation); values.add(modelVersion); values.add(Long.toString(outputLimit));
                values.addAll(content);
                for (var value : values) {
                    var bytes = Objects.requireNonNull(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                    digest.update(bytes);
                }
                return new Request(inputUpperBound, outputLimit, java.util.HexFormat.of().formatHex(digest.digest()));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("auxiliary_budget_digest_unavailable", impossible);
            }
        }
    }
    public Attempt reserve(String tenant, String invocation, String attempt, Request request) {
        var binding = ExecutionBudgetScope.current().orElseGet(() -> {
            var run = new RunRef(new TenantId(tenant), MAINTENANCE_PREFIX + invocation);
            var config = settings.read(ChatBudgetSettings.GROUP);
            var limits = ChatBudgetSettings.freeze(config, Scenario.MAINTENANCE, Role.PRIMARY, AgentExecutionConfig.inherited());
            budgets.register(new Registration(run, run, Role.PRIMARY, new Policy.Enforced(limits)));
            return new ExecutionBudgetScope.Binding(budgets, run);
        });
        if (!binding.run().tenantId().value().equals(tenant)) throw new SecurityException("budget_scope_tenant_mismatch");
        // 收尾中不得通过摘要、检索等旁路消耗最低收尾保护。
        var demand = new Demand(binding.run(), attempt, request.fingerprint(), Category.AUXILIARY_MODEL,
                Resources.call(Category.AUXILIARY_MODEL, request.inputUpperBound(), request.outputLimit()), false);
        binding.port().recover(binding.run());
        var result = binding.port().reserve(demand);
        if (result instanceof Reservation.Denied denied) throw new IllegalStateException(denied.reasonCode());
        return new Attempt(binding, attempt);
    }
    /** 在用量事实成功保存后捕获；不把缓存或 reasoning 再次相加。 */
    public static void captureUsage(LlmUsage usage) {
        var attempt = CURRENT.get();
        if (attempt != null) attempt.usage = new Usage(optional(usage.promptTokens()), optional(usage.completionTokens()), optional(usage.totalTokens()));
    }
    private static OptionalLong optional(Long value) { return value == null ? OptionalLong.empty() : OptionalLong.of(value); }
    public static final class Attempt implements AutoCloseable {
        private final ExecutionBudgetScope.Binding binding;
        private final String id;
        private final Attempt previous;
        private Usage usage = Usage.unreported();
        private Attempt(ExecutionBudgetScope.Binding binding, String id) {
            this.binding = binding; this.id = id; this.previous = CURRENT.get(); CURRENT.set(this);
        }
        public boolean sent() { return binding.port().markSent(binding.run(), id); }
        /** 保存与结算分开，崩溃只恢复既有事实，不重复网络请求。 */
        public void resultSaved() {
            binding.port().saveResult(binding.run(), id, usage, RESULT_PREFIX + id);
            binding.port().settle(binding.run(), id);
        }
        public void notSent() {
            binding.port().saveResult(binding.run(), id, Usage.unreported(), RESULT_PREFIX + id);
            binding.port().settle(binding.run(), id);
        }
        @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
