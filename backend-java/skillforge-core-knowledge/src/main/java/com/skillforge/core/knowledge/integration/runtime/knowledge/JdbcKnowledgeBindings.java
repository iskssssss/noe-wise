package com.skillforge.core.knowledge.integration.runtime.knowledge;

import com.skillforge.runtime.adapter.jdbc.observability.JdbcMapperLogging;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.KnowledgePortal.*;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 从真实KB revision/Capability发布冻结精确来源引用；KEYWORD解析所属租户不可变分块正文。 */
public final class JdbcKnowledgeBindings {
    private static final System.Logger LOG=System.getLogger(JdbcKnowledgeBindings.class.getName());
    private final KnowledgeQueryCancellation cancellation = new KnowledgeQueryCancellation();
    private final SqlSessionFactory sessions;
    /** 装配冻结知识映射、取消控制和访问观测；只使用宿主提供的数据源。 */
    public JdbcKnowledgeBindings(DataSource source) {
        Objects.requireNonNull(source);
        var config = new Configuration(new Environment("knowledge-bindings", new JdbcTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.addInterceptor(cancellation);
        config.addInterceptor(new JdbcMapperLogging());
        configure(config);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    /** 调用方业务事务注册精确知识绑定映射；不建立连接、开启事务或修改租户。 */
    public static void configure(Configuration configuration) {
        Objects.requireNonNull(configuration);
        if (configuration.hasMapper(KnowledgeBindingMapper.class)) return;
        String resource = "mapper/AgentRuntimeKnowledgeBindingMapper.xml";
        try (InputStream xml = JdbcKnowledgeBindings.class.getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("knowledge_binding_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception failure) { throw new IllegalStateException("knowledge_binding_initialization_failure", failure); }
    }

    /** 受信发布者入口；不证明当前Session对挂载的成员授权。缺失任何原版本内容则整个发布回滚。 */
    public Binding publish(TenantId tenant, FrozenKnowledgeTarget target) {
        validateTarget(tenant, target);
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction(tenant, true, mapper -> {
                    String id = bindingId(tenant, target); mapper.lockBinding(id);
                    var old = mapper.find(id);
                    if (old != null) return restore(mapper, old, target).orElseThrow(() -> rejected("knowledge_binding_conflict"));
                    var original = mapper.source(tenant.value(), target, versions(target), operators(target));
                    if (original == null) throw rejected("knowledge_binding_source_rejected");
                    var row = new KnowledgeBindingRow(); row.tenantId=tenant.value(); row.bindingId=id; row.targetDigest=targetDigest(target);
                    row.handle=target.handle().value(); row.descriptorId=target.descriptorId(); row.descriptorRevision=target.descriptorRevision();
                    row.capabilityId=target.capability().capabilityId(); row.versionId=target.capability().versionId();
                    row.descriptorJson=original.descriptorJson; row.publishedAt=original.publishedAt; row.sourceCount=target.sourceVersions().size();
                    if (mapper.insertBinding(row)!=1) throw new IllegalStateException();
                    for (var version : versions(target)) {
                        if (mapper.copySource(tenant.value(), id, version)!=1) throw rejected("knowledge_source_version_unavailable");
                    }
                    mapper.copyChunks(tenant.value(), id);
                    return restore(mapper,row,target).orElseThrow(() -> new IllegalStateException("knowledge_snapshot_incomplete"));
                });
            } catch (RuntimeException failure) {
                if (attempt < 2 && serializationConflict(failure)) continue;
                if (failure instanceof IllegalArgumentException rejected) throw rejected;
                throw new IllegalStateException("knowledge_binding_storage_failure");
            }
        }
    }

    /** 仅核对已冻结引用，不追踪active revision；原正文由关系外键保护。 */
    public Optional<Binding> find(TenantId tenant, FrozenKnowledgeTarget target) {
        validateTarget(tenant,target);
        try { return transaction(tenant,false,mapper -> Optional.ofNullable(mapper.find(bindingId(tenant,target))).flatMap(row -> restore(mapper,row,target))); }
        catch (RuntimeException failure) { throw new IllegalStateException("knowledge_binding_read_failure"); }
    }

    /**
     * 派发事务内复用已绑定租户的SqlSession核对完整冻结来源；本方法不切租户、不提交事务。
     * 未绑定或绑定到其他租户时拒绝，不能让合法目标替代当前IAM或当前挂载检查。
     */
    public Optional<Binding> find(SqlSession session, TenantId tenant, FrozenKnowledgeTarget target) {
        Objects.requireNonNull(session); validateTarget(tenant, target);
        var mapper = session.getMapper(KnowledgeBindingMapper.class);
        if (!tenant.value().equals(mapper.currentTenant())) throw rejected("knowledge_transaction_tenant_mismatch");
        return Optional.ofNullable(mapper.find(bindingId(tenant, target))).flatMap(row -> restore(mapper, row, target));
    }

    /** 只核本次实际使用的精确来源，未使用的冻结来源不影响当前调用。 */
    public boolean currentSourcesAvailable(TenantId tenant, List<KnowledgeSourceVersion> sources) {
        Objects.requireNonNull(tenant); sources = List.copyOf(sources);
        if (sources.isEmpty()) return true;
        var selected = sources;
        return transaction(tenant, false, mapper -> mapper.sourcesAvailable(tenant.value(), selected));
    }

    /** 派发事务内沿用原租户和连接，不修改冻结目标或读取结果正文。 */
    public boolean currentSourcesAvailable(SqlSession session, TenantId tenant, List<KnowledgeSourceVersion> sources) {
        Objects.requireNonNull(session); Objects.requireNonNull(tenant); sources = List.copyOf(sources);
        if (sources.isEmpty()) return true;
        configure(session.getConfiguration());
        var mapper = session.getMapper(KnowledgeBindingMapper.class);
        if (!tenant.value().equals(mapper.currentTenant())) throw rejected("knowledge_transaction_tenant_mismatch");
        return mapper.sourcesAvailable(tenant.value(), sources);
    }

    /** 对一个来源的授权版本集合执行一次字面查询；文档数量不消耗额外查询预算。 */
    public List<ChunkHit> keyword(TenantId tenant, FrozenKnowledgeTarget target, Set<KnowledgeSourceVersion> versions,
            String query, int limit, Instant asOf, Optional<Instant> freshnessAfter, InvocationControl control) {
        validateTarget(tenant,target); versions=Set.copyOf(versions); Objects.requireNonNull(asOf); Objects.requireNonNull(freshnessAfter); Objects.requireNonNull(control);
        if (versions.isEmpty() || !target.sourceVersions().containsAll(versions)
                || versions.stream().map(KnowledgeSourceVersion::sourceId).distinct().count()!=1
                || !target.allowedOperators().contains(KnowledgeOperatorType.KEYWORD)
                || query==null || query.isBlank() || limit<=0) throw rejected("knowledge_query_rejected");
        checkControl(control);
        var deadline=Instant.now().plus(target.timeout()); if(control.deadlineAt().isBefore(deadline)) deadline=control.deadlineAt();
        final Instant effectiveDeadline=deadline;
        try(var session=sessions.openSession(false)) {
            try {
                var mapper=session.getMapper(KnowledgeBindingMapper.class);
                mapper.configureRead(tenant.value(),Long.toString(Math.max(1,Duration.between(Instant.now(),effectiveDeadline).toMillis())));
                var parameters=new HashMap<String,Object>(); parameters.put("tenant",tenant.value());parameters.put("id",bindingId(tenant,target));
                parameters.put("digest",targetDigest(target));parameters.put("versions",versions.stream()
                        .sorted(Comparator.comparing(KnowledgeSourceVersion::sourceId).thenComparing(KnowledgeSourceVersion::sourceVersion)).toList());
                parameters.put("query",query);parameters.put("limit",limit);
                parameters.put("asOf",asOf.atOffset(ZoneOffset.UTC));parameters.put("freshness",freshnessAfter.map(value->value.atOffset(ZoneOffset.UTC)).orElse(null));
                var rows=cancellation.execute(control,effectiveDeadline,()->mapper.keyword(parameters));
                if(rows.isEmpty())throw rejected("knowledge_binding_missing");
                var result=new ArrayList<ChunkHit>();
                for(var row:rows) {
                    checkControl(control);
                    if(row.sourceId==null)continue;
                    result.add(new ChunkHit(new KnowledgeSourceVersion(row.sourceId,row.sourceVersion),row.documentId,
                            row.chunkId,row.chunkIndex,row.content,row.createdAt));
                }
                checkControl(control);
                if(!Instant.now().isBefore(effectiveDeadline))throw rejected("knowledge_query_deadline");
                return List.copyOf(result);
            } finally { session.rollback(); }
        } catch(IllegalArgumentException rejected) { throw rejected; }
        catch(RuntimeException failure) {
            checkControl(control);
            if(!Instant.now().isBefore(effectiveDeadline))throw rejected("knowledge_query_deadline");
            throw new IllegalStateException("knowledge_query_storage_failure");
        }
    }

    public record Binding(FrozenKnowledgeTarget target,String descriptorJson,Instant publishedAt,String snapshotId) {
        public Binding { Objects.requireNonNull(target);Objects.requireNonNull(descriptorJson);Objects.requireNonNull(publishedAt);Objects.requireNonNull(snapshotId); }
        @Override public String toString(){return "KnowledgeBinding[revision="+target.descriptorRevision()+"]";}
    }
    public record ChunkHit(KnowledgeSourceVersion sourceVersion,String documentId,String chunkId,int chunkIndex,String content,Instant createdAt) {
        public ChunkHit { Objects.requireNonNull(sourceVersion);Objects.requireNonNull(documentId);Objects.requireNonNull(chunkId);Objects.requireNonNull(content);Objects.requireNonNull(createdAt);if(chunkIndex<0)throw rejected("knowledge_chunk_invalid"); }
        @Override public String toString(){return "KnowledgeChunk[REDACTED]";}
    }

    /** 目录只读取冻结版本元数据与正文长度，不返回原文或追踪当前文档指针。 */
    public List<DocumentHit> listDocuments(TenantId tenant, FrozenKnowledgeTarget target,
            Set<KnowledgeSourceVersion> versions, String titleFilter, Optional<DocumentPosition> after,
            int limit, Instant asOf, Optional<Instant> freshnessAfter, InvocationControl control) {
        documentScope(tenant,target,versions,KnowledgeOperatorType.DOCUMENT_LIST);
        Objects.requireNonNull(titleFilter);Objects.requireNonNull(after);
        if(limit<=0 || limit>51)throw rejected("knowledge_document_query_rejected");
        var scope=selection(tenant,target,versions,asOf,freshnessAfter);
        return documentRead(tenant,target,control,mapper->{
            var rows=mapper.documentList(new DocumentListSelection(scope,titleFilter,after.orElse(null),limit));
            if(rows.isEmpty())throw rejected("knowledge_binding_missing");
            var result=new ArrayList<DocumentHit>();
            for(var row:rows)if(row.sourceId!=null)result.add(document(row));
            return List.copyOf(result);
        });
    }

    /** 精确版本的完整原文分片；PostgreSQL substring/char_length 与 API 的 Unicode 码点范围一致。 */
    public Optional<DocumentSliceHit> readDocument(TenantId tenant, FrozenKnowledgeTarget target,
            Set<KnowledgeSourceVersion> versions, KnowledgeOperation.ReadDocument operation,
            Instant asOf, Optional<Instant> freshnessAfter, InvocationControl control) {
        documentScope(tenant,target,versions,KnowledgeOperatorType.DOCUMENT_READ);Objects.requireNonNull(operation);
        var selected=new KnowledgeSourceVersion(operation.sourceId(),operation.sourceVersion());
        if(!versions.contains(selected))return Optional.empty();
        var scope=selection(tenant,target,Set.of(selected),asOf,freshnessAfter);
        return documentRead(tenant,target,control,mapper->{
            var rows=mapper.documentSlice(new DocumentReadSelection(scope,operation.documentId(),operation.offset(),operation.maxCharacters()));
            if(rows.isEmpty())throw rejected("knowledge_binding_missing");
            var row=rows.getFirst();
            if(row.sourceId==null)return Optional.empty();
            var document=document(row);
            if(operation.offset()>document.totalCharacters())throw rejected("knowledge_document_range_rejected");
            if(row.content==null)throw rejected("knowledge_document_unavailable");
            int length=row.content.codePointCount(0,row.content.length());
            return Optional.of(new DocumentSliceHit(document,operation.offset(),Math.addExact(operation.offset(),length),row.content));
        });
    }

    /** 可恢复的稳定目录位置，只表示已返回元数据的排序位置，不授予正文读取权。 */
    public record DocumentPosition(String sourceId,String documentId,String sourceVersion) {
        public DocumentPosition { Objects.requireNonNull(sourceId);Objects.requireNonNull(documentId);Objects.requireNonNull(sourceVersion); }
    }
    public record DocumentHit(KnowledgeSourceVersion sourceVersion,String documentId,String title,int totalCharacters) {
        public DocumentHit { Objects.requireNonNull(sourceVersion);Objects.requireNonNull(documentId);Objects.requireNonNull(title);if(totalCharacters<0)throw rejected("knowledge_document_unavailable"); }
        public DocumentPosition position(){return new DocumentPosition(sourceVersion.sourceId(),documentId,sourceVersion.sourceVersion());}
    }
    public record DocumentSliceHit(DocumentHit document,int startOffset,int endOffset,String text) {
        public DocumentSliceHit { Objects.requireNonNull(document);Objects.requireNonNull(text);if(startOffset<0||endOffset<startOffset||endOffset>document.totalCharacters()
                ||text.codePointCount(0,text.length())!=endOffset-startOffset)throw rejected("knowledge_document_range_rejected"); }
    }
    record DocumentSelection(String tenant,String id,String digest,List<KnowledgeSourceVersion> versions,OffsetDateTime asOf,OffsetDateTime freshness) { }
    record DocumentListSelection(DocumentSelection scope,String titleFilter,DocumentPosition after,int limit) { }
    record DocumentReadSelection(DocumentSelection scope,String documentId,int offset,int limit) { }

    private static DocumentSelection selection(TenantId tenant,FrozenKnowledgeTarget target,Set<KnowledgeSourceVersion> versions,
            Instant asOf,Optional<Instant> freshnessAfter) {
        Objects.requireNonNull(asOf);Objects.requireNonNull(freshnessAfter);
        return new DocumentSelection(tenant.value(),bindingId(tenant,target),targetDigest(target),versions.stream()
                .sorted(Comparator.comparing(KnowledgeSourceVersion::sourceId).thenComparing(KnowledgeSourceVersion::sourceVersion)).toList(),
                asOf.atOffset(ZoneOffset.UTC),freshnessAfter.map(value->value.atOffset(ZoneOffset.UTC)).orElse(null));
    }
    private static DocumentHit document(KnowledgeDocumentRow row) {
        if(row.documentId==null||row.sourceVersion==null||row.title==null||row.totalCharacters==null)
            throw rejected("knowledge_document_unavailable");
        return new DocumentHit(new KnowledgeSourceVersion(row.sourceId,row.sourceVersion),row.documentId,row.title,row.totalCharacters);
    }
    private static void documentScope(TenantId tenant,FrozenKnowledgeTarget target,Set<KnowledgeSourceVersion> versions,KnowledgeOperatorType operator) {
        validateTarget(tenant,target);Objects.requireNonNull(versions);
        if(versions.isEmpty()||!target.sourceVersions().containsAll(versions)||!target.allowedOperators().contains(operator))
            throw rejected("knowledge_document_unavailable");
    }
    /** 同一有界只读事务执行目录或原文读取；取消、超时及存储故障不伪装成确定业务拒绝。 */
    private <T>T documentRead(TenantId tenant,FrozenKnowledgeTarget target,InvocationControl control,Function<KnowledgeBindingMapper,T> work) {
        Objects.requireNonNull(control);checkControl(control);
        Instant deadline=Instant.now().plus(target.timeout());if(control.deadlineAt().isBefore(deadline))deadline=control.deadlineAt();
        final Instant effective=deadline;
        try(var session=sessions.openSession(false)) {
            try {
                var mapper=session.getMapper(KnowledgeBindingMapper.class);
                mapper.configureRead(tenant.value(),Long.toString(Math.max(1,Duration.between(Instant.now(),effective).toMillis())));
                T result=cancellation.execute(control,effective,()->work.apply(mapper));
                checkControl(control);if(!Instant.now().isBefore(effective))throw rejected("knowledge_query_deadline");
                return result;
            } finally { session.rollback(); }
        } catch(IllegalArgumentException rejected){throw rejected;}
        catch(RuntimeException failure){checkControl(control);if(!Instant.now().isBefore(effective))throw rejected("knowledge_query_deadline");
            throw new IllegalStateException("knowledge_document_storage_failure",failure);}
    }
    /** 核验冻结摘要、来源集合和全部块数后还原绑定，任何快照不完整都拒绝。 */
    private static Optional<Binding> restore(KnowledgeBindingMapper mapper,KnowledgeBindingRow row,FrozenKnowledgeTarget target) {
        if(!row.targetDigest.equals(targetDigest(target)))return Optional.empty();
        var stored=mapper.sources(row.bindingId); var actual=new HashSet<KnowledgeSourceVersion>();
        for(var item:stored) { if(item.chunkCount<=0 || item.chunkCount!=item.actualCount)throw new IllegalStateException("knowledge_snapshot_incomplete"); actual.add(new KnowledgeSourceVersion(item.sourceId,item.sourceVersion)); }
        if(row.sourceCount!=actual.size() || !actual.equals(target.sourceVersions()))throw new IllegalStateException("knowledge_snapshot_manifest_mismatch");
        return Optional.of(new Binding(target,row.descriptorJson,row.publishedAt,row.bindingId));
    }
    /** 绑定租户执行发布或读取；发布可使用可重复读，失败整体回滚。 */
    private <T>T transaction(TenantId tenant,boolean repeatable,Function<KnowledgeBindingMapper,T> work) {
        String transactionTag=UUID.randomUUID().toString();
        LOG.log(System.Logger.Level.DEBUG,"知识绑定事务开始 transactionTag={0} repeatable={1}",transactionTag,repeatable);
        try(var session=sessions.openSession(false)) {
            try { if(repeatable)session.getConnection().setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                var mapper=session.getMapper(KnowledgeBindingMapper.class);mapper.bindTenant(tenant.value());var result=work.apply(mapper);session.commit();return result;
            } catch(IllegalArgumentException rejected){session.rollback();throw rejected;}
            catch(RuntimeException|SQLException failure){session.rollback();throw new IllegalStateException("knowledge_binding_transaction_failure",failure);}
        } finally { LOG.log(System.Logger.Level.DEBUG,"知识绑定事务结束 transactionTag={0}",transactionTag); }
    }
    /** 拒绝不合法租户、描述符、空来源及跨描述符来源，防止绕过冻结目标。 */
    private static void validateTarget(TenantId tenant,FrozenKnowledgeTarget target) {
        Objects.requireNonNull(tenant);Objects.requireNonNull(target);
        if(!tenant.value().matches("[1-9][0-9]*") || !target.descriptorId().matches("[1-9][0-9]*") || target.sourceVersions().isEmpty()
                || target.allowedOperators().isEmpty() || target.sourceVersions().stream().anyMatch(v->!v.sourceId().equals(target.descriptorId())))throw rejected("knowledge_target_rejected");
    }
    /** 在访问前后检查单调取消与调用截止时间，拒绝继续交付迟到结果。 */
    private static void checkControl(InvocationControl control) {
        if(control.cancellationSignal().isCancellationRequested())throw rejected("knowledge_query_cancelled");
        if(!Instant.now().isBefore(control.deadlineAt()))throw rejected("knowledge_query_deadline");
    }
    /** 按来源和版本确定性排序，保证冻结摘要与 SQL 参数稳定。 */
    private static List<KnowledgeSourceVersion> versions(FrozenKnowledgeTarget target){return target.sourceVersions().stream().sorted(Comparator.comparing(KnowledgeSourceVersion::sourceId).thenComparing(KnowledgeSourceVersion::sourceVersion)).toList();}
    /** 返回稳定排序的允许算子名称，用于发布源能力一致性核验。 */
    private static List<String> operators(FrozenKnowledgeTarget target){return target.allowedOperators().stream().map(Enum::name).sorted().toList();}
    /** 基于租户及冻结目标身份生成确定性绑定键，避免跨租户碰撞。 */
    private static String bindingId(TenantId tenant,FrozenKnowledgeTarget target){return digest(List.of(tenant.value(),target.handle().value(),target.descriptorId(),Long.toString(target.descriptorRevision()),target.capability().capabilityId(),target.capability().versionId()));}
    /** 覆盖来源、算子与超时计算目标内容摘要，禁止同身份暗改冻结内容。 */
    private static String targetDigest(FrozenKnowledgeTarget target){var fields=new ArrayList<>(List.of(target.handle().value(),target.descriptorId(),Long.toString(target.descriptorRevision()),target.capability().capabilityId(),target.capability().versionId(),target.timeout().toString()));for(var version:versions(target)){fields.add(version.sourceId());fields.add(version.sourceVersion());}fields.add("operators");fields.addAll(operators(target));return digest(fields);}
    /** 以长度前缀编码 UTF-8 字段后计算 SHA-256，拒绝不可编码输入，避免字段拼接歧义。 */
    private static String digest(List<String> fields){try{var d=MessageDigest.getInstance("SHA-256");for(var field:fields){var bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(field));d.update(ByteBuffer.allocate(4).putInt(bytes.remaining()).array());d.update(bytes);}return HexFormat.of().formatHex(d.digest());}catch(java.nio.charset.CharacterCodingException invalid){throw rejected("knowledge_target_rejected");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException();}}
    /** 识别可重试的序列化或唯一性竞争，其余失败不自动重试。 */
    private static boolean serializationConflict(Throwable failure){for(var cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql && ("40001".equals(sql.getSQLState())||"23505".equals(sql.getSQLState())))return true;return false;}
    /** 创建仅包含稳定原因码的拒绝异常，不包含来源正文或查询内容。 */
    private static IllegalArgumentException rejected(String reason){return new IllegalArgumentException(reason);}
}

interface KnowledgeBindingMapper {
    Map<String,Object> configureRead(@Param("tenant")String tenant,@Param("timeout")String timeout);
    List<KnowledgeKeywordRow> keyword(Map<String,Object> parameters);
    List<KnowledgeDocumentRow> documentList(JdbcKnowledgeBindings.DocumentListSelection selection);
    List<KnowledgeDocumentRow> documentSlice(JdbcKnowledgeBindings.DocumentReadSelection selection);
    boolean sourcesAvailable(@Param("tenant") String tenant, @Param("versions") List<KnowledgeSourceVersion> versions);
    String bindTenant(@Param("tenant")String tenant);
    String currentTenant();
    String lockBinding(@Param("id")String id);
    KnowledgeBindingRow find(@Param("id")String id);
    KnowledgeBindingSource source(@Param("tenant")String tenant,@Param("target")FrozenKnowledgeTarget target,@Param("versions")List<KnowledgeSourceVersion> versions,@Param("operators")List<String> operators);
    int insertBinding(KnowledgeBindingRow row);
    int copySource(@Param("tenant")String tenant,@Param("id")String id,@Param("version")KnowledgeSourceVersion version);
    int copyChunks(@Param("tenant")String tenant,@Param("id")String id);
    List<KnowledgeSourceRow> sources(@Param("id")String id);
}
final class KnowledgeBindingSource {public String descriptorJson;public Instant publishedAt;}
final class KnowledgeBindingRow {public String tenantId,bindingId,targetDigest,handle,descriptorId,capabilityId,versionId,descriptorJson;public long descriptorRevision;public int sourceCount;public Instant publishedAt;}
final class KnowledgeSourceRow {public String sourceId,sourceVersion;public long chunkCount,actualCount;}

final class KnowledgeKeywordRow { public String bindingId,sourceId,sourceVersion,documentId,chunkId,content; public int chunkIndex; public Instant createdAt; }
final class KnowledgeDocumentRow { public String bindingId,sourceId,sourceVersion,documentId,title,content;public Integer totalCharacters; }
