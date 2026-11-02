package com.skillforge.runtime.adapter.jdbc.memory;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.MemoryRetrievalPolicy.EmbeddingModel;
import com.skillforge.runtime.api.MemoryRetrievalDiagnostics;
import com.skillforge.runtime.adapter.jdbc.memory.search.MemorySearchDocument;
import com.skillforge.runtime.adapter.jdbc.memory.search.MemorySearchAnalyzer;
import com.skillforge.runtime.adapter.jdbc.memory.search.MemoryRankFusion;
import com.skillforge.runtime.api.MemoryQueryProfile;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.MemoryPort;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.MemoryPort.MemoryMutationResult;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryCall;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryOutcome;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryResult;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.RetryMode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** PostgreSQL Memory Store：不可变 revision、scope-aware head、事务 journal 与零写查询。 */
public final class JdbcMemoryPort implements MemoryPort {

    private static final Duration RECENT_WINDOW = Duration.ofDays(30);
    private final SqlSessionFactory sessions;
    /** 仅标识本线程同步 callback 正持有的短事务治理锁；不能跨线程传播。 */
    private final ThreadLocal<String> heldGovernanceOwner = new ThreadLocal<>();
    private final MemoryTransitionGuard transitionGuard;
    private final Function<MemoryFact,MemorySearchDocument> projector;
    private final Function<TenantId,MemoryRetrievalPolicy> indexingPolicy;
    private final MemorySearchEmbedding embedding;

    public JdbcMemoryPort(DataSource source, Function<MemoryFact,MemorySearchDocument> projector) {
        this(source, (session,command,current)->true, projector);
    }

    public JdbcMemoryPort(DataSource source, MemoryTransitionGuard transitionGuard, Function<MemoryFact,MemorySearchDocument> projector) {
        this(source,transitionGuard,projector,tenant->MemoryRetrievalPolicy.lexicalV1(),
                request->new MemorySearchEmbedding.Outcome.Rejected("memory_embedding_provider_not_configured"));
    }

    public JdbcMemoryPort(DataSource source, MemoryTransitionGuard transitionGuard,
            Function<MemoryFact,MemorySearchDocument> projector,
            Function<TenantId,MemoryRetrievalPolicy> indexingPolicy, MemorySearchEmbedding embedding) {
        Objects.requireNonNull(source, "source");
        this.transitionGuard = Objects.requireNonNull(transitionGuard, "transitionGuard");
        this.projector=Objects.requireNonNull(projector,"projector");
        this.indexingPolicy=Objects.requireNonNull(indexingPolicy,"indexingPolicy");
        this.embedding=Objects.requireNonNull(embedding,"embedding");
        var configuration = new Configuration(new Environment(
                "agent-runtime-memory", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        // 治理锁和 head 锁等待后必须读取新语句快照，不能复用锁前的 owner 或 revision。
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        String resource = "mapper/AgentRuntimeMemoryMapper.xml";
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("memory_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource,
                    configuration.getSqlFragments()).parse();
        } catch (Exception failure) {
            throw new IllegalStateException("memory_store_initialization_failure", failure);
        }
        transitionGuard.configure(configuration);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Override
    public MemoryQueryOutcome query(MemoryQueryCall call) {
        Objects.requireNonNull(call, "call");
        if (!callAllowed(call.context())) {
            return failed("memory_query_cancelled", call.query().queryId());
        }
        MemoryQuery query = call.query();
        if (!queryAllowed(call.context(), query)) return failed("memory_query_owner_mismatch", query.queryId());
        if (query.profile() == MemoryQueryProfile.RECENT_EPISODIC
                && (!query.scopes().equals(Set.of(MemoryScope.USER_AGENT))
                || query.types().isEmpty())) {
            return failed("memory_recent_episodic_contract_mismatch", query.queryId());
        }
        TenantId tenant = call.context().runIdentity().owner().sessionRef().tenantId();
        if (!MemorySearchAnalyzer.VERSION.equals(query.retrievalPolicy().analyzerVersion())) return failed("memory_analyzer_version_unsupported",query.queryId());
        try {
            var existingOperation=transaction(tenant,mapper->mapper.queryEmbedding(tenant.value(),queryOperationId(call)));
            if(existingOperation!=null && !digest(MemoryJdbcCodec.encodeQuery(call)).equals(existingOperation.fingerprint))
                throw new CallRejected("memory_query_embedding_request_conflict");
            MemorySearchStatsRow initial=transaction(tenant,mapper->mapper.searchStats(searchFilter(call,instant(requireTime(mapper.now())),null)));
            if(initial.missingDocumentCount>0)return failed("memory_search_projection_missing",query.queryId());
            boolean incomplete=query.retrievalPolicy().embeddingModel().isPresent() && initial.readyVectorCount<initial.visibleDocumentCount;
            QueryVector vector=incomplete?new QueryVector(null,"memory_vector_coverage_incomplete"):
                    query.retrievalPolicy().embeddingModel().isPresent() && initial.visibleDocumentCount>0 ? queryVector(call) : new QueryVector(null,null);
            if(vector.reason!=null && query.retrievalPolicy().vectorFailurePolicy()==MemoryRetrievalPolicy.VectorFailurePolicy.FAIL)
                return failed(vector.reason,query.queryId());
            return transaction(tenant, mapper -> {
                Instant evaluatedAt=instant(requireTime(mapper.now()));
                MemorySearchFilter filter=searchFilter(call,evaluatedAt,vector.text);
                MemorySearchStatsRow stats=mapper.searchStats(filter);
                if(stats.missingDocumentCount>0)throw new CallRejected("memory_search_projection_missing");
                boolean coverageChanged=query.retrievalPolicy().embeddingModel().isPresent() && stats.readyVectorCount<stats.visibleDocumentCount;
                if(coverageChanged && query.retrievalPolicy().vectorFailurePolicy()==MemoryRetrievalPolicy.VectorFailurePolicy.FAIL)
                    throw new CallRejected("memory_vector_coverage_incomplete");
                String effectiveReason=coverageChanged?"memory_vector_coverage_incomplete":vector.reason;
                String effectiveVector=coverageChanged?null:vector.text;
                List<MemoryRankFusion.Hit> lexical=filter.queryLexemes().isEmpty()?List.of():mapper.lexicalSearch(filter).stream()
                        .map(row->new MemoryRankFusion.Hit(row.factId,row.revision,row.score)).toList();
                List<MemoryRankFusion.Hit> semantic=effectiveVector==null?List.of():mapper.vectorSearch(filter).stream()
                        .map(row->new MemoryRankFusion.Hit(row.factId,row.revision,row.score)).toList();
                var fused=MemoryRankFusion.fuse(lexical,semantic,query.retrievalPolicy().rrfRankConstant(),query.retrievalPolicy().candidateLimit());
                List<ScoredFact> ranked=new ArrayList<>();
                List<MemoryRetrievalDiagnostics.Candidate> diagnostics=new ArrayList<>();
                var facts=findCurrent(mapper,fused.stream()
                        .map(hit->new CurrentFactRead(call.context(),new MemoryFactRef(tenant,hit.factId()))).toList());
                var keys=facts.stream().flatMap(Optional::stream).map(fact->documentKey(fact,query.retrievalPolicy())).toList();
                Map<String,MemorySearchDocumentRow> documents=new HashMap<>();
                if(!keys.isEmpty())for(var document:mapper.searchDocuments(tenant.value(),keys))documents.put(document.factId,document);
                for(int index=0;index<fused.size();index++){
                    var hit=fused.get(index);
                    var fact=facts.get(index).orElse(null);
                    if(fact==null || fact.revision()!=hit.revision() || !visible(call.context(),query,fact,evaluatedAt))continue;
                    var document=documents.get(fact.ref().factId());
                    if(document==null || !digest(document.searchText).equals(document.textDigest))throw invalid();
                    ranked.add(new ScoredFact(fact,hit.fusedScore(),document.searchText));
                    diagnostics.add(new MemoryRetrievalDiagnostics.Candidate(fact.ref(),fact.revision(),
                            hit.lexical().map(MemoryRankFusion.Leg::score),hit.lexical().map(MemoryRankFusion.Leg::rank),
                            hit.vector().map(MemoryRankFusion.Leg::score),hit.vector().map(MemoryRankFusion.Leg::rank),hit.fusedScore()));
                }
                List<MemoryFact> selected=budget(query,ranked);
                if(!callAllowed(call.context()))throw new CallRejected("memory_query_cancelled");
                var mode=query.retrievalPolicy().embeddingModel().isPresent() && effectiveReason==null?MemoryRetrievalDiagnostics.Mode.HYBRID:MemoryRetrievalDiagnostics.Mode.LEXICAL;
                return new MemoryQueryOutcome.Retrieved(new MemoryQueryResult(query.queryId(),selected,
                        new ResultRef("memory-query:"+digest(query.queryId()+"\u0000"+evaluatedAt+"\u0000"+selected.stream().map(f->f.ref().factId()+":"+f.revision()).reduce("",(left,right)->left+"\u0000"+right))),
                        evaluatedAt,new MemoryRetrievalDiagnostics(mode,Optional.ofNullable(effectiveReason),stats.visibleDocumentCount,stats.readyVectorCount,diagnostics,selected.stream().map(f->f.ref().factId()).toList())));
            });
        }catch(CallRejected rejected){return failed(rejected.reason,query.queryId());}
        catch(RuntimeException failure){return failed("memory_store_read_failure",query.queryId());}
    }

    private MemorySearchFilter searchFilter(MemoryQueryCall call,Instant evaluatedAt,String vector){
        var context=call.context();var run=context.runIdentity();var owner=run.owner();var q=call.query();var p=q.retrievalPolicy();var model=p.embeddingModel();
        Instant cutoff=q.asOf().minus(RECENT_WINDOW);
        return new MemorySearchFilter(owner.sessionRef().tenantId().value(),userId(context),run.agentId().value(),owner.sessionRef().sessionId(),groupId(context),
                owner.target().kind().name(),owner.target().id(),q.scopes().stream().map(Enum::name).toList(),querySubject(context,q),q.types().stream().map(MemoryTypeRef::value).toList(),
                q.asOf().getEpochSecond(),q.asOf().getNano(),evaluatedAt.getEpochSecond(),evaluatedAt.getNano(),q.taskConstraint().map(value->value.taskId()).orElse(null),
                q.profile()==MemoryQueryProfile.RECENT_EPISODIC,cutoff.getEpochSecond(),cutoff.getNano(),p.projectionVersion(),p.analyzerVersion(),
                MemorySearchAnalyzer.queryLexemes(MemorySearchAnalyzer.tokens(q.intent())),model.map(EmbeddingModel::modelConfigId).orElse(null),
                model.map(EmbeddingModel::revision).orElse(null),model.map(EmbeddingModel::dimensions).orElse(null),vector,p.minimumCosineSimilarity(),p.candidateLimit());
    }

    private static MemorySearchDocumentRow documentKey(MemoryFact fact,MemoryRetrievalPolicy policy){
        var row=new MemorySearchDocumentRow();row.tenantId=fact.ref().tenantId().value();row.factId=fact.ref().factId();row.contentRevision=1;
        row.projectionVersion=policy.projectionVersion();row.analyzerVersion=policy.analyzerVersion();return row;
    }

    private void registerSearchDocument(MemoryMapper mapper,PortCallContext context,MemoryFact fact){
        registerSearchDocument(mapper,context,fact,Objects.requireNonNull(indexingPolicy.apply(fact.ref().tenantId())));
    }

    private void registerSearchDocument(MemoryMapper mapper,PortCallContext context,MemoryFact fact,MemoryRetrievalPolicy policy){
        if(fact.status()!=FactStatus.ACTIVE && fact.status()!=FactStatus.PENDING_CONFIRM)return;
        var projected=Objects.requireNonNull(projector.apply(fact),"memory_search_projection_missing");
        if(!projected.projectionVersion().equals(policy.projectionVersion()) || !MemorySearchAnalyzer.VERSION.equals(policy.analyzerVersion()))
            throw new IllegalArgumentException("memory_search_projection_version_mismatch");
        var row=documentKey(fact,policy);row.searchText=projected.text();row.textDigest=digest(projected.text());
        row.analyzedText=String.join(" ",MemorySearchAnalyzer.tokens(row.searchText));
        row.runData=MemoryJdbcCodec.encodeRun(context.runIdentity());row.identityData=MemoryJdbcCodec.encodeIdentity(context.identity());
        mapper.insertSearchDocument(row);
        var stored=mapper.searchDocument(row);
        if(stored==null || !stored.searchText.equals(row.searchText) || !stored.textDigest.equals(row.textDigest))throw invalid();
        if(policy.embeddingModel().isPresent()){
            var model=policy.embeddingModel().orElseThrow();
            String invocation="memory-index:"+stableDigest(row.tenantId,row.factId,"1",row.projectionVersion,row.analyzerVersion,model.modelConfigId(),Long.toString(model.revision()),Integer.toString(model.dimensions()),row.textDigest);
            mapper.enqueueEmbedding(row,model,invocation);
        }
    }

    /** 实际派发前固定原任务与fence；过期RUNNING只收敛UNKNOWN，不重领再调用。 */
    public Optional<MemoryEmbeddingWork> claimEmbedding(TenantId tenant,String workerId,Duration lease){
        Objects.requireNonNull(workerId);if(workerId.isBlank() || lease.isNegative() || lease.isZero())throw new IllegalArgumentException("invalid_memory_index_lease");
        return transaction(tenant,mapper->{
            mapper.expireEmbeddings(tenant.value());
            var row=mapper.claimEmbedding(tenant.value(),workerId+":"+java.util.UUID.randomUUID(),lease.toMillis()/1000.0);
            if(row==null)return Optional.empty();
            return Optional.of(new MemoryEmbeddingWork(tenant,row.factId,row.contentRevision,row.projectionVersion,row.analyzerVersion,
                    new EmbeddingModel(row.modelId,row.modelRevision,row.dimensions),row.textDigest,row.searchText,row.invocationId,row.leaseToken,row.leaseUntil,
                    MemoryJdbcCodec.decodeRun(row.runData),MemoryJdbcCodec.decodeIdentity(row.identityData)));
        });
    }

    public boolean completeEmbedding(MemoryEmbeddingWork work,List<Double> vector){
        String encoded=vectorText(vector,work.model().dimensions());
        return transaction(work.tenantId(),mapper->{
            if(!mapper.embeddingSourceActive(work)){
                mapper.completeEmbedding(work,"REJECTED",null,"memory_index_source_inactive");return false;
            }
            return mapper.completeEmbedding(work,"READY",encoded,null)==1;
        });
    }

    public boolean failEmbedding(MemoryEmbeddingWork work,String reasonCode,boolean unknown){
        Objects.requireNonNull(reasonCode);if(reasonCode.isBlank())throw new IllegalArgumentException("reasonCode");
        return transaction(work.tenantId(),mapper->mapper.completeEmbedding(work,unknown?"UNKNOWN":"REJECTED",null,reasonCode)==1);
    }

    /** 策略切换显式补登记，不覆盖旧版本投影；原身份只从已持久正文投影继承。 */
    public int enqueueMissing(TenantId tenant,MemoryRetrievalPolicy policy,int limit){
        if(limit<=0)throw new IllegalArgumentException("limit");
        return transaction(tenant,mapper->{
            int count=0;
            for(var candidate:mapper.rebuildCandidates(tenant.value(),policy,policy.embeddingModel().orElse(null),limit)){
                if((candidate.runData==null || candidate.identityData==null) && candidate.commandData==null)throw new CallRejected("memory_search_original_identity_missing");
                var row=mapper.findRevision(tenant.value(),candidate.factId,candidate.revision);
                if(row==null)throw invalid();var fact=restore(mapper,row);
                var context=candidate.runData==null?MemoryJdbcCodec.decodeCommandContext(candidate.commandData):
                        new PortCallContext(MemoryJdbcCodec.decodeRun(candidate.runData),MemoryJdbcCodec.decodeIdentity(candidate.identityData),
                        new com.skillforge.runtime.api.port.InvocationControl(Instant.now().plusSeconds(60),()->false));
                if(!owns(context,fact))throw invalid();
                registerSearchDocument(mapper,context,fact,policy);count++;
            }
            return count;
        });
    }

    private static String queryOperationId(MemoryQueryCall call){
        return stableDigest(call.context().runIdentity().runRef().tenantId().value(),call.context().runIdentity().runRef().runId(),
                HexFormat.of().formatHex(MemoryJdbcCodec.encodeOwnerIdentity(call.query().owner())),call.query().queryId());
    }

    private QueryVector queryVector(MemoryQueryCall call){
        var q=call.query();var model=q.retrievalPolicy().embeddingModel().orElseThrow();var tenant=q.tenantId();
        String operation=queryOperationId(call);
        String fingerprint=digest(MemoryJdbcCodec.encodeQuery(call));String invocation="memory-query:"+operation;
        boolean created=transaction(tenant,mapper->{mapper.expireQueryEmbeddings(tenant.value(),operation);
            return mapper.insertQueryEmbedding(tenant.value(),operation,fingerprint,invocation,model,
                    MemoryJdbcCodec.encodeRun(call.context().runIdentity()),MemoryJdbcCodec.encodeIdentity(call.context().identity()),call.context().control().deadlineAt())==1;});
        if(created){
            MemorySearchEmbedding.Outcome outcome;
            try{outcome=Objects.requireNonNull(embedding.embed(new MemorySearchEmbedding.Request(call.context(),invocation,model,q.intent())));}
            catch(RuntimeException unknown){outcome=new MemorySearchEmbedding.Outcome.OutcomeUnknown("memory_query_embedding_outcome_unknown");}
            String state,vector=null,reason=null;
            if(outcome instanceof MemorySearchEmbedding.Outcome.Available available){
                try{vector=vectorText(available.vector(),model.dimensions());state="READY";}
                catch(RuntimeException invalid){state="REJECTED";reason="memory_embedding_vector_invalid";}
            }else if(outcome instanceof MemorySearchEmbedding.Outcome.Rejected rejected){state="REJECTED";reason=rejected.reasonCode();}
            else{state="UNKNOWN";reason=((MemorySearchEmbedding.Outcome.OutcomeUnknown)outcome).reasonCode();}
            final String finalState=state,finalVector=vector,finalReason=reason;
            transaction(tenant,mapper->mapper.finishQueryEmbedding(tenant.value(),operation,fingerprint,finalState,finalVector,finalReason));
        }
        var row=transaction(tenant,mapper->mapper.queryEmbedding(tenant.value(),operation));
        if(row==null)throw invalid();
        if(!fingerprint.equals(row.fingerprint))throw new CallRejected("memory_query_embedding_request_conflict");
        return "READY".equals(row.state)?new QueryVector(row.vectorText,null):new QueryVector(null,row.reasonCode==null?"memory_query_embedding_outcome_unknown":row.reasonCode);
    }

    private static String vectorText(List<Double> vector,int dimensions){
        if(vector==null || vector.size()!=dimensions)throw new IllegalArgumentException("memory_embedding_dimension_mismatch");
        double norm=0;for(Double value:vector){if(value==null || !Double.isFinite(value) || !Float.isFinite(value.floatValue()))throw new IllegalArgumentException("memory_embedding_non_finite");norm+=(double)value.floatValue()*value.floatValue();}
        if(!Double.isFinite(norm)||norm==0)throw new IllegalArgumentException("memory_embedding_zero_vector");
        return vector.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",","[","]"));
    }

