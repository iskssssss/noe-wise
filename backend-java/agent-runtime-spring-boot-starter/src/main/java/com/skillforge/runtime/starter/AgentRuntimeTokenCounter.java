package com.skillforge.runtime.starter;

import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionPreparations;
import com.skillforge.runtime.adapter.model.JTokkitSessionTokenCounter;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelTool;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** 按冻结 binding 的租户读取 tokenizer profile，再交给钉扎 JTokkit 计量。 */
final class AgentRuntimeTokenCounter implements SessionTokenCounter {
    private static final int MAX_CACHED_TENANTS = 32;
    private final Function<TenantId, SessionTokenCounter> factory;
    private final LinkedHashMap<TenantId, SessionTokenCounter> tenants =
            new LinkedHashMap<>(MAX_CACHED_TENANTS, .75f, true);

    AgentRuntimeTokenCounter(JdbcSessionPreparations.Factory preparations, JdbcModelBindings bindings) {
        this(jdbcFactory(preparations, bindings));
    }

    AgentRuntimeTokenCounter(Function<TenantId, SessionTokenCounter> factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    @Override
    public Measurement measure(ResultRef profileRef, FrozenModelTarget target, List<ModelMessage> messages, List<ModelTool> tools) {
        Objects.requireNonNull(target, "target");
        TenantId tenant = target.binding().tenantId();
        // 计量及受信配置读取在锁外执行；淘汰不关闭仍被在途调用持有的计数器。
        return counter(tenant).measure(profileRef, target, messages, tools);
    }

    /** 仅保留最近使用的 32 个纯计数缓存；每次调用仍由计数器校验完整目标与配置。 */
    private synchronized SessionTokenCounter counter(TenantId tenant) {
        var current = tenants.get(tenant);
        if (current != null) return current;
        var created = Objects.requireNonNull(factory.apply(tenant), "tenant token counter");
        // 工厂失败不淘汰有效缓存；同租户首次创建由此短临界区唯一发布。
        if (tenants.size() == MAX_CACHED_TENANTS) tenants.pollFirstEntry();
        tenants.put(tenant, created);
        return created;
    }

    private static Function<TenantId, SessionTokenCounter> jdbcFactory(JdbcSessionPreparations.Factory preparations, JdbcModelBindings bindings) {
        Objects.requireNonNull(preparations);
        Objects.requireNonNull(bindings);
        return tenant -> {
            // 有界计量器只持有轻量租户绑定，MyBatis 资源由宿主工厂共用；每次仍读取受信配置。
            var profiles = preparations.tokenProfiles(tenant);
            return new JTokkitSessionTokenCounter(profiles::read, target -> {
                var binding = bindings.find(target.binding()).orElseThrow(
                        () -> new IllegalStateException("session_tokenizer_binding_rejected"));
                if (!binding.target().equals(target))
                    throw new IllegalStateException("session_tokenizer_binding_rejected");
                var profile = binding.providerProfile();
                return new OpenAiModelPort.ProviderProfile(profile.providerId(), profile.serviceId(),
                        profile.profileVersion(), ModelProviderCatalog.ModelProtocol.fromId(profile.protocol()),
                        ModelProviderCatalog.AuthenticationScheme.fromId(profile.authenticationScheme()),
                        profile.dialectVersion());
            });
        };
    }
}
