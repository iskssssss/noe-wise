package com.skillforge.runtime.starter;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.jdbc.identity.JdbcIdentityTransactions;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.adapter.jdbc.memory.MemoryTransitionGuard;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionStorePort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionPreparations;
import com.skillforge.runtime.adapter.jdbc.session.JdbcWorkflowPolicy;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.MediaPort;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.codec.spi.PortalOutcomeCodec;
import com.skillforge.runtime.codec.spi.KnowledgeDocumentCodec;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.core.PayloadValidator;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.runtime.memory.MemorySourcePolicy;
import com.skillforge.runtime.memory.MemoryTypeRegistry;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;

/** 通用 Runtime 基础装配；身份、能力来源与外部调用端口由宿主显式提供。 */
@AutoConfiguration
@EnableConfigurationProperties(AgentRuntimeProperties.class)

public class AgentRuntimeAutoConfiguration {

    @Bean(destroyMethod = "close")
    ExecutionHostScheduler executionHostScheduler() { return new ExecutionHostScheduler(); }

    @Bean
    @ConditionalOnBean(ExecutionHostRegistry.Host.class)
    ExecutionHostRegistry executionHostRegistry(List<ExecutionHostRegistry.Host> hosts) { return new ExecutionHostRegistry(hosts); }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(ExecutionHostRegistry.Host.class)
    ExecutionHostWorker executionHostWorker(ExecutionHostRegistry hosts, ExecutionTenantDiscovery tenants, ExecutionHostScheduler scheduler,
            AgentRuntimeTenantBinder binder, AgentRuntimeProperties properties) {
        return new ExecutionHostWorker(hosts, tenants, scheduler, binder, properties.getWorkerId() + ":execution:" + java.util.UUID.randomUUID(),
                properties.getWorkerLeaseDuration(), properties.getWorkers().isAutoStartup());
    }

    /** Runtime 与宿主辅助调用共用可装饰的持久预算端口；Ledger 仍负责同事务准入。 */
    @Bean
    @ConditionalOnMissingBean(com.skillforge.runtime.api.port.ExecutionBudgetPort.class)
    com.skillforge.runtime.api.port.ExecutionBudgetPort agentRuntimeExecutionBudgets(DataSource source) {
        return new com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets(source);
    }

    @Bean
    RuntimeSettings agentRuntimeSettings(AgentRuntimeProperties properties) {
        properties.validate();
        return new RuntimeSettings(new CodecLimits(properties.getMaxPayloadCodeUnits(),
                        properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()),
                properties.getModelMaximumResponseBytes());
    }

    @Bean
    @ConditionalOnMissingBean
    Clock agentRuntimeClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    HttpClient agentRuntimeHttpClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 宿主按 schema 精确扩展；重复注册启动拒绝，未知 schema 继续由原 registry 拒绝。 */
    @Bean
    @ConditionalOnMissingBean(PayloadValidator.class)
    PayloadValidator agentRuntimePayloadValidator(List<AgentRuntimePayloadSchema> hostSchemas) {
        var defaults = new SessionRuntimePayloads();
        if (hostSchemas.isEmpty()) return defaults;
        Map<String, AgentRuntimePayloadSchema> schemas = new LinkedHashMap<>();
        for (var host : hostSchemas) {
            for (String schema : host.schemaIds()) {
                if (schema == null || schema.isBlank() || schemas.putIfAbsent(schema, host) != null)
                    throw new IllegalArgumentException("runtime_host_payload_schema_conflict");
            }
        }
        var registered = Map.copyOf(schemas);
        return (payload, limits) -> {
            var host = payload == null ? null : registered.get(payload.schemaId());
            if (host == null) {
                defaults.validate(payload, limits);
                return;
            }
            // 仅复用通用 JSON 解析限额；宿主收到的仍是带原 schema 的不可变载荷。
            defaults.validate(new TypedPayload("application/json", payload.canonicalJson()), limits);
            host.validate(payload);
        };
    }

    @Bean
    CoreRuntime agentRuntimeCore(RuntimeSettings settings, PayloadValidator payloads) {
        return new CoreRuntime(settings.codecLimits(), payloads);
    }

    /** 管理员只读诊断由 Session 拥有严格 typed 解码，宿主只注入本入口并执行读取授权与审计。 */
    @Bean
    com.skillforge.runtime.session.ExecutionModelAttemptInspector agentRuntimeModelAttemptInspector(RuntimeSettings settings) {
        return new com.skillforge.runtime.session.ExecutionModelAttemptInspector(settings.codecLimits());
    }

