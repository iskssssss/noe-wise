package com.skillforge.core.capability.integration.runtime.tool;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
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

/** 原 MCP 部署和 Connector 的精确不可变路由；不提供 snapshot membership 自证。 */
public final class JdbcMcpBindings {
    public static final String PROTOCOL_VERSION = "2026-07-28";
    private final SqlSessionFactory sessions;
    public JdbcMcpBindings(DataSource source) {
        var config = new Configuration(new Environment("mcp-bindings", new JdbcTransactionFactory(), Objects.requireNonNull(source)));
        config.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/AgentRuntimeMcpBindingMapper.xml";
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException();
            new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
        } catch (Exception failure) { throw new IllegalStateException("mcp_binding_initialization_failure", failure); }
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    /** 只允许受信发布入口；endpoint、密文与 Tool descriptor 必须从原权威行读取。 */
    public Binding publish(TenantId tenant, FrozenToolTarget target, Profile profile) {
        Objects.requireNonNull(tenant); Objects.requireNonNull(target); Objects.requireNonNull(profile);
        // MCP 尚无可信动作键重放协议；在访问存储前拒绝幂等写声明，避免约束错误被当成暂时故障。
        if (target.idempotent() && target.effect() != ToolEffect.READ_ONLY) {
            throw new IllegalArgumentException("mcp_write_idempotence_unsupported");
        }
        return transaction(tenant, mapper -> {
            mapper.lockRoute(tenant.value(), target.deploymentId());
            // toMillis 只能用于无损毫秒匹配，不能把调用者纳秒截断为另一超时。
            long timeout = target.timeout().toMillis();
            if (!Duration.ofMillis(timeout).equals(target.timeout())) throw invalid();
            var source = mapper.source(tenant.value(), target, profile.toolName(), timeout);
            if (source == null) throw invalid();
            requirePolicy(source,target);
            validateEndpoint(source.endpoint);
            if (!("none".equals(source.authType) || "bearer".equals(source.authType))) throw invalid();
            if ("bearer".equals(source.authType) && (source.encryptedCredential == null || source.encryptedCredential.isBlank())) throw invalid();
            if ("none".equals(source.authType) && source.encryptedCredential != null && !source.encryptedCredential.isBlank()) throw invalid();
            var row = McpBindingRow.from(tenant, target, new Profile(profile.toolName(), source.protocolVersion), source);
            var existing = mapper.find(target);
            if (existing != null) {
                existing.requireIntegrity();
                if (!existing.contentDigest.equals(row.contentDigest)) throw new IllegalArgumentException("mcp_binding_conflict");
                return existing.restore(target);
            }
            if (mapper.insert(row) != 1) throw new IllegalStateException("mcp_binding_insert_failed");
            return row.restore(target);
        });
    }

    /** 只读冻结事实；不查原 current active、Connector 或宿主 Registry。 */
    public Optional<Binding> find(TenantId tenant, FrozenToolTarget target) {
        Objects.requireNonNull(tenant); Objects.requireNonNull(target);
        return transaction(tenant, mapper -> {
            var row = mapper.find(target);
            if (row == null) return Optional.empty();
            row.requireIntegrity();
            return row.matches(target) ? Optional.of(row.restore(target)) : Optional.empty();
        });
    }