    private static String stableDigest(String... values){
        try{var bytes=new java.io.ByteArrayOutputStream();var output=new java.io.DataOutputStream(bytes);
            for(String value:values){byte[] encoded=value.getBytes(StandardCharsets.UTF_8);output.writeInt(encoded.length);output.write(encoded);}output.close();return digest(bytes.toByteArray());
        }catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
    }

    @Override
    public MemoryMutationResult submit(MemoryCommand command) {
        Objects.requireNonNull(command, "command");
        PortCallContext context = context(command);
        String key = key(command);
        if (!callAllowed(context)) {
            return rejected("memory_mutation_cancelled", key);
        }
        byte[] commandData;
        try {
            commandData = MemoryJdbcCodec.encodeCommand(command);
        } catch (RuntimeException invalid) {
            return rejected("memory_command_encoding_rejected", key);
        }
        TenantId tenant = context.runIdentity().owner().sessionRef().tenantId();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactionWithSession(tenant, (mapper, session) -> mutate(
                        mapper, tenant, command, commandData, session));
            } catch (RuntimeException failure) {
                if (attempt < 2 && retryable(failure)) continue;
                return rejected("memory_store_write_failure", key);
            }
        }
        return rejected("memory_store_write_failure", key);
    }

    /** 每项使用独立 owner 条件，来源按精确 revision 集合读取后复用完整还原校验。 */
    @Override
    public List<Optional<MemoryFact>> findCurrent(CurrentFactReadBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.reads().isEmpty()) return List.of();
        var tenant = batch.reads().getFirst().fact().tenantId();
        try {
            return transaction(tenant, mapper -> findCurrent(mapper, batch.reads()));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("memory_current_fact_read_failure", failure);
        }
    }

    private List<Optional<MemoryFact>> findCurrent(MemoryMapper mapper, List<CurrentFactRead> reads) {
        if (reads.isEmpty()) return List.of();
        var tenant = reads.getFirst().fact().tenantId();
        List<MemoryCurrentFactRequest> requests = new ArrayList<>(reads.size());
        for (int index = 0; index < reads.size(); index++) {
            var read = reads.get(index);
            var run = read.context().runIdentity();
            var owner = run.owner();
            requests.add(new MemoryCurrentFactRequest(index, read.fact().factId(),
                    userId(read.context()), run.agentId().value(), owner.sessionRef().sessionId(), groupId(read.context()), owner.target().kind().name(), owner.target().id()));
        }
        List<MemoryFactRow> rows = mapper.findCurrentBatch(tenant.value(), requests);
        MemoryFactRow[] ordered = new MemoryFactRow[requests.size()];
        Map<FactRevision, MemoryFactRow> revisions = new LinkedHashMap<>();
        for (MemoryFactRow row : rows) {
            int ordinal = row.requestOrdinal;
            if (ordinal < 0 || ordinal >= ordered.length || ordered[ordinal] != null
                    || !tenant.value().equals(row.tenantId)
                    || !requests.get(ordinal).factId().equals(row.factId)) throw invalid();
            ordered[ordinal] = row;
            revisions.putIfAbsent(new FactRevision(row.factId, row.revision), row);
        }
        Map<FactRevision, List<MemorySourceRow>> sources = new HashMap<>();
        if (!revisions.isEmpty()) {
            for (MemorySourceRow source : mapper.findSourcesBatch(tenant.value(), List.copyOf(revisions.values()))) {
                var revision = new FactRevision(source.factId, source.revision);
                if (!tenant.value().equals(source.tenantId) || !revisions.containsKey(revision)) throw invalid();
                sources.computeIfAbsent(revision, ignored -> new ArrayList<>()).add(source);
            }
        }
        List<Optional<MemoryFact>> result = new ArrayList<>(ordered.length);
        for (int index = 0; index < ordered.length; index++) {
            MemoryFactRow row = ordered[index];
            if (row == null) {
                result.add(Optional.empty());
            } else {
                MemoryFact fact = restore(row, sources.getOrDefault(new FactRevision(row.factId, row.revision), List.of()));
                if (!owns(reads.get(index).context(), fact)) throw invalid();
                result.add(Optional.of(fact));
            }
        }
        return List.copyOf(result);
    }

    /** 在原派发事务中锁住事实head，遗忘与实际派发形成明确先后，不另开连接。 */
    public List<Optional<MemoryFact>> findCurrent(SqlSession session, CurrentFactReadBatch batch) {
        Objects.requireNonNull(session); Objects.requireNonNull(batch);
        configureBorrowed(session.getConfiguration());
        var mapper = session.getMapper(MemoryMapper.class);
        var reads = batch.reads().stream().sorted(java.util.Comparator.comparing(read -> read.fact().factId())).toList();
        for (var read : reads) {
            if (!read.fact().tenantId().equals(read.context().runIdentity().runRef().tenantId())) throw invalid();
            mapper.lockHeadForRead(read.fact().tenantId().value(), read.fact().factId());
        }
        var result = new ArrayList<Optional<MemoryFact>>();
        for (var read : batch.reads()) result.add(Optional.ofNullable(current(mapper, read.context(), read.fact(), false)));
        return List.copyOf(result);
    }

    private static void configureBorrowed(Configuration configuration) {
        synchronized (configuration) {
            if (configuration.hasMapper(MemoryMapper.class)) return;
            String resource = "mapper/AgentRuntimeMemoryMapper.xml";
            try (var stream = JdbcMemoryPort.class.getClassLoader().getResourceAsStream(resource)) {
                if (stream == null) throw new IllegalStateException("memory_mapper_missing");
                new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
            } catch (java.io.IOException failure) { throw new IllegalStateException("memory_mapper_invalid", failure); }
        }
    }

    /** 供 Starter 以 method reference 装配 MemoryRuntime.CurrentFactSource。 */
    public Optional<MemoryFact> findCurrent(PortCallContext context, MemoryFactRef ref) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(ref, "ref");
        if (!ref.tenantId().equals(context.runIdentity().owner().sessionRef().tenantId())) {
            return Optional.empty();
        }
        try {
            return transaction(ref.tenantId(), mapper -> Optional.ofNullable(
                    current(mapper, context, ref, false)));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("memory_current_fact_read_failure", failure);
        }
    }

    /** 仅确认原journal是否存在；指纹和当前授权仍由随后原命令的Runtime重放验证。 */
    public boolean hasRecordedMutation(PortCallContext context, IdempotencyKey idempotencyKey) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (!context.identity().owner().equals(context.runIdentity().owner()))
            throw new IllegalArgumentException("memory_operation_owner_mismatch");
        if (!callAllowed(context)) throw new IllegalStateException("memory_operation_read_cancelled");
        TenantId tenant = context.runIdentity().owner().sessionRef().tenantId();
        return transaction(tenant, mapper -> {
            boolean recorded = mapper.findOperation(tenant.value(), idempotencyKey.value()) != null;
            if (!callAllowed(context)) throw new IllegalStateException("memory_operation_read_cancelled");
            return recorded;
        });
    }

    /** 冻结提取别名只读取原不可变revision；不由当前head替换历史内容。 */
    public Optional<MemoryFact> findRevision(PortCallContext context, MemoryFactRef ref, long revision) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(ref, "ref");
        if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
        if (!context.identity().owner().equals(context.runIdentity().owner()))
            throw new IllegalArgumentException("memory_revision_owner_mismatch");
        if (!ref.tenantId().equals(context.runIdentity().owner().sessionRef().tenantId())) return Optional.empty();
        if (!callAllowed(context)) throw new IllegalStateException("memory_revision_read_cancelled");
        return transaction(ref.tenantId(), mapper -> {
            var row = mapper.findRevision(ref.tenantId().value(), ref.factId(), revision);
            if (row == null) return Optional.empty();
            MemoryFact fact = restore(mapper, row);
            if (!callAllowed(context)) throw new IllegalStateException("memory_revision_read_cancelled");
            return owns(context, fact) ? Optional.of(fact) : Optional.empty();
        });
    }

    /**
     * 治理写入前精确检查同 scope/type/subject/predicate 的当前事实冲突。
     * 该检查不经过相关性排名和查询预算，不能用有限召回结果证明“无冲突”。
     */
    public boolean hasConflictingCurrentFact(PortCallContext context, MemoryScope scope,
            MemoryTypeRef type, String subject, String predicate,
            String objectCanonicalJson) {
        MemoryOwner owner = switch (scope) {
            case SESSION -> new MemoryOwner.Session(context.runIdentity().owner().sessionRef());
            case USER_AGENT -> new MemoryOwner.UserAgent(context.runIdentity().owner().sessionRef().tenantId(), context.runIdentity().owner().requireUser(), context.runIdentity().agentId());
            case USER -> new MemoryOwner.User(context.runIdentity().owner().sessionRef().tenantId(), context.runIdentity().owner().requireUser());
            case GROUP -> new MemoryOwner.Group(((SessionSubject.Group) context.runIdentity().owner().subject()).groupBindingRef());
            case GROUP_MEMBER -> throw new IllegalArgumentException("group member conflict check requires exact MemoryOwner");
        };
        return hasConflictingCurrentFact(context, owner, type, subject, predicate, objectCanonicalJson);
    }

    /** 冲突检查使用精确MemoryOwner，不能把另一成员的事实当成本对象冲突。 */
    public boolean hasConflictingCurrentFact(PortCallContext context, MemoryOwner memoryOwner,
            MemoryTypeRef type, String subject, String predicate, String objectCanonicalJson) {
        MemoryScope scope = memoryOwner.scope();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(objectCanonicalJson, "objectCanonicalJson");
        if (!callAllowed(context)) throw new IllegalStateException("memory_conflict_check_cancelled");
        TenantId tenant = context.runIdentity().owner().sessionRef().tenantId();
        try {
            return transaction(tenant, mapper -> {
                Instant evaluatedAt = instant(requireTime(mapper.now()));
                var run = context.runIdentity();
                var owner = run.owner();
                for (MemoryFactRow row : mapper.listCurrent(tenant.value(),
                        userId(context), run.agentId().value(),
                        owner.sessionRef().sessionId(), groupId(context), owner.target().kind().name(), owner.target().id(), List.of(scope.name()),
                        memoryOwner instanceof MemoryOwner.GroupMember member ? member.subjectEndUserId().value() : null, null)) {
                    MemoryFact fact = restore(mapper, row);
                    if (fact.owner().equals(memoryOwner) && currentForConflict(context, scope, type, fact, evaluatedAt)
                            && fact.subject().equals(subject)
                            && fact.predicate().equals(predicate)
                            && !fact.object().canonicalJson().equals(objectCanonicalJson)) {
                        return true;
                    }
                }
                if (!callAllowed(context)) {
                    throw new CallRejected("memory_conflict_check_cancelled");
                }
                return false;
            });
        } catch (RuntimeException failure) {
            throw new IllegalStateException("memory_conflict_check_failure", failure);
        }
    }

    /** 模型调用完成后，以业务槽位串行确定候选关系与提交；不将候选内容摘要当成槽位。 */
    public <T> T withExtractionSlot(PortCallContext context, MemoryOwner owner,
            MemoryTypeRef type, String subject, String predicate, java.util.function.Supplier<T> callback) {
        requireRelatedOwner(context, owner);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(callback, "callback");
        // 同一原来源可以被重新解释成不同槽位；以 owner 覆盖槽位锁，不为每层 callback 再占一条连接。
        return withGovernanceOwner(context, owner, callback);
    }

    private <T> T withGovernanceOwner(PortCallContext context, MemoryOwner owner,
            java.util.function.Supplier<T> callback) {
        String key = ownerKey(owner);
        if (key.equals(heldGovernanceOwner.get())) return callback.get();
        if (heldGovernanceOwner.get() != null) throw new IllegalStateException("memory_governance_owner_nested");
        return transaction(owner.tenantId(), mapper -> {
            mapper.lockExtraction(owner.tenantId().value(), key);
            heldGovernanceOwner.set(key);
            try {
                if (!callAllowed(context)) throw new CallRejected("memory_extraction_slot_cancelled");
                return callback.get();
            } finally {
                heldGovernanceOwner.remove();
            }
        });
    }

    private static String ownerKey(MemoryOwner owner) {
        String identity = switch (owner) {
            case MemoryOwner.Session value -> stableDigest(value.sessionRef().sessionId());
            case MemoryOwner.UserAgent value -> stableDigest(Long.toString(value.endUserId().value()), value.agentId().value());
            case MemoryOwner.User value -> stableDigest(Long.toString(value.endUserId().value()));
            case MemoryOwner.Group value -> stableDigest(value.groupBindingRef().groupBindingId());
            case MemoryOwner.GroupMember value -> stableDigest(value.groupBindingRef().groupBindingId(), Long.toString(value.subjectEndUserId().value()));
        };
        return stableDigest("memory-governance-owner.v1", owner.tenantId().value(), owner.scope().name(), identity);
    }

    /** 已治理来源的持久判据来自不可变修订，不依赖候选 ID、任务或提取器版本。 */
    public ExtractionGovernance extractionGovernance(PortCallContext context, MemoryFactDraft draft) {
        requireRelatedOwner(context, draft.owner());
        return transaction(draft.owner().tenantId(), mapper -> extractionGovernance(mapper, context, draft));
    }

    private ExtractionGovernance extractionGovernance(MemoryMapper mapper, PortCallContext context,
            MemoryFactDraft draft) {
        var owner = draft.owner();
        MemoryFactRow ownerRow = row(fromDraft(new MemoryFactRef(owner.tenantId(), "governance-probe"), draft, Instant.EPOCH));
        var target = context.runIdentity().owner().target();
        List<MemoryFactRow> histories = mapper.governedHistory(ownerRow, target.kind().name(), target.id());
        ExtractionGovernance result = ExtractionGovernance.ALLOWED;
        List<MemorySourcePositionRow> positions = null;
        for (MemoryFactRow governedRow : histories) {
            MemoryFact governed = restore(mapper, governedRow);
            if (!governed.owner().equals(owner)) throw invalid();
            boolean forgotten = governed.status() == FactStatus.FORGOTTEN;
            for (MemorySourceRow source : mapper.governedSources(owner.tenantId().value(), governed.ref().factId(), governed.revision())) {
                if (source.sourceSessionId.equals(draft.sourceSessionRef().sessionId())
                        && source.sourceTargetKind.equals(target.kind().name()) && source.sourceTargetId.equals(target.id())
                        && draft.sourceEventIds().stream().anyMatch(event -> event.value().equals(source.sourceEventId))) {
                    return forgotten ? ExtractionGovernance.FORGOTTEN_SOURCE : ExtractionGovernance.SUPERSEDED_SOURCE;
                }
            }
            // 偏好的同槽按宿主稳定 subject/predicate 控制时序；独立情节只抑制原来源，不做语义相似判断。
            if (!"preference".equals(draft.type().value()) || !governed.type().equals(draft.type()) || !governed.subject().equals(draft.subject())
                    || !governed.predicate().equals(draft.predicate())) continue;
            if (positions == null) {
                positions = mapper.sourcePositions(owner.tenantId().value(), context.runIdentity().agentId().value(),
                        target.kind().name(), target.id(), draft.sourceSessionRef().sessionId(),
                        draft.sourceEventIds().stream().map(EventId::value).toList());
                if (positions.size() != draft.sourceEventIds().size())
                    throw new CallRejected("memory_extraction_source_position_missing");
            }
            if (governedRow.governanceRecordedAt == null) throw invalid();
            if (positions.stream().anyMatch(source -> !Instant.ofEpochSecond(source.epochSecond, source.nano)
                    .isAfter(governedRow.governanceRecordedAt))) {
                return forgotten ? ExtractionGovernance.FORGOTTEN_SOURCE : ExtractionGovernance.SUPERSEDED_SOURCE;
            }
            if (forgotten) result = ExtractionGovernance.FORGOTTEN_SLOT_REVIEW;
        }
        return result;
    }

    public enum ExtractionGovernance {
        ALLOWED(null),
        FORGOTTEN_SOURCE("memory_forgotten_source_suppressed"),
        SUPERSEDED_SOURCE("memory_superseded_source_suppressed"),
        FORGOTTEN_SLOT_REVIEW("memory_forgotten_slot_confirmation_required");

        private final String reasonCode;
        ExtractionGovernance(String reasonCode) { this.reasonCode = reasonCode; }
        public String reasonCode() {
            if (reasonCode == null) throw new IllegalStateException("memory_allowed_has_no_rejection");
            return reasonCode;
        }
    }

    /** 精确槽位保留历史和未来业务区间，由宿主比较区间和适用条件。 */
    public List<MemoryFact> relatedCurrentFacts(PortCallContext context, MemoryOwner owner,
            MemoryTypeRef type, String subject, String predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return relatedCurrentFacts(context, owner, type, subject, Optional.of(predicate), Integer.MAX_VALUE, false);
    }

    /** 提取输入只冻结有界、当前有效的已有事实别名。 */
    public List<MemoryFact> relatedCurrentFacts(PortCallContext context, MemoryOwner owner,
            MemoryTypeRef type, String subject, int limit) {
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("memory_related_limit_invalid");
        return relatedCurrentFacts(context, owner, type, subject, Optional.empty(), limit, true);
    }

    private List<MemoryFact> relatedCurrentFacts(PortCallContext context, MemoryOwner owner,
            MemoryTypeRef type, String subject, Optional<String> predicate, int limit, boolean effectiveNow) {
        requireRelatedOwner(context, owner);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        return transaction(owner.tenantId(), mapper -> {
            Instant now = instant(requireTime(mapper.now()));
            var sessionOwner = context.runIdentity().owner();
            List<MemoryFact> result = new ArrayList<>();
            for (var row : mapper.listCurrent(owner.tenantId().value(), userId(context),
                    context.runIdentity().agentId().value(), sessionOwner.sessionRef().sessionId(), groupId(context),
                    sessionOwner.target().kind().name(), sessionOwner.target().id(), List.of(owner.scope().name()),
                    owner instanceof MemoryOwner.GroupMember member ? member.subjectEndUserId().value() : null, null)) {
                MemoryFact fact = restore(mapper, row);
                if (fact.owner().equals(owner) && fact.type().equals(type) && fact.subject().equals(subject)
                        && predicate.map(value -> value.equals(fact.predicate())).orElse(true)
                        && fact.status() == FactStatus.ACTIVE && fact.supersededBy().isEmpty()
                        && (!effectiveNow || currentForConflict(context, owner.scope(), type, fact, now))) result.add(fact);
            }
            if (!callAllowed(context)) throw new CallRejected("memory_related_read_cancelled");
            return result.stream().sorted(java.util.Comparator.comparing((MemoryFact fact) -> fact.ref().factId()))
                    .limit(limit).toList();
        });
    }

    private static void requireRelatedOwner(PortCallContext context, MemoryOwner owner) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(owner, "owner");
        boolean allowed = owner.tenantId().equals(context.runIdentity().owner().sessionRef().tenantId())
                && context.identity().owner().equals(context.runIdentity().owner())
                && switch (owner) {
                    case MemoryOwner.Session value -> value.sessionRef().equals(context.runIdentity().owner().sessionRef());
                    case MemoryOwner.UserAgent value -> groupId(context) == null && Objects.equals(userId(context), value.endUserId().value()) && value.agentId().equals(context.runIdentity().agentId());
                    case MemoryOwner.User value -> groupId(context) == null && Objects.equals(userId(context), value.endUserId().value());
                    case MemoryOwner.Group value -> value.groupBindingRef().groupBindingId().equals(groupId(context));
                    case MemoryOwner.GroupMember value -> value.groupBindingRef().groupBindingId().equals(groupId(context));
                };
        if (!allowed) throw new IllegalArgumentException("memory_related_owner_mismatch");
        if (!callAllowed(context)) throw new CallRejected("memory_related_call_cancelled");
    }

    /**
     * 串行同 scope 的自动偏好提取复用或创建。调用方必须先通过现有类型、访问与来源策略。
     *
     * <p>锁由本方法连接持有；callback 创建仍由 MemoryRuntime 的独立连接事务提交，模型调用
     * 必须发生在此方法之前。来源支持关系只在精确复用时写入，不冒充 CREATE 或 memory.written。
     * 原来源重放交给 callback，以继续修复原 Runtime 日志 ACK；失败不会重放 UNKNOWN 外部操作。
     */
    public <T> T reuseOrCreateExtractedPreference(PortCallContext context, MemoryFactDraft draft,
            ResultRef accessAudit, ResultRef sourceAudit, Function<Optional<MemoryFact>, T> callback) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(accessAudit, "accessAudit");
        Objects.requireNonNull(sourceAudit, "sourceAudit");
        Objects.requireNonNull(callback, "callback");
        if (!"preference".equals(draft.type().value()) || draft.reviewRequired()
                || draft.initialStatus() != FactStatus.ACTIVE
                || draft.exportPath().isPresent() || draft.sourceEventIds().size() != 1
                || !context.identity().owner().equals(context.runIdentity().owner())
                || !context.identity().scopes().contains("memory:extract")) {
            throw new IllegalArgumentException("memory_extraction_reuse_contract_invalid");
        }
        TenantId tenant = context.runIdentity().owner().sessionRef().tenantId();
        MemoryFact probe = fromDraft(new MemoryFactRef(tenant, "extraction-probe"), draft, Instant.EPOCH);
        if (!owns(context, probe) || !draft.sourceSessionRef().equals(context.runIdentity().owner().sessionRef())) {
            throw new IllegalArgumentException("memory_extraction_reuse_owner_invalid");
        }
        String semantic = digest(MemoryJdbcCodec.encodeExtractionIdentity(draft));
        return withGovernanceOwner(context, draft.owner(), () -> transaction(tenant, mapper -> {
            mapper.lockExtraction(tenant.value(), semantic);
            if (!callAllowed(context)) throw new CallRejected("memory_extraction_reuse_cancelled");
            var target = context.runIdentity().owner().target();
            String event = draft.sourceEventIds().getFirst().value();
            var evidence = mapper.findExtractionEvidence(tenant.value(), semantic,
                    target.kind().name(), target.id(), draft.sourceSessionRef().sessionId(), event,
                    draft.extractorVersion());
            if (evidence != null) {
                MemoryFactRow prior = mapper.findRevision(tenant.value(), evidence.factId, evidence.revision);
                if (prior == null) throw invalid();
                MemoryFact fact = restore(mapper, prior);
                if (!owns(context, fact) || !equivalentExtraction(draft, fact)) throw invalid();
                return callback.apply(Optional.of(fact));
            }
            Instant evaluatedAt = instant(requireTime(mapper.now()));
            var owner = context.runIdentity().owner();
            MemoryFactRow row = mapper.findEquivalentExtraction(tenant.value(), semantic,
                    userId(context), context.runIdentity().agentId().value(),
                    owner.sessionRef().sessionId(), owner.target().kind().name(), owner.target().id(), draft.scope().name(), evaluatedAt.getEpochSecond(), evaluatedAt.getNano());
            if (row == null) return callback.apply(Optional.empty());
            MemoryFact fact = restore(mapper, row);
            if (!currentForConflict(context, draft.scope(), draft.type(), fact, evaluatedAt)
                    || !equivalentExtraction(draft, fact)) throw invalid();
            if (fact.sourceSessionRef().equals(draft.sourceSessionRef())
                    && fact.sourceEventIds().equals(draft.sourceEventIds())) {
                return callback.apply(Optional.empty());
            }
            if (mapper.insertExtractionEvidence(tenant.value(), semantic, context.runIdentity().agentId().value(),
                    target.kind().name(), target.id(), draft.sourceSessionRef().sessionId(), event,
                    draft.extractorVersion(), fact.ref().factId(), fact.revision(),
                    accessAudit.value(), sourceAudit.value()) != 1) throw invalid();
            return callback.apply(Optional.of(fact));
        }));
    }

    /** 复用只承认精确属性相等；不合并情节、时间变化、来源导出或更高置信度的不同事实。 */
    private static boolean equivalentExtraction(MemoryFactDraft draft, MemoryFact fact) {
        return Arrays.equals(MemoryJdbcCodec.encodeExtractionIdentity(draft),
                MemoryJdbcCodec.encodeExtractionIdentity(fact))
                && fact.derivedFrom().isEmpty() && fact.supersededBy().isEmpty()
                && draft.derivedFrom().isEmpty() && draft.validTo().equals(fact.validTo())
                && draft.expiresAt().equals(fact.expiresAt());
    }

    private MemoryMutationResult mutate(MemoryMapper mapper, TenantId tenant,
            MemoryCommand command, byte[] commandData, SqlSession session) {
        MemoryOwner mutationOwner = switch (command) {
            case MemoryCommand.Create value -> value.fact().owner();
            case MemoryCommand.Supersede value -> value.replacement().owner();
            case MemoryCommand.Transition value -> {
                MemoryFact existingFact = current(mapper, value.context(), value.fact(), false);
                yield existingFact == null ? null : existingFact.owner();
            }
            case MemoryCommand.AcceptRevision value -> {
                MemoryFact existingFact = current(mapper, value.context(), value.current(), false);
                yield existingFact == null ? null : existingFact.owner();
            }
        };
        if (mutationOwner != null && !ownerKey(mutationOwner).equals(heldGovernanceOwner.get())) {
            mapper.lockExtraction(tenant.value(), ownerKey(mutationOwner));
        }
        String key = key(command);
        mapper.lockOperation(tenant.value(), key);
        MemoryOperationRow existing = mapper.findOperation(tenant.value(), key);
        if (existing != null) {
            MemoryFact output = requireOperation(mapper, tenant, key, existing);
            boolean sameCommand = existing.commandKind.equals(kind(command))
                    && Arrays.equals(existing.commandData, commandData);
            if (sameCommand) {
                if (!operationMatches(mapper, tenant, command, output)) throw invalid();
                return new MemoryMutationResult.Applied(output, beforeForCommand(mapper, command), true);
            }
            Long head = mapper.headRevision(tenant.value(), output.ref().factId());
            if (head == null || head <= 0) throw invalid();
            return conflict(mapper, tenant, key, output.ref(), head,
                    "memory_idempotency_conflict", commandData);
        }
        MemoryTimeRow recorded = requireTime(mapper.now());
        Mutation mutation = switch (command) {
            case MemoryCommand.Create value -> create(mapper, tenant, value, recorded);
            case MemoryCommand.Supersede value -> supersede(mapper, tenant, value, recorded);
            case MemoryCommand.Transition value -> transition(mapper, tenant, value, recorded, session);
            case MemoryCommand.AcceptRevision value -> acceptRevision(mapper, tenant, value, recorded);
        };
        if (mutation.result != null) {
            registerSearchDocument(mapper,context(command),mutation.result);
            mapper.insertOperation(tenant.value(), key, kind(command), commandData,
                    mutation.result, recorded);
            return new MemoryMutationResult.Applied(mutation.result, mutation.before, false);
        }
        return mutation.failure;
    }

    private Mutation create(MemoryMapper mapper, TenantId tenant,
            MemoryCommand.Create command, MemoryTimeRow recorded) {
        MemoryFactDraft draft = command.fact();
        MemoryFactRef ref = new MemoryFactRef(tenant, factId(tenant, command.idempotencyKey().value()));
        if (mapper.headRevision(tenant.value(), ref.factId()) != null) {
            return Mutation.failed(rejected("memory_fact_identity_conflict", command.idempotencyKey().value()));
        }
        if (command.context().identity().scopes().contains("memory:extract")) {
            ExtractionGovernance governance = extractionGovernance(mapper, command.context(), draft);
            String rejection = switch (governance) {
                case FORGOTTEN_SOURCE, SUPERSEDED_SOURCE -> governance.reasonCode();
                case FORGOTTEN_SLOT_REVIEW -> draft.initialStatus() == FactStatus.PENDING_CONFIRM && draft.reviewRequired()
                        ? null : governance.reasonCode();
                case ALLOWED -> null;
            };
            if (rejection != null) return Mutation.failed(rejected(rejection, key(command)));
        }
        MemoryFact fact = fromDraft(ref, draft, instant(recorded));
        insertFact(mapper, fact, command.context().runIdentity().agentId(),
                command.context().runIdentity().owner().target());
        if (mapper.insertHead(tenant.value(), fact,
                row(fact)) != 1) throw invalid();
        return Mutation.applied(fact, Optional.empty());
    }

    private Mutation supersede(MemoryMapper mapper, TenantId tenant,
            MemoryCommand.Supersede command, MemoryTimeRow recorded) {
        MemoryFact current = current(mapper, command.context(), command.current(), true);
        if (current == null) {
            return Mutation.failed(rejected("memory_current_fact_missing", command.idempotencyKey().value()));
        }
        if (current.revision() != command.expectedRevision()) {
            return Mutation.failed(conflict(mapper, tenant, command.idempotencyKey().value(),
                    current.ref(), current.revision(), "memory_revision_conflict",
                    MemoryJdbcCodec.encodeCommand(command)));
        }
        MemoryFactDraft replacement = command.replacement();
        if (current.revision() == Long.MAX_VALUE || current.status() != FactStatus.ACTIVE
                || current.supersededBy().isPresent() || !replacement.owner().equals(current.owner())
                || !replacement.type().equals(current.type())
                || !replacement.derivedFrom().equals(Optional.of(current.ref()))) {
            return Mutation.failed(rejected("memory_supersede_source_mismatch",
                    command.idempotencyKey().value()));
        }
        MemoryFactRef replacementRef = new MemoryFactRef(tenant,
                factId(tenant, command.idempotencyKey().value()));
        if (mapper.headRevision(tenant.value(), replacementRef.factId()) != null) {
            return Mutation.failed(rejected("memory_fact_identity_conflict",
                    command.idempotencyKey().value()));
        }
        Instant now = instant(recorded);
        MemoryFact replacementFact = fromDraft(replacementRef, replacement, now);
        MemoryFact archived = copy(current, current.revision() + 1, FactStatus.ARCHIVED,
                closeAt(current, now), Optional.of(replacementRef));
        insertFact(mapper, replacementFact, command.context().runIdentity().agentId(),
                command.context().runIdentity().owner().target());
        if (mapper.insertHead(tenant.value(), replacementFact,
                row(replacementFact)) != 1) {
            throw invalid();
        }
        insertFact(mapper, archived, command.context().runIdentity().agentId(),
                command.context().runIdentity().owner().target());
        if (mapper.advanceHead(tenant.value(), current.ref().factId(), current.revision(),
                archived.revision()) != 1) throw invalid();
        return Mutation.applied(replacementFact, Optional.of(current));
    }

    private Mutation acceptRevision(MemoryMapper mapper, TenantId tenant,
            MemoryCommand.AcceptRevision command, MemoryTimeRow recorded) {
        if (!command.current().tenantId().equals(tenant) || !command.proposal().tenantId().equals(tenant)
                || command.current().equals(command.proposal())) {
            return Mutation.failed(rejected("memory_accept_revision_identity_mismatch", key(command)));
        }
        // 所有双事实确认以 factId 全序锁 head，避免相反引用顺序造成死锁。
        boolean currentFirst = command.current().factId().compareTo(command.proposal().factId()) < 0;
        current(mapper, command.context(), currentFirst ? command.current() : command.proposal(), true);
        current(mapper, command.context(), currentFirst ? command.proposal() : command.current(), true);
        // READ COMMITTED下，等待head行锁的JOIN语句仍可能携带等待前的不可变revision。
        // 两个head锁均取得后开启新语句快照重读，CAS必须比较已提交head而非锁查询的旧投影。
        MemoryFact current = current(mapper, command.context(), command.current(), false);
        MemoryFact proposal = current(mapper, command.context(), command.proposal(), false);
        if (current == null || proposal == null) return Mutation.failed(rejected("memory_current_fact_missing", key(command)));
        if (current.revision() != command.expectedRevision()) return Mutation.failed(conflict(mapper, tenant,
                key(command), current.ref(), current.revision(), "memory_revision_conflict", MemoryJdbcCodec.encodeCommand(command)));
        if (proposal.revision() != command.expectedProposalRevision()) return Mutation.failed(conflict(mapper, tenant,
                key(command), proposal.ref(), proposal.revision(), "memory_revision_conflict", MemoryJdbcCodec.encodeCommand(command)));
        if (current.revision() == Long.MAX_VALUE || proposal.revision() == Long.MAX_VALUE
                || current.status() != FactStatus.ACTIVE || proposal.status() != FactStatus.PENDING_CONFIRM
                || current.supersededBy().isPresent() || proposal.supersededBy().isPresent()
                || !current.owner().equals(proposal.owner()) || !current.type().equals(proposal.type())
                || !proposal.derivedFrom().equals(Optional.of(current.ref()))) {
            return Mutation.failed(rejected("memory_accept_revision_source_mismatch", key(command)));
        }
        Instant effectiveAt = instant(requireTime(mapper.now()));
        if (proposal.validFrom().isAfter(effectiveAt)
                || proposal.validTo().filter(end -> !effectiveAt.isBefore(end)).isPresent()
                || proposal.expiresAt().filter(end -> !effectiveAt.isBefore(end)).isPresent()) {
            return Mutation.failed(rejected("memory_revision_not_effective", key(command)));
        }
        MemoryFact archived = copy(current, current.revision() + 1, FactStatus.ARCHIVED,
                closeAt(current, effectiveAt), Optional.of(proposal.ref()));
        MemoryFact activated = copy(proposal, proposal.revision() + 1, FactStatus.ACTIVE,
                proposal.validTo(), proposal.supersededBy());
        var run = command.context().runIdentity();
        insertFact(mapper, archived, run.agentId(), run.owner().target());
        insertFact(mapper, activated, run.agentId(), run.owner().target());
        if (mapper.advanceHead(tenant.value(), current.ref().factId(), current.revision(), archived.revision()) != 1
                || mapper.advanceHead(tenant.value(), proposal.ref().factId(), proposal.revision(), activated.revision()) != 1) throw invalid();
        return Mutation.applied(activated, Optional.of(current));
    }

    private Mutation transition(MemoryMapper mapper, TenantId tenant,
            MemoryCommand.Transition command, MemoryTimeRow recorded, SqlSession session) {
        MemoryFact current = current(mapper, command.context(), command.fact(), true);
        if (current == null) {
            return Mutation.failed(rejected("memory_current_fact_missing", command.idempotencyKey().value()));
        }
        if (current.revision() != command.expectedRevision()) {
            return Mutation.failed(conflict(mapper, tenant, command.idempotencyKey().value(),
                    current.ref(), current.revision(), "memory_revision_conflict",
                    MemoryJdbcCodec.encodeCommand(command)));
        }
        if (current.status() == FactStatus.PENDING_CONFIRM && command.target() == FactStatus.ACTIVE
                && current.derivedFrom().isPresent()) {
            return Mutation.failed(rejected("memory_revision_acceptance_required", command.idempotencyKey().value()));
        }
        if (current.revision() == Long.MAX_VALUE || current.supersededBy().isPresent()
                || !legal(current.status(), command.target())) {
            return Mutation.failed(rejected("memory_transition_not_allowed",
                    command.idempotencyKey().value()));
        }
        // head 已由 current(...,true) 锁定；投影钩子随后锁投影，统一采用 head→projection 顺序。
        if (!transitionGuard.allows(session, command, current)) {
            return Mutation.failed(rejected("memory_transition_precondition_failed", command.idempotencyKey().value()));
        }
        MemoryFact next = copy(current, current.revision() + 1, command.target(),
                current.validTo(), current.supersededBy());
        insertFact(mapper, next, command.context().runIdentity().agentId(),
                command.context().runIdentity().owner().target());
        if (mapper.advanceHead(tenant.value(), current.ref().factId(), current.revision(),
                next.revision()) != 1) throw invalid();
        return Mutation.applied(next, Optional.of(current));
    }

    private MemoryFact current(MemoryMapper mapper, PortCallContext context,
            MemoryFactRef ref, boolean lock) {
        var run = context.runIdentity();
        var owner = run.owner();
        MemoryFactRow row = mapper.findCurrent(ref.tenantId().value(), ref.factId(),
                userId(context), run.agentId().value(),
                owner.sessionRef().sessionId(), groupId(context), owner.target().kind().name(), owner.target().id(), lock);
        if (row == null) return null;
        MemoryFact fact = restore(mapper, row);
        if (!owns(context, fact)) throw invalid();
        return fact;
    }

    private void insertFact(MemoryMapper mapper, MemoryFact fact, AgentId writer,
            SessionTarget sourceTarget) {
        if (mapper.insertRevision(row(fact)) != 1) throw invalid();
        // 状态修订只引用同一事实的首版正文及来源，不复制正文或重新归因来源写入者。
        if (fact.revision() != 1) return;
        for (int index = 0; index < fact.sourceEventIds().size(); index++) {
            MemorySourceRow source = new MemorySourceRow();
            source.tenantId = fact.ref().tenantId().value();
            source.factId = fact.ref().factId();
            source.revision = fact.revision();
            source.sourceOrdinal = index;
            source.agentId = sourceTarget.kind() == SessionTarget.Kind.GROUP ? null : writer.value();
            source.sourceTargetKind = sourceTarget.kind().name();
            source.sourceTargetId = sourceTarget.id();
            source.sourceSessionId = fact.sourceSessionRef().sessionId();
            source.sourceEventId = fact.sourceEventIds().get(index).value();
            if (mapper.insertSource(source) != 1) throw invalid();
        }
    }

    private MemoryFact requireOperation(MemoryMapper mapper, TenantId tenant,
            String key, MemoryOperationRow operation) {
        if (!tenant.value().equals(operation.tenantId)
                || !key.equals(operation.idempotencyKey)
                || operation.commandData == null || operation.commandData.length == 0) throw invalid();
        MemoryFactRow row = mapper.findRevision(tenant.value(), operation.resultFactId,
                operation.resultRevision);
        if (row == null) throw invalid();
        MemoryFact output = restore(mapper, row);
        boolean indexed = switch (operation.commandKind) {
            case "CREATE", "SUPERSEDE" -> output.ref().equals(
                    new MemoryFactRef(tenant, factId(tenant, key))) && output.revision() == 1;
            case "TRANSITION" -> transitionOutputMatchesHistory(mapper, tenant, output);
            case "ACCEPT_REVISION" -> acceptOutputMatchesHistory(mapper, tenant, output);
            default -> false;
        };
        if (!indexed) throw invalid();
        return output;
    }

    /** 幂等重放读取原命令冻结的前置revision，绝不从已推进head补造before。 */
    private Optional<MemoryFact> beforeForCommand(MemoryMapper mapper, MemoryCommand command) {
        if (command instanceof MemoryCommand.Create) return Optional.empty();
        MemoryFactRef ref;
        long revision;
        if (command instanceof MemoryCommand.Transition transition) {
            ref = transition.fact(); revision = transition.expectedRevision();
        } else if (command instanceof MemoryCommand.AcceptRevision accept) {
            ref = accept.current(); revision = accept.expectedRevision();
        } else {
            MemoryCommand.Supersede supersede = (MemoryCommand.Supersede) command;
            ref = supersede.current(); revision = supersede.expectedRevision();
        }
        MemoryFactRow row = mapper.findRevision(ref.tenantId().value(), ref.factId(), revision);
        if (row == null) throw invalid();
        return Optional.of(restore(mapper, row));
    }

    private boolean operationMatches(MemoryMapper mapper, TenantId tenant,
            MemoryCommand command, MemoryFact output) {
        return switch (command) {
            case MemoryCommand.Create value -> output.equals(fromDraft(
                    new MemoryFactRef(tenant, factId(tenant, key(command))), value.fact(),
                    output.createdAt()));
            case MemoryCommand.Supersede value -> output.equals(fromDraft(
                    new MemoryFactRef(tenant, factId(tenant, key(command))), value.replacement(),
                    output.createdAt()));
            case MemoryCommand.Transition value -> transitionReplayMatches(
                    mapper, tenant, value, output);
            case MemoryCommand.AcceptRevision value -> output.ref().equals(value.proposal())
                    && value.expectedProposalRevision() < Long.MAX_VALUE
                    && output.revision() == value.expectedProposalRevision() + 1
                    && output.derivedFrom().equals(Optional.of(value.current()))
                    && acceptReplayMatches(mapper, tenant, value, output);
        };
    }

    private boolean acceptOutputMatchesHistory(MemoryMapper mapper, TenantId tenant, MemoryFact output) {
        if (output.status() != FactStatus.ACTIVE || output.revision() <= 1 || output.derivedFrom().isEmpty()) return false;
        MemoryFactRow pendingRow = mapper.findRevision(tenant.value(), output.ref().factId(), output.revision() - 1);
        if (pendingRow == null) return false;
        MemoryFact pending = restore(mapper, pendingRow);
        return pending.status() == FactStatus.PENDING_CONFIRM && pending.supersededBy().isEmpty()
                && output.equals(copy(pending, output.revision(), FactStatus.ACTIVE, pending.validTo(), pending.supersededBy()));
    }

    private boolean acceptReplayMatches(MemoryMapper mapper, TenantId tenant,
            MemoryCommand.AcceptRevision command, MemoryFact output) {
        if (!acceptOutputMatchesHistory(mapper, tenant, output) || command.expectedRevision() == Long.MAX_VALUE) return false;
        MemoryFactRow beforeRow = mapper.findRevision(tenant.value(), command.current().factId(), command.expectedRevision());
        MemoryFactRow archivedRow = mapper.findRevision(tenant.value(), command.current().factId(), command.expectedRevision() + 1);
        if (beforeRow == null || archivedRow == null) return false;
        MemoryFact before = restore(mapper, beforeRow), archived = restore(mapper, archivedRow);
        return before.status() == FactStatus.ACTIVE && before.supersededBy().isEmpty()
                && before.owner().equals(output.owner()) && before.type().equals(output.type())
                && archived.status() == FactStatus.ARCHIVED
                && archived.supersededBy().equals(Optional.of(output.ref()))
                && archived.validTo().isPresent()
                && archived.equals(copy(before, before.revision() + 1, FactStatus.ARCHIVED,
                        archived.validTo(), Optional.of(output.ref())));
    }

    private boolean transitionOutputMatchesHistory(MemoryMapper mapper, TenantId tenant,
            MemoryFact output) {
        if (output.revision() <= 1) return false;
        MemoryFactRow beforeRow = mapper.findRevision(tenant.value(), output.ref().factId(),
                output.revision() - 1);
        if (beforeRow == null) return false;
        MemoryFact before = restore(mapper, beforeRow);
        return legal(before.status(), output.status())
                && output.equals(copy(before, output.revision(), output.status(),
                    before.validTo(), before.supersededBy()));
    }

    private boolean transitionReplayMatches(MemoryMapper mapper, TenantId tenant,
            MemoryCommand.Transition command, MemoryFact output) {
        if (!output.ref().equals(command.fact())
                || command.expectedRevision() == Long.MAX_VALUE
                || output.revision() != command.expectedRevision() + 1
                || output.status() != command.target()) return false;
        MemoryFactRow beforeRow = mapper.findRevision(tenant.value(), command.fact().factId(),
                command.expectedRevision());
        if (beforeRow == null) return false;
        MemoryFact before = restore(mapper, beforeRow);
        return output.equals(copy(before, before.revision() + 1, command.target(),
                before.validTo(), before.supersededBy()));
    }

    private MemoryFact restore(MemoryMapper mapper, MemoryFactRow row) {
        return restore(row, mapper.findSources(row.tenantId, row.factId, row.revision));
    }

    /** 验证不可变正文摘要及身份，再将当前状态列组合成精确 revision；来源仍逐项核验。 */
    private MemoryFact restore(MemoryFactRow row, List<MemorySourceRow> sources) {
        if (row.contentRevision != 1 || row.revision < row.contentRevision
                || row.factData == null || !Objects.equals(row.factDataDigest, digest(row.factData))) throw invalid();
        MemoryFact content = MemoryJdbcCodec.decodeFact(row.factData);
        if (content.revision() != row.contentRevision
                || !Objects.equals(row.extractionDigest, digest(MemoryJdbcCodec.encodeExtractionIdentity(content)))) throw invalid();
        MemoryFact fact = row.revision == row.contentRevision ? content : copy(content, row.revision,
                FactStatus.valueOf(row.status), optionalInstant(row.validToEpochSecond, row.validToNano),
                Optional.ofNullable(row.supersededFactId).map(value ->
                        new MemoryFactRef(new TenantId(row.tenantId), value)));
        List<EventId> events = new ArrayList<>(sources.size());
        for (int index = 0; index < sources.size(); index++) {
            MemorySourceRow source = sources.get(index);
            if (!row.tenantId.equals(source.tenantId) || !row.factId.equals(source.factId)
                    || row.revision != source.revision
                    || source.sourceOrdinal != index
                    || ("GROUP".equals(source.sourceTargetKind)
                            ? source.agentId != null : source.agentId == null || source.agentId.isBlank())
                    || source.sourceTargetKind == null || source.sourceTargetKind.isBlank()
                    || source.sourceTargetId == null || source.sourceTargetId.isBlank()
                    || (MemoryScope.USER_AGENT.name().equals(row.scope) && !Objects.equals(row.agentId, source.agentId))
                    || (row.groupBindingId != null && (!"GROUP".equals(source.sourceTargetKind) || !row.groupBindingId.equals(source.sourceTargetId)))
                    || !row.sourceSessionId.equals(source.sourceSessionId)) throw invalid();
            events.add(new EventId(source.sourceEventId));
        }
        if (!fact.ref().tenantId().value().equals(row.tenantId)
                || !fact.ref().factId().equals(row.factId) || fact.revision() != row.revision
                || !sameOwnerColumns(row(fact), row)
                || !fact.scope().name().equals(row.scope) || !fact.type().value().equals(row.typeId)
                || !fact.status().name().equals(row.status)
                || !fact.sourceSessionRef().sessionId().equals(row.sourceSessionId)
                || !fact.createdAt().equals(Instant.ofEpochSecond(row.createdEpochSecond, row.createdNano))
                || !fact.validFrom().equals(Instant.ofEpochSecond(row.validFromEpochSecond, row.validFromNano))
                || !fact.validTo().equals(optionalInstant(row.validToEpochSecond, row.validToNano))
                || !fact.expiresAt().equals(optionalInstant(row.expiresEpochSecond, row.expiresNano))
                || !Objects.equals(fact.supersededBy().map(value -> value.factId()).orElse(null),
                        row.supersededFactId)
                || !fact.sourceEventIds().equals(events)) {
            throw invalid();
        }
        return fact;
    }

    private static MemoryFactRow row(MemoryFact fact) {
        MemoryFactRow row = new MemoryFactRow();
        row.tenantId = fact.ref().tenantId().value();
        row.factId = fact.ref().factId();
        row.revision = fact.revision();
        switch (fact.owner()) {
            case MemoryOwner.Session value -> row.sessionId = value.sessionRef().sessionId();
            case MemoryOwner.UserAgent value -> { row.endUserId = value.endUserId().value(); row.agentId = value.agentId().value(); }
            case MemoryOwner.User value -> row.endUserId = value.endUserId().value();
            case MemoryOwner.Group value -> row.groupBindingId = value.groupBindingRef().groupBindingId();
            case MemoryOwner.GroupMember value -> { row.groupBindingId = value.groupBindingRef().groupBindingId(); row.subjectEndUserId = value.subjectEndUserId().value(); }
        }
        row.scope = fact.scope().name();
        row.typeId = fact.type().value();
        row.status = fact.status().name();
        row.sourceSessionId = fact.sourceSessionRef().sessionId();
        row.createdEpochSecond = fact.createdAt().getEpochSecond();
        row.createdNano = fact.createdAt().getNano();
        row.validFromEpochSecond = fact.validFrom().getEpochSecond();
        row.validFromNano = fact.validFrom().getNano();
        row.validToEpochSecond = fact.validTo().map(Instant::getEpochSecond).orElse(null);
        row.validToNano = fact.validTo().map(Instant::getNano).orElse(null);
        row.expiresEpochSecond = fact.expiresAt().map(Instant::getEpochSecond).orElse(null);
        row.expiresNano = fact.expiresAt().map(Instant::getNano).orElse(null);
        row.supersededFactId = fact.supersededBy().map(value -> value.factId()).orElse(null);
        row.contentRevision = 1;
        // 内容变更使用 Supersede 新事实；因此状态 revision 的完整内容由首版唯一拥有。
        row.factData = fact.revision() == 1 ? MemoryJdbcCodec.encodeFact(fact) : null;
        row.factDataDigest = row.factData == null ? null : digest(row.factData);
        row.extractionDigest = fact.revision() == 1 ? digest(MemoryJdbcCodec.encodeExtractionIdentity(fact)) : null;
        return row;
    }

    private MemoryMutationResult conflict(MemoryMapper mapper, TenantId tenant, String key,
            MemoryFactRef ref, long revision, String reason, byte[] commandData) {
        String commandDigest = digest(commandData);
        String audit = "memory-conflict:" + digest(tenant.value() + "\u0000" + key + "\u0000"
                + reason + "\u0000" + ref.factId() + "\u0000" + revision + "\u0000" + commandDigest);
        if (mapper.insertAudit(tenant.value(), audit, key, ref.factId(), revision,
                reason, commandDigest, requireTime(mapper.now())) < 0) throw invalid();
        return new MemoryMutationResult.Conflict(ref, revision, new ResultRef(audit));
    }

    private <T> T transaction(TenantId tenant, Function<MemoryMapper, T> work) {
        return transactionWithSession(tenant, (mapper, session) -> work.apply(mapper));
    }

    /** 钩子复用同一会话、连接及 RLS 事务；只有本方法拥有提交、回滚与连接释放权。 */
    private <T> T transactionWithSession(TenantId tenant, BiFunction<MemoryMapper, SqlSession, T> work) {
        try (SqlSession session = sessions.openSession(false)) {
            try {
                MemoryMapper mapper = session.getMapper(MemoryMapper.class);
                mapper.bindTenant(tenant.value());
                T result = work.apply(mapper, session);
                session.commit();
                return result;
            } catch (RuntimeException failure) {
                session.rollback();
                throw failure;
            }
        }
    }

    private static MemoryFact fromDraft(MemoryFactRef ref, MemoryFactDraft draft, Instant createdAt) {
        return new MemoryFact(ref, draft.owner(),
                draft.type(), draft.retentionClass(), draft.schemaVersion(), draft.subject(),
                draft.predicate(), draft.object(), draft.sourceEventIds(), draft.sourceSessionRef(),
                draft.sourceExecutionRef(), draft.exportPath(), draft.extractorVersion(), 1,
                createdAt, draft.validFrom(), draft.validTo(), draft.expiresAt(), draft.confidence(),
                draft.importance(), draft.reviewRequired(), draft.derivedFrom(), Optional.empty(),
                0d, draft.initialStatus());
    }

    private static MemoryFact copy(MemoryFact fact, long revision, FactStatus status,
            Optional<Instant> validTo, Optional<MemoryFactRef> supersededBy) {
        return new MemoryFact(fact.ref(), fact.owner(), fact.type(), fact.retentionClass(), fact.schemaVersion(), fact.subject(),
                fact.predicate(), fact.object(), fact.sourceEventIds(), fact.sourceSessionRef(),
                fact.sourceExecutionRef(), fact.exportPath(), fact.extractorVersion(), revision,
                fact.createdAt(), fact.validFrom(), validTo, fact.expiresAt(), fact.confidence(),
                fact.importance(), fact.reviewRequired(), fact.derivedFrom(), supersededBy,
                fact.decayScore(), status);
    }

    private static Optional<Instant> closeAt(MemoryFact current, Instant now) {
        Instant boundary = now.isBefore(current.validFrom()) ? current.validFrom() : now;
        return current.validTo().filter(value -> value.isBefore(boundary)).or(() -> Optional.of(boundary));
    }

    private static boolean visible(PortCallContext context, MemoryQuery query,
            MemoryFact fact, Instant evaluatedAt) {
        if (!owns(context, fact) || !matchesQuery(context, query, fact)
                || !query.scopes().contains(fact.scope())
                || !query.types().isEmpty() && !query.types().contains(fact.type())
                || fact.status() != FactStatus.ACTIVE || fact.supersededBy().isPresent()
                || fact.createdAt().isAfter(evaluatedAt) || fact.validFrom().isAfter(query.asOf())
                || fact.validTo().filter(end -> !query.asOf().isBefore(end)).isPresent()
                || fact.expiresAt().filter(end -> !query.asOf().isBefore(end)).isPresent()) {
            return false;
        }
        return query.profile() != MemoryQueryProfile.RECENT_EPISODIC
                || !fact.validFrom().isBefore(query.asOf().minus(RECENT_WINDOW));
    }

    private static boolean currentForConflict(PortCallContext context, MemoryScope scope,
            MemoryTypeRef type, MemoryFact fact, Instant evaluatedAt) {
        return owns(context, fact) && fact.scope() == scope && fact.type().equals(type)
                && fact.status() == FactStatus.ACTIVE && fact.supersededBy().isEmpty()
                && !fact.createdAt().isAfter(evaluatedAt)
                && !fact.validFrom().isAfter(evaluatedAt)
                && fact.validTo().filter(end -> !evaluatedAt.isBefore(end)).isEmpty()
                && fact.expiresAt().filter(end -> !evaluatedAt.isBefore(end)).isEmpty();
    }

    private static Long userId(PortCallContext context) {
        return context.runIdentity().owner().subject() instanceof SessionSubject.User user ? user.endUserId().value() : null;
    }

    private static String groupId(PortCallContext context) {
        return context.runIdentity().owner().subject() instanceof SessionSubject.Group group ? group.groupBindingRef().groupBindingId() : null;
    }

    private static Long querySubject(PortCallContext context, MemoryQuery query) {
        if (query.owner() instanceof MemoryOwner.GroupMember member) return member.subjectEndUserId().value();
        if (query.subjectEndUserId().isPresent()) return query.subjectEndUserId().orElseThrow().value();
        return context.runIdentity().requester() instanceof RunRequester.EndUser user ? user.endUserId().value() : null;
    }

    private static boolean queryAllowed(PortCallContext context, MemoryQuery query) {
        if (!query.owner().tenantId().equals(context.runIdentity().owner().sessionRef().tenantId())) return false;
        return switch (query.owner()) {
            case MemoryOwner.Session value -> value.sessionRef().equals(context.runIdentity().owner().sessionRef())
                    && (groupId(context) == null || query.scopes().equals(Set.of(MemoryScope.SESSION)));
            case MemoryOwner.UserAgent value -> groupId(context) == null && Objects.equals(userId(context), value.endUserId().value()) && value.agentId().equals(context.runIdentity().agentId());
            case MemoryOwner.User value -> groupId(context) == null && Objects.equals(userId(context), value.endUserId().value());
            case MemoryOwner.Group value -> value.groupBindingRef().groupBindingId().equals(groupId(context));
            case MemoryOwner.GroupMember value -> value.groupBindingRef().groupBindingId().equals(groupId(context));
        };
    }

    private static boolean matchesQuery(PortCallContext context, MemoryQuery query, MemoryFact fact) {
        if (fact.owner() instanceof MemoryOwner.GroupMember member)
            return Objects.equals(querySubject(context, query), member.subjectEndUserId().value());
        return true;
    }

    private static boolean owns(PortCallContext context, MemoryFact fact) {
        if (!fact.owner().tenantId().equals(context.runIdentity().owner().sessionRef().tenantId())) return false;
        return switch (fact.owner()) {
            case MemoryOwner.Session value -> value.sessionRef().equals(context.runIdentity().owner().sessionRef()) && fact.sourceSessionRef().equals(value.sessionRef());
            case MemoryOwner.UserAgent value -> groupId(context) == null && Objects.equals(userId(context), value.endUserId().value()) && value.agentId().equals(context.runIdentity().agentId());
            case MemoryOwner.User value -> groupId(context) == null && Objects.equals(userId(context), value.endUserId().value());
            case MemoryOwner.Group value -> value.groupBindingRef().groupBindingId().equals(groupId(context));
            case MemoryOwner.GroupMember value -> value.groupBindingRef().groupBindingId().equals(groupId(context));
        };
    }

    private static boolean sameOwnerColumns(MemoryFactRow left, MemoryFactRow right) {
        return Objects.equals(left.endUserId, right.endUserId) && Objects.equals(left.agentId, right.agentId)
                && Objects.equals(left.sessionId, right.sessionId) && Objects.equals(left.groupBindingId, right.groupBindingId)
                && Objects.equals(left.subjectEndUserId, right.subjectEndUserId);
    }

    private static List<MemoryFact> budget(MemoryQuery query,List<ScoredFact> ranked){
        List<MemoryFact> selected=new ArrayList<>();long characters=0,tokens=0;
        for(var candidate:ranked){
            if(selected.size()==query.maxItems())break;
            long nextCharacters=candidate.text.codePointCount(0,candidate.text.length());
            long nextTokens=candidate.text.getBytes(StandardCharsets.UTF_8).length;
            if(characters+nextCharacters>query.characterBudget() || tokens+nextTokens>query.tokenBudget())break;
            selected.add(candidate.fact);characters+=nextCharacters;tokens+=nextTokens;
        }
        return List.copyOf(selected);
    }

    private static boolean legal(FactStatus source, FactStatus target) {
        return switch (source) {
            case ACTIVE -> Set.of(FactStatus.DECAYED, FactStatus.PENDING_CONFIRM,
                    FactStatus.ARCHIVED, FactStatus.FORGOTTEN).contains(target);
            case DECAYED -> target == FactStatus.ACTIVE;
            case PENDING_CONFIRM -> target == FactStatus.ACTIVE || target == FactStatus.REJECTED || target == FactStatus.FORGOTTEN;
            case FORGOTTEN -> target == FactStatus.ACTIVE;
            case REJECTED, ARCHIVED -> false;
        };
    }

    private static boolean callAllowed(PortCallContext context) {
        try {
            return Instant.now().isBefore(context.control().deadlineAt())
                    && !context.control().cancellationSignal().isCancellationRequested();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static MemoryTimeRow requireTime(MemoryTimeRow row) {
        if (row == null || row.nano < 0 || row.nano > 999_999_999) throw invalid();
        return row;
    }

    private static Instant instant(MemoryTimeRow row) {
        return Instant.ofEpochSecond(row.epochSecond, row.nano);
    }

    private static Optional<Instant> optionalInstant(Long second, Integer nano) {
        if ((second == null) != (nano == null)) throw invalid();
        return second == null ? Optional.empty() : Optional.of(Instant.ofEpochSecond(second, nano));
    }

    private static String factId(TenantId tenant, String key) {
        return "memory-fact:" + digest(tenant.value() + "\u0000" + key);
    }

    private static MemoryQueryOutcome failed(String reason, String identity) {
        return new MemoryQueryOutcome.Failed(new PortFailure(reason,
                RetryMode.IDEMPOTENT_BY_STABLE_KEY,
                new ResultRef("memory-failure:" + digest(reason + "\u0000" + identity))));
    }

    private static MemoryMutationResult rejected(String reason, String identity) {
        return new MemoryMutationResult.Rejected(new PortFailure(reason,
                RetryMode.IDEMPOTENT_BY_STABLE_KEY,
                new ResultRef("memory-failure:" + digest(reason + "\u0000" + identity))));
    }

    private static PortCallContext context(MemoryCommand command) {
        return switch (command) {
            case MemoryCommand.Create value -> value.context();
            case MemoryCommand.Supersede value -> value.context();
            case MemoryCommand.Transition value -> value.context();
            case MemoryCommand.AcceptRevision value -> value.context();
        };
    }

    private static String key(MemoryCommand command) {
        return switch (command) {
            case MemoryCommand.Create value -> value.idempotencyKey().value();
            case MemoryCommand.Supersede value -> value.idempotencyKey().value();
            case MemoryCommand.Transition value -> value.idempotencyKey().value();
            case MemoryCommand.AcceptRevision value -> value.idempotencyKey().value();
        };
    }

    private static String kind(MemoryCommand command) {
        return switch (command) {
            case MemoryCommand.Create ignored -> "CREATE";
            case MemoryCommand.Supersede ignored -> "SUPERSEDE";
            case MemoryCommand.Transition ignored -> "TRANSITION";
            case MemoryCommand.AcceptRevision ignored -> "ACCEPT_REVISION";
        };
    }

    private static String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean retryable(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("memory_store_corrupt");
    }

    private record FactRevision(String factId, long revision) { }
    private record ScoredFact(MemoryFact fact,double score,String text) { }
    private record QueryVector(String text,String reason) { }

    private record Mutation(MemoryFact result, Optional<MemoryFact> before, MemoryMutationResult failure) {
        private static Mutation applied(MemoryFact result, Optional<MemoryFact> before) { return new Mutation(result, before, null); }
        private static Mutation failed(MemoryMutationResult failure) { return new Mutation(null, Optional.empty(), failure); }
    }

    private static final class CallRejected extends RuntimeException {
        private final String reason;
        private CallRejected(String reason) { this.reason = reason; }
    }
}

interface MemoryMapper {
    Long lockHeadForRead(@Param("tenant") String tenant, @Param("factId") String factId);
    List<MemoryFactRow> governedHistory(@Param("owner") MemoryFactRow owner,
            @Param("targetKind") String targetKind, @Param("targetId") String targetId);
    List<MemorySourceRow> governedSources(@Param("tenant") String tenant,
            @Param("factId") String factId, @Param("revision") long revision);
    List<MemorySourcePositionRow> sourcePositions(@Param("tenant") String tenant, @Param("agentId") String agentId,
            @Param("targetKind") String targetKind, @Param("targetId") String targetId,
            @Param("sessionId") String sessionId, @Param("eventIds") List<String> eventIds);
    MemorySearchStatsRow searchStats(@Param("filter") MemorySearchFilter filter);
    List<MemorySearchHitRow> lexicalSearch(@Param("filter") MemorySearchFilter filter);
    List<MemorySearchHitRow> vectorSearch(@Param("filter") MemorySearchFilter filter);
    int insertSearchDocument(MemorySearchDocumentRow document);
    MemorySearchDocumentRow searchDocument(MemorySearchDocumentRow key);
    List<MemorySearchDocumentRow> searchDocuments(@Param("tenant") String tenant,
            @Param("documents") List<MemorySearchDocumentRow> documents);
    int enqueueEmbedding(@Param("document") MemorySearchDocumentRow document,@Param("model") EmbeddingModel model,@Param("invocationId") String invocationId);
    int expireEmbeddings(@Param("tenant") String tenant);
    MemoryEmbeddingRow claimEmbedding(@Param("tenant") String tenant,@Param("token") String token,@Param("leaseSeconds") double leaseSeconds);
    boolean embeddingSourceActive(@Param("work") MemoryEmbeddingWork work);
    int completeEmbedding(@Param("work") MemoryEmbeddingWork work,@Param("state") String state,@Param("vector") String vector,@Param("reason") String reason);
    List<MemoryRebuildRow> rebuildCandidates(@Param("tenant") String tenant,@Param("policy") MemoryRetrievalPolicy policy,@Param("model") EmbeddingModel model,@Param("limit") int limit);
    int insertQueryEmbedding(@Param("tenant") String tenant,@Param("operation") String operation,@Param("fingerprint") String fingerprint,@Param("invocation") String invocation,
            @Param("model") EmbeddingModel model,@Param("runData") String runData,@Param("identityData") String identityData,@Param("deadline") Instant deadline);
    MemoryQueryEmbeddingRow queryEmbedding(@Param("tenant") String tenant,@Param("operation") String operation);
    int expireQueryEmbeddings(@Param("tenant") String tenant,@Param("operation") String operation);
    int finishQueryEmbedding(@Param("tenant") String tenant,@Param("operation") String operation,@Param("fingerprint") String fingerprint,@Param("state") String state,@Param("vector") String vector,@Param("reason") String reason);
    String bindTenant(@Param("tenant") String tenant);
    MemoryTimeRow now();
    String lockOperation(@Param("tenant") String tenant, @Param("key") String key);
    String lockExtraction(@Param("tenant") String tenant, @Param("digest") String digest);
    MemoryFactRow findEquivalentExtraction(@Param("tenant") String tenant, @Param("digest") String digest,
            @Param("endUserId") Long endUserId, @Param("agentId") String agentId,
            @Param("sessionId") String sessionId, @Param("targetKind") String targetKind, @Param("targetId") String targetId, @Param("scope") String scope,
            @Param("epoch") long epoch, @Param("nano") int nano);
    MemoryExtractionEvidenceRow findExtractionEvidence(@Param("tenant") String tenant, @Param("digest") String digest,
            @Param("targetKind") String targetKind, @Param("targetId") String targetId,
            @Param("sessionId") String sessionId, @Param("eventId") String eventId, @Param("extractor") String extractor);
    int insertExtractionEvidence(@Param("tenant") String tenant, @Param("digest") String digest,
            @Param("agentId") String agentId, @Param("targetKind") String targetKind, @Param("targetId") String targetId,
            @Param("sessionId") String sessionId, @Param("eventId") String eventId, @Param("extractor") String extractor,
            @Param("factId") String factId, @Param("revision") long revision,
            @Param("accessAudit") String accessAudit, @Param("sourceAudit") String sourceAudit);
    MemoryOperationRow findOperation(@Param("tenant") String tenant, @Param("key") String key);
    MemoryFactRow findCurrent(@Param("tenant") String tenant, @Param("factId") String factId,
            @Param("endUserId") Long endUserId, @Param("agentId") String agentId,
            @Param("sessionId") String sessionId, @Param("groupBindingId") String groupBindingId, @Param("targetKind") String targetKind, @Param("targetId") String targetId, @Param("lock") boolean lock);
    List<MemoryFactRow> findCurrentBatch(@Param("tenant") String tenant,
            @Param("requests") List<MemoryCurrentFactRequest> requests);
    List<MemorySourceRow> findSourcesBatch(@Param("tenant") String tenant,
            @Param("facts") List<MemoryFactRow> facts);
    MemoryFactRow findRevision(@Param("tenant") String tenant, @Param("factId") String factId,
            @Param("revision") long revision);
    Long headRevision(@Param("tenant") String tenant, @Param("factId") String factId);
    List<MemoryFactRow> listCurrent(@Param("tenant") String tenant,
            @Param("endUserId") Long endUserId, @Param("agentId") String agentId,
            @Param("sessionId") String sessionId, @Param("groupBindingId") String groupBindingId, @Param("targetKind") String targetKind, @Param("targetId") String targetId,
            @Param("scopes") List<String> scopes, @Param("subjectEndUserId") Long subjectEndUserId, @Param("taskId") String taskId);
    List<MemorySourceRow> findSources(@Param("tenant") String tenant,
            @Param("factId") String factId, @Param("revision") long revision);
    int insertRevision(MemoryFactRow row);
    int insertSource(MemorySourceRow row);
    int insertHead(@Param("tenant") String tenant, @Param("fact") MemoryFact fact,
            @Param("row") MemoryFactRow row);
    int advanceHead(@Param("tenant") String tenant, @Param("factId") String factId,
            @Param("expectedRevision") long expectedRevision,
            @Param("nextRevision") long nextRevision);
    int insertOperation(@Param("tenant") String tenant, @Param("key") String key,
            @Param("kind") String kind, @Param("data") byte[] data,
            @Param("fact") MemoryFact fact, @Param("recorded") MemoryTimeRow recorded);
    int insertAudit(@Param("tenant") String tenant, @Param("auditRef") String auditRef,
            @Param("key") String key, @Param("factId") String factId,
            @Param("revision") long revision, @Param("reason") String reason,
            @Param("digest") String digest, @Param("recorded") MemoryTimeRow recorded);
}
