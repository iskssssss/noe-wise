package com.skillforge.runtime.adapter.jdbc.model;

import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ModelPort.*;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 从原宿主配置发布不可变模型绑定；解密与网络调用均不属于本组件。 */
public final class JdbcModelBindings {
    static final int CURRENT_BINDING_FORMAT = 4;
    private final SqlSessionFactory sessions;
    private final ProviderProfileValidator providerProfiles;

    /**
     * 构造只读绑定存储；调用 {@link #publish} 会失败关闭。
     * 需要发布能力的宿主必须显式提供 SDK ProviderProfile 校验器。
     */
    public JdbcModelBindings(DataSource dataSource) {
        this(dataSource, (configuration, profile) -> {
            throw new IllegalArgumentException("model_provider_validation_required");
        });
    }

    /** providerProfiles 由 SDK 装配层提供，在任何不可变绑定写入前核对目录组合。 */
    public JdbcModelBindings(DataSource dataSource, ProviderProfileValidator providerProfiles) {
        var configuration = new Configuration(new Environment("model-bindings",
                new JdbcTransactionFactory(), Objects.requireNonNull(dataSource)));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/AgentRuntimeModelBindingMapper.xml";
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("model_binding_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception failure) { throw new IllegalStateException("model_binding_initialization_failure", failure); }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
        this.providerProfiles = Objects.requireNonNull(providerProfiles);
    }

    /** 仅受信宿主配置发布入口持有；来源已由宿主校验，SDK 不查询宿主业务表。 */
    public Binding publish(TenantId tenant, String bindingId, Optional<AgentId> agentId, Profile profile, Source supplied) {
        Objects.requireNonNull(tenant); Objects.requireNonNull(agentId); Objects.requireNonNull(profile);
        new ModelBindingRef(tenant, bindingId, 1);
        return transaction(tenant, mapper -> {
            mapper.lockBinding(tenant.value(), bindingId);
            ModelSourceRow source = ModelSourceRow.from(Objects.requireNonNull(supplied));
            if (source.encryptedCredential == null || source.encryptedCredential.isBlank())
                throw new IllegalArgumentException("model_credential_missing");
            validateEndpoint(source.endpoint);
            providerProfiles.validate(source.providerConfiguration(), profile);
            var candidate = ModelBindingRow.from(tenant, bindingId, agentId, source, profile);
            var previous = mapper.latest(bindingId);
            // 历史格式只提供 revision 序列，不解码或复用其冻结目标；新格式必须重新发布。
            if (previous != null && previous.bindingFormatVersion == CURRENT_BINDING_FORMAT
                    && previous.contentDigest.equals(candidate.contentDigest)) return previous.restore();
            candidate.revision = previous == null ? 1 : Math.incrementExact(previous.revision);
            if (mapper.insert(candidate) != 1) throw new IllegalStateException("model_binding_insert_failed");
            return candidate.restore();
        });
    }

    /** 永远按完整三元引用读取，不回退当前配置或最近版本。 */
    public Optional<Binding> find(ModelBindingRef binding) {
        Objects.requireNonNull(binding);
        return transaction(binding.tenantId(), mapper -> Optional.ofNullable(
                mapper.find(binding.bindingId(), binding.revision())).map(ModelBindingRow::restore));
    }

    /**
     * 同一事务读取原绑定全值并核对实际 Agent，避免归属检查后再次读取同一发布版本。
     * Agent 定向绑定只允许该 Agent；agentId 为空的租户绑定允许该租户的实际 Agent。
     * 原行仍完整校验摘要及三元引用；缺失或归属不符返回空，不代替当前 IAM、Run 或调用许可检查。
     */
    public Optional<Binding> findForOwner(ModelBindingRef binding, AgentId actualAgentId) {
        Objects.requireNonNull(binding); Objects.requireNonNull(actualAgentId);
        return transaction(binding.tenantId(), mapper -> {
            var row = mapper.find(binding.bindingId(), binding.revision());
            if (row == null) return Optional.empty();
            var restored = row.restore();
            if (!restored.target().binding().equals(binding)) throw new IllegalStateException("model_binding_snapshot_mismatch");
            return row.agentId == null || row.agentId.equals(actualAgentId.value())
                    ? Optional.of(restored) : Optional.empty();
        });
    }

    /** 节点受理来源验证复用Store事务，读取原精确绑定并核实际Agent。 */
    public static Optional<Binding> findBorrowed(org.apache.ibatis.session.SqlSession session, ModelBindingRef binding, AgentId agent) {
        var row = session.getMapper(ModelBindingMapper.class).find(binding.bindingId(), binding.revision());
        if (row == null) return Optional.empty();
        var actual = row.restore();
        if (!actual.target().binding().equals(binding)) throw new IllegalStateException("model_binding_snapshot_mismatch");
        return row.agentId == null || row.agentId.equals(agent.value()) ? Optional.of(actual) : Optional.empty();
    }

    public static void configure(Configuration configuration) {
        if (configuration.hasMapper(ModelBindingMapper.class)) return;
        String resource = "mapper/AgentRuntimeModelBindingMapper.xml";
        try (InputStream xml = JdbcModelBindings.class.getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("model_binding_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (java.io.IOException failure) { throw new IllegalStateException("model_binding_initialization_failure", failure); }
    }

    /** 受信宿主冻结的协议能力与策略；reasoning 能力由 Provider profile 校验。 */
    public record Profile(RequestedModelStrategy strategy, Set<ResponseFormatKind> responseFormats,
                          boolean streaming, boolean reasoning, Duration timeout,
                          ModelImagePolicy imagePolicy, PromptPrefixCapabilities prefixCapabilities) {
        public Profile(RequestedModelStrategy strategy, Set<ResponseFormatKind> responseFormats,
                boolean streaming, boolean reasoning, Duration timeout) {
            this(strategy, responseFormats, streaming, reasoning, timeout,
                    ModelImagePolicy.textOnly(), PromptPrefixCapabilities.unverified());
        }
        /** 普通调用显式冻结未知前缀能力，不能恢复时查询目录补值。 */
        public Profile(RequestedModelStrategy strategy, Set<ResponseFormatKind> responseFormats,
                boolean streaming, boolean reasoning, Duration timeout, ModelImagePolicy imagePolicy) {
            this(strategy, responseFormats, streaming, reasoning, timeout, imagePolicy,
                    PromptPrefixCapabilities.unverified());
        }
        /** 纯文本调用携带已解析的服务前缀能力。 */
        public Profile(RequestedModelStrategy strategy, Set<ResponseFormatKind> responseFormats,
                boolean streaming, boolean reasoning, Duration timeout, PromptPrefixCapabilities prefixCapabilities) {
            this(strategy, responseFormats, streaming, reasoning, timeout, ModelImagePolicy.textOnly(), prefixCapabilities);
        }
        public Profile {
            Objects.requireNonNull(strategy); responseFormats = Set.copyOf(responseFormats);
            new ModelCapabilitySnapshot(responseFormats, streaming, reasoning, new ResultRef("profile-validation"), imagePolicy, prefixCapabilities);
            Objects.requireNonNull(timeout);
            if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("model_profile_unsupported");
        }
    }

    /** 仅 transport 可读取凭据密文；默认字符串展示不包含 endpoint 或密文。 */
    public record Binding(FrozenModelTarget target, RequestedModelStrategy strategy,
                          String endpoint, String encryptedCredential,
                          ProviderProfile providerProfile, String sourceReference) {
        public Binding {
            Objects.requireNonNull(target); Objects.requireNonNull(strategy);
            validateEndpoint(endpoint);
            if (encryptedCredential == null || encryptedCredential.isBlank()) throw new IllegalArgumentException("model_credential_missing");
            Objects.requireNonNull(providerProfile);
            sourceReference = requireText(sourceReference, "source_reference");
        }

        @Override public String toString() { return "ModelBinding[revision=" + target.binding().revision() + "]"; }
    }

    /** JDBC 私有快照中的 SDK 供应商事实；Runtime Core/API 不接触这些字段。 */
    public record ProviderProfile(String providerId, String serviceId,
            String profileVersion, String protocol,
            String authenticationScheme, String dialectVersion) {
        public ProviderProfile {
            requireText(providerId, "provider_id");
            requireText(serviceId, "service_id");
            requireText(profileVersion, "profile_version");
            requireText(protocol, "protocol");
            requireText(authenticationScheme, "authentication_scheme");
            requireText(dialectVersion, "dialect_version");
        }
    }

    /** 待发布来源的完整 SDK 供应商选择；验证失败时不得插入绑定版本。 */
    public record ProviderConfiguration(String modelId, String endpoint,
            ProviderProfile providerProfile) {
        public ProviderConfiguration {
            requireText(modelId, "model_id");
            validateEndpoint(endpoint);
            Objects.requireNonNull(providerProfile);
        }
    }

    /** 宿主授权并冻结的配置来源；来源版本参与绑定摘要，默认展示不含连接或密文。 */
    public record Source(String reference, ProviderConfiguration configuration, String encryptedCredential) {
        public Source {
            requireText(reference, "source_reference"); Objects.requireNonNull(configuration);
            requireText(encryptedCredential, "credential");
        }
        @Override public String toString() { return "ModelSource[reference=" + reference + "]"; }
    }

    @FunctionalInterface
    public interface ProviderProfileValidator {
        void validate(ProviderConfiguration configuration, Profile requestedProfile);
    }

    private <T> T transaction(TenantId tenant, Function<ModelBindingMapper, T> work) {
        try (var session = sessions.openSession(false)) {
            try {
                var mapper = session.getMapper(ModelBindingMapper.class); mapper.bindTenant(tenant.value());
                T result = work.apply(mapper); session.commit(); return result;
            } catch (RuntimeException failure) { session.rollback(); throw failure; }
        } catch (IllegalArgumentException invalid) { throw invalid; }
        catch (RuntimeException failure) { throw new IllegalStateException("model_binding_storage_failure"); }
    }

    private static void validateEndpoint(String endpoint) {
        try {
            var uri = URI.create(endpoint);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("model_endpoint_invalid"); }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("model_" + name + "_missing");
        }
        return value;
    }

    static String digest(List<String> fields) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                var encoded = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(field));
                byte[] value = new byte[encoded.remaining()]; encoded.get(value);
                hash.update(ByteBuffer.allocate(4).putInt(value.length).array()); hash.update(value);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("model_binding_unicode_invalid"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("model_digest_unavailable"); }
    }
}