    @Bean
    PortalOutcomeCodec agentRuntimePortalOutcomeCodec(
            CoreRuntime core, PayloadValidator payloads) {
        return core.portalResults(payloads, payloads, payloads);
    }

    /** 文档访问原有容量上限独立于模型载荷限额，元数据和原文预算复用唯一规范实现。 */
    @Bean
    KnowledgeDocumentCodec agentRuntimeKnowledgeDocumentCodec() {
        return CoreRuntime.knowledgeDocuments(new CodecLimits(1_048_576, 10_000, 16));
    }

    @Bean
    AgentRuntimePayloadValidation agentRuntimePayloadValidation(
            PayloadValidator payloads, RuntimeSettings settings) {
        return new AgentRuntimePayloadValidation() {
          @Override public boolean accepts(com.skillforge.runtime.api.TypedPayload payload) {
            try {
                payloads.validate(payload, settings.codecLimits());
                return true;
            } catch (RuntimeException rejected) {
                return false;
            }
          }
          @Override public boolean accepts(com.skillforge.runtime.api.TypedPayload schema,
                  com.skillforge.runtime.api.TypedPayload payload) {
            try {
                if(!accepts(schema)||!accepts(payload))return false;
                com.skillforge.runtime.session.SessionRuntimePayloads.validateAgainstSchema(schema,
                        new com.skillforge.runtime.api.TypedPayload("application/json",payload.canonicalJson()),settings.codecLimits());
                return true;
            } catch(RuntimeException rejected) {return false;}
          }
        };
    }

    @Bean
    JdbcSessionLogPort agentRuntimeSessionLog(DataSource dataSource) {
        return new JdbcSessionLogPort(dataSource);
    }

    /** 产品关闭会话仍使用原 owner root 与状态CAS，不引入Session执行或受理调度。 */
    @Bean
    @ConditionalOnMissingBean(com.skillforge.runtime.api.port.SessionStorePort.class)
    JdbcSessionStorePort agentRuntimeSessionStore(DataSource dataSource) {
        return new JdbcSessionStorePort(dataSource);
    }

    /** 维护入口不自设业务TTL；宿主按明确保留政策调用，孤儿正文始终先核验完整引用。 */
    @Bean
    com.skillforge.runtime.adapter.jdbc.session.JdbcSessionStorageMaintenance agentRuntimeSessionStorageMaintenance(
            DataSource dataSource, AgentRuntimeProperties properties) {
        return com.skillforge.runtime.adapter.jdbc.session.JdbcSessionStorageMaintenance.withRetention(dataSource, properties.getHistoryRetention());
    }

    @Bean
    @ConditionalOnMissingBean(ModelProviderCatalog.class)
    ModelProviderCatalog agentRuntimeModelProviderCatalog() {
        return ModelProviderCatalog.standard();
    }

    @Bean
    @ConditionalOnMissingBean(ModelProviderRuntime.class)
    ModelProviderRuntime agentRuntimeModelProviderRuntime(
            HttpClient http, Clock clock, RuntimeSettings settings) {
        return ModelProviderRuntime.runtime(
                http, clock, settings.modelMaximumResponseBytes());
    }

    @Bean
    JdbcSessionPreparations.Factory agentRuntimeSessionPreparationFactory(DataSource dataSource) {
        return new JdbcSessionPreparations.Factory(dataSource);
    }

    @Bean
    JdbcModelBindings agentRuntimeModelBindings(DataSource dataSource,
            ModelProviderCatalog providers) {
        return new JdbcModelBindings(dataSource, (configuration, requestedProfile) -> {
            var profile = configuration.providerProfile();
            var resolved = providers.resolve(new ModelProviderCatalog.Selection(
                    profile.providerId(), profile.serviceId(), profile.profileVersion(),
                    configuration.modelId(), ModelProviderCatalog.ModelProtocol.fromId(
                            profile.protocol()),
                    ModelProviderCatalog.AuthenticationScheme.fromId(
                            profile.authenticationScheme()), configuration.endpoint()));
            if (!resolved.dialectVersion().equals(profile.dialectVersion())) {
                throw new IllegalArgumentException("model_dialect_version_mismatch");
            }
            providers.validateCapabilities(resolved,
                    requestedProfile.responseFormats(), requestedProfile.streaming(),
                    requestedProfile.reasoning());
            providers.validatePrefixCapabilities(resolved, requestedProfile.prefixCapabilities());
        });
    }

