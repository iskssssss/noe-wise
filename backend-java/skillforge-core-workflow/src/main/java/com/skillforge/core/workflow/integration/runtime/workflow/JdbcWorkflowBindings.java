package com.skillforge.core.workflow.integration.runtime.workflow;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import java.io.InputStream;
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
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 冻结真实 Workflow instance 配置与不可变 DefinitionVersion，不回读当前实例覆盖原调用。 */
public final class JdbcWorkflowBindings {
    private final SqlSessionFactory sessions;
    public JdbcWorkflowBindings(DataSource source) {
        var config = new Configuration(new Environment("workflow-bindings", new JdbcTransactionFactory(), Objects.requireNonNull(source)));
        config.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/AgentRuntimeWorkflowBindingMapper.xml";
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException();
            new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
        } catch (Exception failure) { throw new IllegalStateException("workflow_binding_initialization_failure", failure); }
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    /** 受信发布者已确定当前 input 的 mount；此入口只从权威定义/实例校验并冻结，不证明成员关系。 */
    public Binding publish(TenantId tenant, FrozenWorkflowTarget target) {
        return publish(tenant,target,binding -> "{}");
    }

    /** 宿主在冻结事务内解析全部安装槽；任何槽失败都不得发布不完整父绑定。 */
    public Binding publish(TenantId tenant,FrozenWorkflowTarget target,Function<Binding,String> nodeBindings) {
        return publish(tenant,target,nodeBindings,Function.identity());
    }

    /** 宿主负责其私有槽 JSON 的规范编码，JDBC 仍逐字段验证不可变绑定并保留原始摘要。 */
    public Binding publish(TenantId tenant,FrozenWorkflowTarget target,Function<Binding,String> nodeBindings,
                           Function<String,String> canonicalBindings) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(nodeBindings);
        Objects.requireNonNull(canonicalBindings);
        return transaction(tenant, mapper -> {
            mapper.lockBinding(tenant.value(), target.instanceId());
            var source = mapper.source(tenant.value(), target);
            if (source == null) throw new IllegalArgumentException("workflow_binding_source_rejected");
            var row = WorkflowBindingRow.from(tenant, target, source);
            row.resolvedBindingsJson=Objects.requireNonNull(canonicalBindings.apply(Objects.requireNonNull(nodeBindings.apply(row.restore()))));
            row.digest=row.digest();
            var old = mapper.find(target);
            if (old != null) {
                // 先验证原始冻结字节；集合迭代顺序不构成目标漂移，也不能借规范化掩盖损坏快照。
                var restored = old.restore();
                if (!old.digest(Objects.requireNonNull(canonicalBindings.apply(old.resolvedBindingsJson))).equals(row.digest))
                    throw new IllegalArgumentException("workflow_binding_conflict");
                return restored;
            }
            if (mapper.insert(row) != 1) throw new IllegalStateException("workflow_binding_insert_failed");
            return row.restore();
        });
    }
    public Optional<Binding> find(TenantId tenant, FrozenWorkflowTarget target) {
        Objects.requireNonNull(target);
        return transaction(tenant, mapper -> Optional.ofNullable(mapper.find(target)).map(WorkflowBindingRow::restore)
                .filter(binding -> binding.target().equals(target)));
    }

    /** 原不可变绑定映射在工厂阶段注册，同事务guard不得临时修改Configuration。 */
    public static void configure(Configuration configuration) {
        Objects.requireNonNull(configuration);
        if (configuration.hasMapper(WorkflowBindingMapper.class)) return;
        String resource = "mapper/AgentRuntimeWorkflowBindingMapper.xml";
        try (InputStream xml = JdbcWorkflowBindings.class.getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("workflow_binding_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (java.io.IOException failure) { throw new IllegalStateException("workflow_binding_initialization_failure", failure); }
    }

    /** 借用已有租户事务读取精确冻结目标，继续验证既有不可变绑定摘要。 */
    public static Optional<Binding> findBorrowed(SqlSession session, FrozenWorkflowTarget target) {
        Objects.requireNonNull(session); Objects.requireNonNull(target);
        return Optional.ofNullable(session.getMapper(WorkflowBindingMapper.class).find(target))
                .map(WorkflowBindingRow::restore).filter(binding -> binding.target().equals(target));
    }

    /** 定义/config 是 Provider 内部 JSON 对象文本，只有 Workflow Engine parser 解析，不传入 SDK Core。 */
    public record Binding(FrozenWorkflowTarget target, String workflowKey, String definitionJson,
                          String configJson, Optional<String> customPrompt, String bindingsJson, String descriptorJson,
                          String resolvedBindingsJson) {
        public Binding {
            Objects.requireNonNull(target); Objects.requireNonNull(customPrompt); Objects.requireNonNull(descriptorJson);
            Objects.requireNonNull(resolvedBindingsJson);
            if (workflowKey == null || workflowKey.isBlank() || definitionJson == null || definitionJson.isBlank()
                    || configJson == null || configJson.isBlank() || bindingsJson == null || bindingsJson.isBlank())
                throw new IllegalArgumentException("workflow_binding_invalid");
        }
        @Override public String toString() { return "WorkflowBinding[revision=" + target.instanceRevision() + "]"; }
    }
    private <T> T transaction(TenantId tenant, Function<WorkflowBindingMapper,T> work) {
        Objects.requireNonNull(tenant);
        try (var session = sessions.openSession(false)) {
            try {
                var mapper = session.getMapper(WorkflowBindingMapper.class); mapper.bindTenant(tenant.value());
                T result = work.apply(mapper); session.commit(); return result;
            } catch (RuntimeException failure) { session.rollback(); throw failure; }
        } catch (IllegalArgumentException rejected) { throw rejected; }
        catch (RuntimeException failure) { throw new IllegalStateException("workflow_binding_storage_failure"); }
    }
    static String digest(List<String> fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                var value = StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(field));
                digest.update(ByteBuffer.allocate(4).putInt(value.remaining()).array()); digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("workflow_binding_invalid"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("sha256_unavailable"); }
    }
}