interface ModelBindingMapper {
    String bindTenant(@Param("tenant") String tenant);
    String lockBinding(@Param("tenant") String tenant, @Param("binding") String binding);
    ModelBindingRow latest(@Param("binding") String binding);
    ModelBindingRow find(@Param("binding") String binding, @Param("revision") long revision);
    int insert(ModelBindingRow row);
}

final class ModelSourceRow {
    public String sourceKind, sourceId, endpoint, modelId, encryptedCredential,
            providerId, serviceId, profileVersion, protocol, authenticationScheme,
            dialectVersion;
    static ModelSourceRow from(JdbcModelBindings.Source source) {
        var row = new ModelSourceRow();
        var c = source.configuration(); var p = c.providerProfile();
        row.sourceKind = "HOST"; row.sourceId = source.reference();
        row.endpoint = c.endpoint(); row.modelId = c.modelId(); row.encryptedCredential = source.encryptedCredential();
        row.providerId = p.providerId(); row.serviceId = p.serviceId(); row.profileVersion = p.profileVersion();
        row.protocol = p.protocol(); row.authenticationScheme = p.authenticationScheme(); row.dialectVersion = p.dialectVersion();
        return row;
    }

    JdbcModelBindings.ProviderConfiguration providerConfiguration() {
        return new JdbcModelBindings.ProviderConfiguration(modelId, endpoint,
                new JdbcModelBindings.ProviderProfile(providerId, serviceId,
                        profileVersion, protocol,
                        authenticationScheme, dialectVersion));
    }
}