    @Bean
    AgentRuntimeModelConfiguration agentRuntimeModelConfiguration(JdbcSessionPreparations.Factory preparations,
            JdbcModelBindings bindings) {
        var tokens = new AgentRuntimeModelConfiguration.TokenProfiles() {
            @Override public SessionTokenCounter.Profile register(
                    TenantId tenant, SessionTokenCounter.Profile profile) {
                return preparations.tokenProfiles(tenant).register(profile);
            }
            @Override public java.util.Optional<SessionTokenCounter.Profile> read(
                    TenantId tenant, com.skillforge.runtime.api.ResultRef reference) {
                return preparations.tokenProfiles(tenant).read(reference);
            }
        };
        return new AgentRuntimeModelConfiguration(bindings::publish, bindings::find, tokens,
                new AgentRuntimeModelConfiguration.PreparationProfiles() {
                    @Override public com.skillforge.runtime.session.spi.SessionPreparations.Profile register(
                            TenantId tenant, com.skillforge.runtime.session.spi.SessionPreparations.Profile profile) {
                        return preparations.forTenant(tenant).registerProfile(profile);
                    }
                    @Override public java.util.Optional<com.skillforge.runtime.session.spi.SessionPreparations.Profile> read(
                            TenantId tenant, com.skillforge.runtime.api.ResultRef reference) {
                        return preparations.forTenant(tenant).profile(reference);
                    }
                });
    }

    @Bean
    OpenAiModelPort agentRuntimeModelPort(JdbcModelBindings bindings, AgentRuntimeDecrypt decrypt,
            RuntimeSettings settings, HttpClient http, Clock clock,
            PayloadValidator payloads,
            ModelProviderRuntime providers,
            ObjectProvider<ModelInvocationAudit> invocationAudits,
            MediaPort media) {
        var guards = new AgentRuntimePayloads(payloads, settings.codecLimits());
        return new OpenAiModelPort(reference -> bindings.find(reference).map(value ->
                new OpenAiModelPort.Binding(value.target(), value.strategy(), value.endpoint(),
                        value.encryptedCredential(), new OpenAiModelPort.ProviderProfile(
                                value.providerProfile().providerId(),
                                value.providerProfile().serviceId(),
                                value.providerProfile().profileVersion(),
                                ModelProviderCatalog.ModelProtocol.fromId(
                                        value.providerProfile().protocol()),
                                ModelProviderCatalog.AuthenticationScheme.fromId(
                                        value.providerProfile().authenticationScheme()),
                                value.providerProfile().dialectVersion()))),
                decrypt::decrypt, call -> false, guards.modelOutputs(), http, clock,
                settings.modelMaximumResponseBytes(), providers,
                invocationAudits.getIfAvailable(ModelInvocationAudit::noop), media);
    }

    @Bean
    JdbcExecutionLedgerPort agentRuntimeExecutionLedger(DataSource dataSource) {
        return new JdbcExecutionLedgerPort(dataSource);
    }

    @Bean
    JdbcWorkflowPolicy agentRuntimeWorkflowPolicy(DataSource dataSource,
            JdbcIdentityTransactions identities) {
        return new JdbcWorkflowPolicy(dataSource, identities);
    }

    @Bean
    SessionTokenCounter agentRuntimeTokenCounter(JdbcSessionPreparations.Factory preparations, JdbcModelBindings bindings) {
        return new AgentRuntimeTokenCounter(preparations, bindings);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agent-runtime.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
    JdbcMemoryPort agentRuntimeMemoryPort(DataSource dataSource, MemoryTransitionGuard guard,
            AgentRuntimeMemorySearchBindings search) {
        return new JdbcMemoryPort(dataSource,guard,
                search.projector(),search.indexingPolicy(),search.embedding());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agent-runtime.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
    MemoryRuntime agentRuntimeMemory(JdbcMemoryPort store, SessionLogPort log,
            MemoryAccessPolicy access, MemoryTypeRegistry types, MemorySourcePolicy sources,
            MemoryRuntime.CurrentFactSource currentFacts, Clock clock) {
        return new MemoryRuntime(store, log, access, types, sources, currentFacts, clock);
    }

    record RuntimeSettings(CodecLimits codecLimits, int modelMaximumResponseBytes) { }
}