interface WorkflowBindingMapper {
    String bindTenant(@Param("tenant") String tenant);
    String lockBinding(@Param("tenant") String tenant, @Param("instance") String instance);
    WorkflowBindingSource source(@Param("tenant") String tenant, @Param("target") FrozenWorkflowTarget target);
    WorkflowBindingRow find(@Param("target") FrozenWorkflowTarget target);
    int insert(WorkflowBindingRow row);
}
final class WorkflowBindingSource {
    public String workflowKey, definitionJson, configJson, customPrompt, descriptor, bindingsJson;
}
final class WorkflowBindingRow {
    public String tenantId, handle, instanceId, capabilityId, versionId, timeout, workflowKey, definitionJson,
            configJson, customPrompt, descriptor, digest, bindingsJson, resolvedBindingsJson;
    public long instanceRevision, installationRevision;
    static WorkflowBindingRow from(TenantId tenant, FrozenWorkflowTarget target, WorkflowBindingSource source) {
        var r = new WorkflowBindingRow(); r.tenantId=tenant.value(); r.handle=target.handle().value(); r.instanceId=target.instanceId();
        r.instanceRevision=target.instanceRevision(); r.installationRevision=target.installationRevision(); r.capabilityId=target.capability().capabilityId(); r.versionId=target.capability().versionId();
        r.timeout=target.timeout().toString(); r.workflowKey=source.workflowKey; r.definitionJson=source.definitionJson;
        r.configJson=source.configJson; r.customPrompt=source.customPrompt; r.descriptor=source.descriptor; r.bindingsJson=source.bindingsJson;
        r.resolvedBindingsJson="{}"; r.digest=r.digest(); return r;
    }
    String digest() {
        return digest(resolvedBindingsJson);
    }
    String digest(String resolved) {
        return JdbcWorkflowBindings.digest(List.of("workflow-binding-v1", tenantId,handle,instanceId,Long.toString(instanceRevision),
                capabilityId,versionId,timeout,workflowKey,definitionJson,configJson,Boolean.toString(customPrompt!=null),
                customPrompt==null?"":customPrompt,descriptor,Long.toString(installationRevision),bindingsJson,resolved));
    }
    JdbcWorkflowBindings.Binding restore() {
        if (!digest.equals(digest())) throw new IllegalStateException("workflow_binding_snapshot_mismatch");
        return new JdbcWorkflowBindings.Binding(new FrozenWorkflowTarget(new WorkflowHandle(handle),instanceId,instanceRevision,
                new CapabilityRef(CapabilityType.WORKFLOW,capabilityId,versionId),Duration.parse(timeout),installationRevision),
                workflowKey,definitionJson,configJson,Optional.ofNullable(customPrompt),bindingsJson,descriptor,resolvedBindingsJson);
    }
}