    public record Profile(String toolName, String protocolVersion) {
        public Profile {
            if (toolName == null || toolName.isBlank() || !Set.of(PROTOCOL_VERSION, "2025-11-25").contains(protocolVersion)) throw invalid();
        }
    }
    public record Binding(FrozenToolTarget target, String endpoint, Optional<String> encryptedCredential,
                          String toolName, String protocolVersion,String providerRef) {
        public Binding {
            Objects.requireNonNull(target); Objects.requireNonNull(encryptedCredential);
            validateEndpoint(endpoint); new Profile(toolName, protocolVersion);
            if(providerRef==null||providerRef.isBlank()||!providerRef.equals(providerRef.trim()))throw invalid();
            if (encryptedCredential.isPresent() && encryptedCredential.orElseThrow().isBlank()) throw invalid();
        }
        @Override public String toString() { return "McpBinding[revision=" + target.deploymentRevision() + "]"; }
    }
    private <T> T transaction(TenantId tenant, Function<McpBindingMapper,T> work) {
        try (var session = sessions.openSession(false)) {
            try {
                var mapper = session.getMapper(McpBindingMapper.class); mapper.bindTenant(tenant.value());
                T value = work.apply(mapper); session.commit(); return value;
            } catch (RuntimeException failure) { session.rollback(); throw failure; }
        } catch (IllegalArgumentException rejected) { throw rejected; }
        catch (RuntimeException failure) { throw new IllegalStateException("mcp_binding_storage_failure"); }
    }
    private static void validateEndpoint(String endpoint) {
        try {
            var uri = URI.create(endpoint);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) throw invalid();
        } catch (RuntimeException rejected) { throw invalid(); }
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("mcp_binding_rejected"); }
    private static void requirePolicy(McpSourceRow source,FrozenToolTarget target){
        try{
            if(source.modelInvocable==null||source.modelInvocable!=target.modelInvocable()
                    ||source.confirmationPolicy==null||!source.confirmationPolicy.equals(target.confirmation().name())
                    ||source.outputSchemaDeclared==null||source.outputSchemaDeclared!=target.outputSchemaDeclared()
                    ||!target.callIdentityPolicy().name().equals(source.callIdentityPolicy)
                    ||!JdbcToolPolicyProjection.decodeTextSet(source.sensitiveFields).equals(target.sensitiveFields())
                    ||!JdbcToolPolicyProjection.decodeTextSet(source.providerRequirements).equals(target.providerRequirements()))throw invalid();
        }catch(Exception rejected){throw invalid();}
    }
    static String digest(List<String> fields) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(field));
                hash.update(ByteBuffer.allocate(4).putInt(buffer.remaining()).array()); hash.update(buffer);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (CharacterCodingException invalid) { throw invalid(); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("mcp_digest_unavailable"); }
    }
}

