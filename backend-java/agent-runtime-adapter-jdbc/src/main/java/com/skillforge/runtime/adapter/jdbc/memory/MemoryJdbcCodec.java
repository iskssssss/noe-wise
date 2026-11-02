package com.skillforge.runtime.adapter.jdbc.memory;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryCall;
import com.skillforge.runtime.adapter.jdbc.codec.CanonicalBinaryCodec;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Memory JDBC 私有规范二进制；拒绝未知版本、超限、截断与尾随数据。 */
final class MemoryJdbcCodec {

    private static final int FACT_MAGIC = 0x53464d46;
    private static final int COMMAND_MAGIC = 0x53464d43;
    private static final int VERSION = 3;
    /** 命令v4保留完整上下文及参数身份，草稿正文由精确结果revision拥有，只保存规范草稿摘要。 */
    private static final int COMMAND_VERSION = 4;
    private static final int MAX_TEXT_BYTES = 1_048_576;
    private static final int MAX_FRAME_BYTES = 4_194_304;
    private static final int MAX_ITEMS = 4096;

    private static final CanonicalBinaryCodec IDENTITY = new CanonicalBinaryCodec(new byte[]{'M','I','D','1'}, "memory_identity_codec_invalid");
    static String encodeRun(RunIdentity run) { return IDENTITY.encode(run); }
    static RunIdentity decodeRun(String data) { return IDENTITY.decode(data, RunIdentity.class); }
    static String encodeIdentity(VerifiedIdentity identity) { return IDENTITY.encode(identity); }
    static VerifiedIdentity decodeIdentity(String data) { return IDENTITY.decode(data, VerifiedIdentity.class); }

    static byte[] encodeQuery(MemoryQueryCall call) {
        return write(out -> {
            writeText(out, "memory-search-query.v1"); writeText(out,encodeRun(call.context().runIdentity()));
            MemoryQuery q=call.query(); writeText(out,q.queryId()); writeMemoryOwner(out,q.owner());
            out.writeBoolean(q.subjectEndUserId().isPresent()); if(q.subjectEndUserId().isPresent()) out.writeLong(q.subjectEndUserId().orElseThrow().value());
            writeText(out,q.intent()); writeOptionalText(out,q.taskConstraint().map(value->value.taskId()));
            var scopes=q.scopes().stream().map(Enum::name).sorted().toList(); out.writeInt(scopes.size()); for(var scope:scopes)writeText(out,scope);
            var types=q.types().stream().map(value->value.value()).sorted().toList(); out.writeInt(types.size()); for(var type:types)writeText(out,type);
            writeText(out,q.profile().name()); writeInstant(out,q.asOf());
            var p=q.retrievalPolicy(); writeText(out,p.id());writeText(out,p.version());writeText(out,p.projectionVersion());writeText(out,p.analyzerVersion());
            out.writeInt(p.candidateLimit());out.writeInt(p.rrfRankConstant());out.writeLong(Double.doubleToRawLongBits(p.minimumCosineSimilarity()));
            out.writeBoolean(p.embeddingModel().isPresent());if(p.embeddingModel().isPresent()){var m=p.embeddingModel().orElseThrow();writeText(out,m.modelConfigId());out.writeLong(m.revision());out.writeInt(m.dimensions());}
            writeText(out,p.vectorFailurePolicy().name());out.writeInt(q.maxItems());out.writeInt(q.tokenBudget());out.writeInt(q.characterBudget());out.writeBoolean(q.requireProvenance());
        });
    }