final class ModelBindingRow {
    public String tenantId, bindingId, agentId, sourceKind, sourceId, contentDigest, endpoint, modelId,
            encryptedCredential, strategyId, strategyVersion, responseFormats, timeout,
            providerId, serviceId, profileVersion, protocol, authenticationScheme,
            dialectVersion;
    public ModelImagePolicy imagePolicy;
    public CapabilitySupportStatus systemUpdatesInHistory, retainToolsWithToolChoiceNone;
    public ToolUpdateMode toolUpdateMode;
    public int bindingFormatVersion;
    public long revision;
    public boolean streaming, reasoning;
    static ModelBindingRow from(TenantId tenant, String binding, Optional<AgentId> agent,
            ModelSourceRow source, JdbcModelBindings.Profile profile) {
        var row = new ModelBindingRow(); row.tenantId = tenant.value(); row.bindingId = binding;
        row.agentId = agent.map(AgentId::value).orElse(null); row.sourceKind = source.sourceKind; row.sourceId = source.sourceId;
        row.endpoint = source.endpoint; row.modelId = source.modelId; row.encryptedCredential = source.encryptedCredential;
        row.bindingFormatVersion = JdbcModelBindings.CURRENT_BINDING_FORMAT;
        row.providerId = source.providerId; row.serviceId = source.serviceId;
        row.profileVersion = source.profileVersion; row.protocol = source.protocol;
        row.authenticationScheme = source.authenticationScheme;
        row.dialectVersion = source.dialectVersion;
        row.strategyId = profile.strategy().strategyId(); row.strategyVersion = profile.strategy().strategyVersion();
        row.responseFormats = profile.responseFormats().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
        row.streaming = profile.streaming(); row.reasoning = profile.reasoning(); row.timeout = profile.timeout().toString();
        row.imagePolicy = profile.imagePolicy();
        row.systemUpdatesInHistory = profile.prefixCapabilities().systemUpdatesInHistory();
        row.toolUpdateMode = profile.prefixCapabilities().toolUpdates();
        row.retainToolsWithToolChoiceNone = profile.prefixCapabilities().retainToolsWithToolChoiceNone();
        row.contentDigest = row.digest(); return row;
    }
    private String digest() {
        if (bindingFormatVersion != JdbcModelBindings.CURRENT_BINDING_FORMAT) {
            throw new IllegalStateException("model_binding_format_unsupported");
        }
        return JdbcModelBindings.digest(List.of("model-binding-v4", tenantId, bindingId,
                agentId == null ? "" : agentId, sourceKind, sourceId, providerId, serviceId,
                profileVersion, protocol, authenticationScheme, dialectVersion, endpoint, modelId,
                encryptedCredential, strategyId, strategyVersion, responseFormats,
                Boolean.toString(streaming), Boolean.toString(reasoning), timeout, canonicalImagePolicy(),
                Objects.requireNonNull(systemUpdatesInHistory, "systemUpdatesInHistory").name(),
                Objects.requireNonNull(toolUpdateMode, "toolUpdateMode").name(),
                Objects.requireNonNull(retainToolsWithToolChoiceNone, "retainToolsWithToolChoiceNone").name()));
    }
    JdbcModelBindings.Binding restore() {
        if (!digest().equals(contentDigest)) throw new IllegalStateException("model_binding_snapshot_mismatch");
        Set<ResponseFormatKind> formats = Arrays.stream(responseFormats.split(",")).map(ResponseFormatKind::valueOf)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var strategy = new RequestedModelStrategy(strategyId, strategyVersion);
        var policy = Objects.requireNonNull(imagePolicy, "imagePolicy");
        var prefix = new PromptPrefixCapabilities(systemUpdatesInHistory, toolUpdateMode, retainToolsWithToolChoiceNone);
        var profile = new JdbcModelBindings.Profile(strategy, formats, streaming, reasoning, Duration.parse(timeout), policy, prefix);
        String fact = JdbcModelBindings.digest(List.of(contentDigest, Long.toString(revision)));
        var target = new FrozenModelTarget(new ModelBindingRef(new TenantId(tenantId), bindingId, revision), modelId,
                new ModelCapabilitySnapshot(formats, streaming, reasoning, new ResultRef("model-capability:" + fact), policy, prefix),
                profile.timeout(), new ResultRef("model-resolution:" + fact));
        var provider = new JdbcModelBindings.ProviderProfile(providerId, serviceId,
                profileVersion, protocol, authenticationScheme, dialectVersion);
        return new JdbcModelBindings.Binding(target, strategy, endpoint, encryptedCredential,
                provider, sourceId);
    }
    private String canonicalImagePolicy() {
        var p = Objects.requireNonNull(imagePolicy, "imagePolicy");
        return String.join("\n", Boolean.toString(p.enabled()), Boolean.toString(p.toolImages()),
                Integer.toString(p.maxImages()), Long.toString(p.maxImageBytes()), Long.toString(p.maxRequestBytes()),
                Integer.toString(p.maxDimension()), Integer.toString(p.tileSize()), Integer.toString(p.baseTokens()),
                Integer.toString(p.tileTokens()), p.version());
    }
}