interface McpBindingMapper {
    String bindTenant(@Param("tenant") String tenant);
    String lockRoute(@Param("tenant") String tenant, @Param("deployment") String deployment);
    McpSourceRow source(@Param("tenant") String tenant, @Param("target") FrozenToolTarget target,
                        @Param("tool") String tool, @Param("timeout") long timeout);
    McpBindingRow find(@Param("target") FrozenToolTarget target);
    int insert(McpBindingRow row);
}
final class McpSourceRow {
    public String connectorId, endpoint, encryptedCredential, authType, descriptor, descriptorHash, protocolVersion;
    public String confirmationPolicy, sensitiveFields, providerRequirements, callIdentityPolicy;
    public Boolean modelInvocable, outputSchemaDeclared;
}
final class McpBindingRow {
    public String tenantId, handle, deploymentId, capabilityId, versionId, inputSchemaId, inputSchema,
            outputSchemaId, outputSchema, effect, timeout, connectorId, endpoint, encryptedCredential,
            authType, descriptor, descriptorHash, toolName, protocolVersion, contentDigest,
            confirmationPolicy, sensitiveFields, providerRequirements, callIdentityPolicy;
    public Boolean modelInvocable, outputSchemaDeclared;
    public long deploymentRevision;
    public boolean idempotent;
    static McpBindingRow from(TenantId tenant,FrozenToolTarget target,JdbcMcpBindings.Profile profile,McpSourceRow source) {
        var r = new McpBindingRow(); r.tenantId=tenant.value(); r.handle=target.handle().value(); r.deploymentId=target.deploymentId();
        r.deploymentRevision=target.deploymentRevision(); r.capabilityId=target.capability().capabilityId(); r.versionId=target.capability().versionId();
        r.inputSchemaId=target.inputSchema().schemaId(); r.inputSchema=target.inputSchema().canonicalJson();
        r.outputSchemaId=target.outputSchema().schemaId(); r.outputSchema=target.outputSchema().canonicalJson();
        r.effect=target.effect().name(); r.idempotent=target.idempotent(); r.timeout=target.timeout().toString();
        r.connectorId=source.connectorId; r.endpoint=source.endpoint; r.authType=source.authType;
        r.encryptedCredential="none".equals(source.authType)?null:source.encryptedCredential;
        r.descriptor=source.descriptor; r.descriptorHash=source.descriptorHash;
        r.toolName=profile.toolName(); r.protocolVersion=profile.protocolVersion();
        r.modelInvocable=source.modelInvocable;r.confirmationPolicy=source.confirmationPolicy;
        r.sensitiveFields=source.sensitiveFields;r.providerRequirements=source.providerRequirements;
        r.outputSchemaDeclared=source.outputSchemaDeclared;r.callIdentityPolicy=source.callIdentityPolicy;r.contentDigest=r.digest();return r;
    }
    private String digest() {
        var fields=new ArrayList<>(List.of("mcp-binding-v3",tenantId,handle,deploymentId,Long.toString(deploymentRevision),capabilityId,versionId,
                inputSchemaId,inputSchema,outputSchemaId,outputSchema,effect,Boolean.toString(idempotent),timeout,connectorId,
                endpoint,authType,encryptedCredential==null?"":encryptedCredential,descriptor,descriptorHash,toolName,protocolVersion,
                Boolean.toString(modelInvocable),confirmationPolicy,Boolean.toString(outputSchemaDeclared),callIdentityPolicy));
        var sensitive=JdbcToolPolicyProjection.canonicalTextSet(
                JdbcToolPolicyProjection.decodeTextSet(sensitiveFields));
        fields.add(Integer.toString(sensitive.size()));fields.addAll(sensitive);
        var requirements=JdbcToolPolicyProjection.canonicalTextSet(
                JdbcToolPolicyProjection.decodeTextSet(providerRequirements));
        fields.add(Integer.toString(requirements.size()));fields.addAll(requirements);
        return JdbcMcpBindings.digest(fields);
    }
    void requireIntegrity() {
        if (!digest().equals(contentDigest)) {
            throw new IllegalStateException("mcp_binding_snapshot_mismatch");
        }
    }
    boolean matches(FrozenToolTarget expected) {
        return expected!=null&&handle.equals(expected.handle().value())&&deploymentId.equals(expected.deploymentId())
                &&deploymentRevision==expected.deploymentRevision()&&capabilityId.equals(expected.capability().capabilityId())
                &&versionId.equals(expected.capability().versionId())&&inputSchemaId.equals(expected.inputSchema().schemaId())
                &&inputSchema.equals(expected.inputSchema().canonicalJson())&&outputSchemaId.equals(expected.outputSchema().schemaId())
                &&outputSchema.equals(expected.outputSchema().canonicalJson())&&effect.equals(expected.effect().name())
                &&idempotent==expected.idempotent()&&timeout.equals(expected.timeout().toString())
                &&Boolean.valueOf(expected.modelInvocable()).equals(modelInvocable)
                &&expected.confirmation().name().equals(confirmationPolicy)
                &&Boolean.valueOf(expected.outputSchemaDeclared()).equals(outputSchemaDeclared)
                &&expected.callIdentityPolicy().name().equals(callIdentityPolicy)
                &&JdbcToolPolicyProjection.decodeTextSet(sensitiveFields).equals(expected.sensitiveFields())
                &&JdbcToolPolicyProjection.decodeTextSet(providerRequirements).equals(expected.providerRequirements());
    }
    JdbcMcpBindings.Binding restore(FrozenToolTarget expected) {
        requireIntegrity();
        if(!matches(expected))throw JdbcMcpBindings.invalid();
        return new JdbcMcpBindings.Binding(expected,endpoint,Optional.ofNullable(encryptedCredential),toolName,protocolVersion,connectorId);
    }
}