    /** 从原已提交写命令恢复索引责任；派生投影删除后不依赖当前Worker身份。 */
    static PortCallContext decodeCommandContext(byte[] bytes) {
        if(bytes==null || bytes.length>MAX_FRAME_BYTES)throw invalid();
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=COMMAND_MAGIC || in.readInt()!=COMMAND_VERSION)throw invalid();
            String kind=readText(in);if(!kind.equals("CREATE") && !kind.equals("SUPERSEDE"))throw invalid();
            var owner=readOwner(in);var agent=new AgentId(readText(in));var input=new InputId(readText(in));
            var runRef=new RunRef(new TenantId(readText(in)),readText(in));var trace=readTrace(in);var cause=new ResultRef(readText(in));
            RunRequester requester=switch(readText(in)){
                case "USER"->new RunRequester.EndUser(new EndUserId(in.readLong()));
                case "SYSTEM"->new RunRequester.System((PrincipalRef.SystemPrincipal)readPrincipal(in));
                default->throw invalid();};
            var origin=new com.skillforge.runtime.api.RunOriginRef(new TenantId(readText(in)),readText(in));
            Optional<com.skillforge.runtime.api.SourceMessageRef> source=in.readBoolean()?Optional.of(new com.skillforge.runtime.api.SourceMessageRef(readSession(in),readText(in))):Optional.empty();
            var run=new RunIdentity(owner,agent,input,runRef,trace,cause,requester,source,origin);
            var principal=readPrincipal(in);var verifiedOwner=readOwner(in);int count=readCount(in);var scopes=new java.util.HashSet<String>();for(int i=0;i<count;i++)scopes.add(readText(in));
            var identity=new VerifiedIdentity(principal,verifiedOwner,scopes,in.readLong(),new ResultRef(readText(in)));
            return new PortCallContext(run,identity,new com.skillforge.runtime.api.port.InvocationControl(Instant.now().plusSeconds(60),()->false));
        }catch(IOException|RuntimeException failure){throw invalid();}
    }

    private static SessionOwner readOwner(DataInputStream in)throws IOException{
        var session=readSession(in);var kind=enumValue(com.skillforge.runtime.api.SessionTarget.Kind.class,readText(in));var id=readText(in);
        SessionSubject subject=switch(readText(in)){
            case "USER"->SessionSubject.user(new EndUserId(in.readLong()));
            case "GROUP"->SessionSubject.group(new GroupBindingRef(session.tenantId(),readText(in)));
            default->throw invalid();};
        return new SessionOwner(session,subject,new com.skillforge.runtime.api.SessionTarget(kind,id));
    }
    private static PrincipalRef readPrincipal(DataInputStream in)throws IOException{
        String kind=readText(in);TenantId tenant=new TenantId(readText(in));return switch(kind){
            case "END_USER"->new PrincipalRef.EndUserPrincipal(tenant,new EndUserId(in.readLong()));
            case "SERVICE"->new PrincipalRef.ServicePrincipal(tenant,readText(in),readText(in),in.readLong());
            case "SYSTEM"->new PrincipalRef.SystemPrincipal(tenant,readText(in));default->throw invalid();};
    }
    private static TraceEnvelope readTrace(DataInputStream in)throws IOException{
        String trace=readText(in),span=readText(in);var root=new InputId(readText(in));return new TraceEnvelope(trace,span,root,in.readBoolean()?Optional.of(new EventId(readText(in))):Optional.empty());
    }

    private MemoryJdbcCodec() { }

    /** owner以分支与长度前缀编码，不能使用toString或分隔符拼接作为去重身份。 */
    static byte[] encodeOwnerIdentity(MemoryOwner owner) {
        return write(out -> writeMemoryOwner(out, owner));
    }

    static byte[] encodeFact(MemoryFact fact) {
        return write(out -> {
            out.writeInt(FACT_MAGIC);
            out.writeInt(VERSION);
            writeFact(out, fact);
        });
    }

    /** 精确提取语义身份；不包括本次来源和声明时间，归属及审核属性仍参与身份。 */
    static byte[] encodeExtractionIdentity(MemoryFactDraft draft) {
        return extractionIdentity(draft.owner(), draft.type(), draft.retentionClass(), draft.schemaVersion(),
                draft.subject(), draft.predicate(), draft.object(), draft.confidence(),
                draft.importance(), draft.reviewRequired(), draft.validTo(), draft.expiresAt(), draft.derivedFrom());
    }

    /** 已存不可变正文使用与草稿相同的精确语义编码。 */
    static byte[] encodeExtractionIdentity(MemoryFact fact) {
        return extractionIdentity(fact.owner(), fact.type(), fact.retentionClass(), fact.schemaVersion(),
                fact.subject(), fact.predicate(), fact.object(), fact.confidence(),
                fact.importance(), fact.reviewRequired(), fact.validTo(), fact.expiresAt(), fact.derivedFrom());
    }

    /** 字段使用长度前缀编码，避免分隔符碰撞；动态 JSON 只进行精确规范文本比较。 */
    private static byte[] extractionIdentity(MemoryOwner owner, MemoryTypeRef type,
            RetentionClassRef retention, String schema, String subject, String predicate,
            TypedPayload object, double confidence, double importance, boolean review,
            Optional<Instant> validTo, Optional<Instant> expiresAt, Optional<MemoryFactRef> derivedFrom) {
        return write(out -> {
            writeText(out, "skillforge.memory-extraction-identity.v2");
            writeMemoryOwner(out, owner);
            writeText(out, type.value());
            writeText(out, retention.value());
            writeText(out, schema);
            writeText(out, subject);
            writeText(out, predicate);
            writePayload(out, object);
            out.writeLong(Double.doubleToRawLongBits(confidence));
            out.writeLong(Double.doubleToRawLongBits(importance));
            out.writeBoolean(review);
            writeOptionalInstant(out, validTo);
            writeOptionalInstant(out, expiresAt);
            writeOptionalFactRef(out, derivedFrom);
        });
    }

    static MemoryFact decodeFact(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) {
            throw invalid();
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != FACT_MAGIC) {
                throw invalid();
            }
            int version = input.readInt();
            if (version != VERSION) {
                throw invalid();
            }
            MemoryFact fact = readFact(input, version);
            if (input.read() != -1) {
                throw invalid();
            }
            return fact;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    static byte[] encodeCommand(MemoryCommand command) {
        return write(out -> {
            out.writeInt(COMMAND_MAGIC);
            out.writeInt(COMMAND_VERSION);
            switch (command) {
                case MemoryCommand.Create value -> {
                    writeText(out, "CREATE");
                    writeContext(out, value.context());
                    writeText(out, value.idempotencyKey().value());
                    writeDraftDigest(out, value.fact());
                }
                case MemoryCommand.Supersede value -> {
                    writeText(out, "SUPERSEDE");
                    writeContext(out, value.context());
                    writeText(out, value.idempotencyKey().value());
                    writeFactRef(out, value.current());
                    out.writeLong(value.expectedRevision());
                    writeDraftDigest(out, value.replacement());
                }
                case MemoryCommand.Transition value -> {
                    writeText(out, "TRANSITION");
                    writeContext(out, value.context());
                    writeText(out, value.idempotencyKey().value());
                    writeFactRef(out, value.fact());
                    out.writeLong(value.expectedRevision());
                    writeText(out, value.target().name());
                    writeText(out, value.reasonCode());
                }
                case MemoryCommand.AcceptRevision value -> {
                    writeText(out, "ACCEPT_REVISION");
                    writeContext(out, value.context());
                    writeText(out, value.idempotencyKey().value());
                    writeFactRef(out, value.current());
                    out.writeLong(value.expectedRevision());
                    writeFactRef(out, value.proposal());
                    out.writeLong(value.expectedProposalRevision());
                }
            }
        });
    }

    /**
     * 大草稿正文不再复制进回执。摘要覆盖原草稿全部规范字段；幂等命中后仍用精确
     * result_fact_id/revision完整还原并逐字段核对请求草稿，不能仅凭摘要返回Applied。
     */
    private static void writeDraftDigest(DataOutputStream out, MemoryFactDraft draft) throws IOException {
        byte[] canonical = write(frame -> {
            writeText(frame, "skillforge.memory-command-draft.v1");
            writeDraft(frame, draft);
        });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            out.writeInt(canonical.length);
            out.write(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    private static void writeFact(DataOutputStream out, MemoryFact fact) throws IOException {
        writeFactRef(out, fact.ref());
        writeMemoryOwner(out, fact.owner());
        writeText(out, fact.type().value());
        writeText(out, fact.retentionClass().value());
        writeText(out, fact.schemaVersion());
        writeText(out, fact.subject());
        writeText(out, fact.predicate());
        writePayload(out, fact.object());
        out.writeInt(fact.sourceEventIds().size());
        for (EventId event : fact.sourceEventIds()) writeText(out, event.value());
        writeSession(out, fact.sourceSessionRef());
        writeOptionalExecution(out, fact.sourceExecutionRef());
        writeOptionalText(out, fact.exportPath());
        writeText(out, fact.extractorVersion());
        out.writeLong(fact.revision());
        writeInstant(out, fact.createdAt());
        writeInstant(out, fact.validFrom());
        writeOptionalInstant(out, fact.validTo());
        writeOptionalInstant(out, fact.expiresAt());
        out.writeLong(Double.doubleToRawLongBits(fact.confidence()));
        out.writeLong(Double.doubleToRawLongBits(fact.importance()));
        out.writeBoolean(fact.reviewRequired());
        writeOptionalFactRef(out, fact.derivedFrom());
        writeOptionalFactRef(out, fact.supersededBy());
        out.writeLong(Double.doubleToRawLongBits(fact.decayScore()));
        writeText(out, fact.status().name());
    }

    private static MemoryFact readFact(DataInputStream input, int version) throws IOException {
        MemoryFactRef ref = readFactRef(input);
        MemoryOwner owner = readMemoryOwner(input);
        MemoryTypeRef type = new MemoryTypeRef(readText(input));
        RetentionClassRef retention = new RetentionClassRef(readText(input));
        String schema = readText(input);
        String subject = readText(input);
        String predicate = readText(input);
        TypedPayload object = readPayload(input);
        int sourceCount = readCount(input);
        List<EventId> sources = new ArrayList<>(sourceCount);
        for (int index = 0; index < sourceCount; index++) {
            sources.add(new EventId(readText(input)));
        }
        SessionRef sourceSession = readSession(input);
        Optional<ExecutionRef> sourceExecution = readOptionalExecution(input);
        Optional<String> exportPath = readOptionalText(input);
        String extractor = readText(input);
        long revision = input.readLong();
        Instant created = readInstant(input);
        Instant validFrom = readInstant(input);
        Optional<Instant> validTo = readOptionalInstant(input);
        Optional<Instant> expires = readOptionalInstant(input);
        double confidence = Double.longBitsToDouble(input.readLong());
        double importance = Double.longBitsToDouble(input.readLong());
        boolean review = input.readBoolean();
        Optional<MemoryFactRef> derived = readOptionalFactRef(input);
        Optional<MemoryFactRef> superseded = readOptionalFactRef(input);
        double decay = Double.longBitsToDouble(input.readLong());
        FactStatus status = enumValue(FactStatus.class, readText(input));
        return new MemoryFact(ref, owner, type, retention, schema,
                subject, predicate, object, sources, sourceSession, sourceExecution, exportPath,
                extractor, revision, created, validFrom, validTo, expires, confidence,
                importance, review, derived, superseded, decay, status);
    }

    private static void writeDraft(DataOutputStream out, MemoryFactDraft draft) throws IOException {
        writeMemoryOwner(out, draft.owner());
        writeText(out, draft.type().value());
        writeText(out, draft.retentionClass().value());
        writeText(out, draft.schemaVersion());
        writeText(out, draft.subject());
        writeText(out, draft.predicate());
        writePayload(out, draft.object());
        out.writeInt(draft.sourceEventIds().size());
        for (EventId event : draft.sourceEventIds()) writeText(out, event.value());
        writeSession(out, draft.sourceSessionRef());
        writeOptionalExecution(out, draft.sourceExecutionRef());
        writeOptionalText(out, draft.exportPath());
        writeText(out, draft.extractorVersion());
        writeInstant(out, draft.validFrom());
        writeOptionalInstant(out, draft.validTo());
        writeOptionalInstant(out, draft.expiresAt());
        out.writeLong(Double.doubleToRawLongBits(draft.confidence()));
        out.writeLong(Double.doubleToRawLongBits(draft.importance()));
        out.writeBoolean(draft.reviewRequired());
        writeOptionalFactRef(out, draft.derivedFrom());
        writeText(out, draft.initialStatus().name());
    }

    /** control deadline/cancellation 是瞬时护栏；持久命令冻结 Run 与已验证身份。 */
    private static void writeContext(DataOutputStream out, PortCallContext context) throws IOException {
        RunIdentity run = context.runIdentity();
        writeOwner(out, run.owner());
        writeText(out, run.agentId().value());
        writeText(out, run.causationInputId().value());
        writeText(out, run.runRef().tenantId().value());
        writeText(out, run.runRef().runId());
        writeTrace(out, run.trace());
        writeText(out, run.causationIdentityRef().value());
        switch (run.requester()) {
            case RunRequester.EndUser user -> { writeText(out, "USER"); out.writeLong(user.endUserId().value()); }
            case RunRequester.System system -> { writeText(out, "SYSTEM"); writePrincipal(out, system.principal()); }
        }
        writeText(out, run.originRef().tenantId().value());
        writeText(out, run.originRef().originId());
        out.writeBoolean(run.sourceMessageRef().isPresent());
        if (run.sourceMessageRef().isPresent()) {
            writeSession(out, run.sourceMessageRef().orElseThrow().sessionRef());
            writeText(out, run.sourceMessageRef().orElseThrow().messageId());
        }
        VerifiedIdentity identity = context.identity();
        writePrincipal(out, identity.principal());
        writeOwner(out, identity.owner());
        List<String> scopes = identity.scopes().stream().sorted(Comparator.naturalOrder()).toList();
        out.writeInt(scopes.size());
        for (String scope : scopes) writeText(out, scope);
        out.writeLong(identity.policyRevision());
        writeText(out, identity.verificationRef().value());
    }

    private static void writePrincipal(DataOutputStream out, PrincipalRef principal) throws IOException {
        switch (principal) {
            case PrincipalRef.EndUserPrincipal value -> {
                writeText(out, "END_USER");
                writeText(out, value.tenantId().value());
                out.writeLong(value.endUserId().value());
            }
            case PrincipalRef.ServicePrincipal value -> {
                writeText(out, "SERVICE");
                writeText(out, value.tenantId().value());
                writeText(out, value.principalId());
                writeText(out, value.integrationId());
                out.writeLong(value.credentialVersion());
            }
            case PrincipalRef.SystemPrincipal value -> {
                writeText(out, "SYSTEM");
                writeText(out, value.tenantId().value());
                writeText(out, value.principalId());
            }
        }
    }

    private static void writeOwner(DataOutputStream out, SessionOwner owner) throws IOException {
        writeSession(out, owner.sessionRef());
        writeText(out, owner.target().kind().name());
        writeText(out, owner.target().id());
        switch (owner.subject()) {
            case SessionSubject.User user -> { writeText(out, "USER"); out.writeLong(user.endUserId().value()); }
            case SessionSubject.Group group -> { writeText(out, "GROUP"); writeText(out, group.groupBindingRef().groupBindingId()); }
        }
    }

    private static void writeTrace(DataOutputStream out, TraceEnvelope trace) throws IOException {
        writeText(out, trace.traceId());
        writeText(out, trace.spanId());
        writeText(out, trace.rootInputId().value());
        out.writeBoolean(trace.causedByEventId().isPresent());
        if (trace.causedByEventId().isPresent()) {
            writeText(out, trace.causedByEventId().orElseThrow().value());
        }
    }

    private static void writeMemoryOwner(DataOutputStream out, MemoryOwner owner) throws IOException {
        writeText(out, owner.scope().name());
        writeText(out, owner.tenantId().value());
        switch (owner) {
            case MemoryOwner.Session value -> writeText(out, value.sessionRef().sessionId());
            case MemoryOwner.UserAgent value -> { out.writeLong(value.endUserId().value()); writeText(out, value.agentId().value()); }
            case MemoryOwner.User value -> out.writeLong(value.endUserId().value());
            case MemoryOwner.Group value -> writeText(out, value.groupBindingRef().groupBindingId());
            case MemoryOwner.GroupMember value -> { writeText(out, value.groupBindingRef().groupBindingId()); out.writeLong(value.subjectEndUserId().value()); }
        }
    }

    private static MemoryOwner readMemoryOwner(DataInputStream input) throws IOException {
        MemoryScope scope = enumValue(MemoryScope.class, readText(input));
        TenantId tenant = new TenantId(readText(input));
        return switch (scope) {
            case SESSION -> new MemoryOwner.Session(new SessionRef(tenant, readText(input)));
            case USER_AGENT -> new MemoryOwner.UserAgent(tenant, new EndUserId(input.readLong()), new AgentId(readText(input)));
            case USER -> new MemoryOwner.User(tenant, new EndUserId(input.readLong()));
            case GROUP -> new MemoryOwner.Group(new GroupBindingRef(tenant, readText(input)));
            case GROUP_MEMBER -> new MemoryOwner.GroupMember(new GroupBindingRef(tenant, readText(input)), new EndUserId(input.readLong()));
        };
    }

    private static void writePayload(DataOutputStream out, TypedPayload payload) throws IOException {
        writeText(out, payload.schemaId());
        writeText(out, payload.canonicalJson());
    }

    private static TypedPayload readPayload(DataInputStream input) throws IOException {
        return new TypedPayload(readText(input), readText(input));
    }

    private static void writeFactRef(DataOutputStream out, MemoryFactRef ref) throws IOException {
        writeText(out, ref.tenantId().value());
        writeText(out, ref.factId());
    }

    private static MemoryFactRef readFactRef(DataInputStream input) throws IOException {
        return new MemoryFactRef(new TenantId(readText(input)), readText(input));
    }

    private static void writeOptionalAgent(
            DataOutputStream out, Optional<AgentId> agent) throws IOException {
        out.writeBoolean(agent.isPresent());
        if (agent.isPresent()) writeText(out, agent.orElseThrow().value());
    }

    private static Optional<AgentId> readOptionalAgent(DataInputStream input) throws IOException {
        return input.readBoolean() ? Optional.of(new AgentId(readText(input))) : Optional.empty();
    }

    private static void writeOptionalFactRef(
            DataOutputStream out, Optional<MemoryFactRef> value) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isPresent()) writeFactRef(out, value.orElseThrow());
    }

    private static Optional<MemoryFactRef> readOptionalFactRef(DataInputStream input) throws IOException {
        return input.readBoolean() ? Optional.of(readFactRef(input)) : Optional.empty();
    }

    private static void writeSession(DataOutputStream out, SessionRef session) throws IOException {
        writeText(out, session.tenantId().value());
        writeText(out, session.sessionId());
    }

    private static SessionRef readSession(DataInputStream input) throws IOException {
        return new SessionRef(new TenantId(readText(input)), readText(input));
    }

    private static void writeOptionalSession(
            DataOutputStream out, Optional<SessionRef> session) throws IOException {
        out.writeBoolean(session.isPresent());
        if (session.isPresent()) writeSession(out, session.orElseThrow());
    }

    private static Optional<SessionRef> readOptionalSession(DataInputStream input) throws IOException {
        return input.readBoolean() ? Optional.of(readSession(input)) : Optional.empty();
    }

    private static void writeOptionalExecution(
            DataOutputStream out, Optional<ExecutionRef> execution) throws IOException {
        out.writeBoolean(execution.isPresent());
        if (execution.isPresent()) {
            writeText(out, execution.orElseThrow().tenantId().value());
            writeText(out, execution.orElseThrow().executionId());
        }
    }

    private static Optional<ExecutionRef> readOptionalExecution(DataInputStream input) throws IOException {
        return input.readBoolean()
                ? Optional.of(new ExecutionRef(new TenantId(readText(input)), readText(input)))
                : Optional.empty();
    }

    private static void writeOptionalText(
            DataOutputStream out, Optional<String> value) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isPresent()) writeText(out, value.orElseThrow());
    }

    private static Optional<String> readOptionalText(DataInputStream input) throws IOException {
        return input.readBoolean() ? Optional.of(readText(input)) : Optional.empty();
    }

    private static void writeInstant(DataOutputStream out, Instant value) throws IOException {
        out.writeLong(value.getEpochSecond());
        out.writeInt(value.getNano());
    }

    private static Instant readInstant(DataInputStream input) throws IOException {
        return Instant.ofEpochSecond(input.readLong(), input.readInt());
    }

    private static void writeOptionalInstant(
            DataOutputStream out, Optional<Instant> value) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isPresent()) writeInstant(out, value.orElseThrow());
    }

    private static Optional<Instant> readOptionalInstant(DataInputStream input) throws IOException {
        return input.readBoolean() ? Optional.of(readInstant(input)) : Optional.empty();
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw invalid();
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_TEXT_BYTES || length > input.available()) throw invalid();
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) throw invalid();
        return value;
    }

    private static int readCount(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ITEMS) throw invalid();
        return count;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static byte[] write(Writer writer) {
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            writer.write(output);
            output.flush();
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_FRAME_BYTES) throw invalid();
            return result;
        } catch (IOException failure) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("memory_jdbc_codec_invalid");
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }
}
