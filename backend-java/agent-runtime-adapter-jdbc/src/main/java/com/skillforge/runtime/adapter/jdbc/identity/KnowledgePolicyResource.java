package com.skillforge.runtime.adapter.jdbc.identity;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.KnowledgePortal.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** knowledge.execute的纯IAM资源；派发43字段使用真实Action和请求，不伪造Attempt。 */
public final class KnowledgePolicyResource {
    private KnowledgePolicyResource() {}

    /**
     * 派发事务仅使用已存在的准确请求与逻辑Action授权，不虚构尚未登记的Attempt或ProviderDispatch。
     * 43字段保留完整owner、执行者、版本来源、时态和预算；schema v3严格拒绝缺失或额外字段。
     */
    public static TypedPayload dispatch(RunIdentity run, CapabilitySnapshotRef snapshot, FrozenKnowledgeTarget target,
            KnowledgeRequest request, EventId sourceEvent, IdempotencyKey idempotencyKey,
            ActionRef action, ResultRef requestRef, String fingerprint) {
        Objects.requireNonNull(action); Objects.requireNonNull(requestRef); text(fingerprint);
        if (!run.runRef().tenantId().equals(action.tenantId())) throw new IllegalArgumentException("knowledge_resource_tenant_mismatch");
        var fields=common(run,snapshot,target,request,sourceEvent,idempotencyKey);
        fields.addAll(List.of(action.actionId(),requestRef.value(),fingerprint));
        return encode("skillforge.runtime.knowledge.execute-resource.v4",fields);
    }

    /** 所有来源共享同一真实请求字段；纯构造再次核对scope收窄与完整身份，不查询旧Run或许可。 */
    private static ArrayList<String> common(RunIdentity run, CapabilitySnapshotRef snapshot, FrozenKnowledgeTarget target,
            KnowledgeRequest request, EventId sourceEvent, IdempotencyKey idempotencyKey) {
        Objects.requireNonNull(run);Objects.requireNonNull(snapshot);Objects.requireNonNull(target);Objects.requireNonNull(request);
        Objects.requireNonNull(sourceEvent);Objects.requireNonNull(idempotencyKey);
        var owner=run.owner();var trace=run.trace();var budget=request.budget();
        if (!snapshot.sessionRef().equals(owner.sessionRef()) || !snapshot.inputId().equals(run.causationInputId())
                || !request.tenantId().equals(owner.sessionRef().tenantId()) || !request.requester().equals(run.requester())
                || !request.agentId().equals(run.agentId()) || !request.sessionRef().equals(owner.sessionRef()) || !request.trace().equals(trace)
                || request.mode()==KnowledgeMode.NONE || !target.sourceVersions().containsAll(request.allowedSourceVersions())
                || !target.sourceVersions().stream().map(KnowledgeSourceVersion::sourceId).collect(Collectors.toSet()).containsAll(request.allowedSourceIds())
                || !target.allowedOperators().containsAll(request.allowedOperators())
                || Duration.ofMillis(budget.timeoutMs()).compareTo(target.timeout())>0)
            throw new IllegalArgumentException("knowledge_resource_scope_mismatch");
        var fields = new ArrayList<>(List.of(owner.sessionRef().tenantId().value(),owner.target().kind().name(),owner.target().id(),run.agentId().value(),owner.sessionRef().sessionId(),OwnerPolicyFields.subject(owner),
                run.runRef().runId(),run.causationInputId().value(),run.causationIdentityRef().value(),trace.traceId(),trace.spanId(),trace.rootInputId().value(),trace.causedByEventId().map(EventId::value).orElse(""),
                snapshot.snapshotId(),Long.toString(snapshot.generation()),target.handle().value(),target.capability().capabilityId(),target.capability().versionId(),
                target.descriptorId(),Long.toString(target.descriptorRevision()),target.timeout().toString(),versions(target.sourceVersions()),operators(target.allowedOperators()),
                request.knowledgeRequestId(),request.mode().name(),request.intent(),operation(request.operation()),digest(request.allowedSourceIds()),versions(request.allowedSourceVersions()),operators(request.allowedOperators()),
                Integer.toString(budget.maxQueries()),Integer.toString(budget.maxItems()),Integer.toString(budget.maxTokens()),Integer.toString(budget.maxCharacters()),Long.toString(budget.timeoutMs()),
                request.asOf().toString(),request.freshnessAfter().map(Instant::toString).orElse(""),Boolean.toString(request.citationRequired()),sourceEvent.value(),idempotencyKey.value()));
        fields.addAll(RunPolicyFields.values(run));return fields;
    }
    private static String operation(KnowledgeOperation operation) {
        return switch (operation) {
            case KnowledgeOperation.Search search -> digest(List.of("search", search.query()));
            case KnowledgeOperation.ListDocuments list -> digest(List.of("list_documents", list.titleFilter(), list.cursor().orElse(""), Integer.toString(list.pageSize())));
            case KnowledgeOperation.ReadDocument read -> digest(List.of("read_document", read.sourceId(), read.documentId(), read.sourceVersion(), Integer.toString(read.offset()), Integer.toString(read.maxCharacters())));
        };
    }

    /** 规范资源只从typed字段编码，完成相同owner解析后才交真实IAM消费。 */
    private static TypedPayload encode(String schema,List<String> fields) {
        var result=new TypedPayload(schema,fields.stream().map(KnowledgePolicyResource::quote).collect(Collectors.joining(",","[","]")));
        if(owner("knowledge.execute",result).isEmpty())throw new IllegalArgumentException("knowledge_resource_invalid");
        return result;
    }

    public static Optional<SessionOwner> owner(String action,TypedPayload payload) {
        if(!"knowledge.execute".equals(action)||payload==null||fieldCount(payload)==0)return Optional.empty();
        try {
            var f=AdmissionResource.flatStrings(payload.canonicalJson(),fieldCount(payload));
            var tenant=new TenantId(f.get(0));var owner=OwnerPolicyFields.owner(tenant,f.get(4),f.get(1),f.get(2),f.get(5));
            var run=RunPolicyFields.restore(owner,new AgentId(f.get(3)),new InputId(f.get(7)),new RunRef(tenant,f.get(6)),new TraceEnvelope(f.get(9),f.get(10),new InputId(f.get(11)),
                    f.get(12).isEmpty()?Optional.empty():Optional.of(new EventId(f.get(12)))),new ResultRef(f.get(8)),f,40);
            new CapabilitySnapshotRef(owner.sessionRef(),run.causationInputId(),f.get(13),positive(f.get(14)));
            var timeout=Duration.parse(f.get(20));if(!timeout.toString().equals(f.get(20)))return Optional.empty();
            new FrozenKnowledgeTarget(new KnowledgeHandle(f.get(15)),new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE,f.get(16),f.get(17)),f.get(18),positive(f.get(19)),Set.of(),readOperators(f.get(22)),timeout);
            for(int index:List.of(21,27,28))if(!f.get(index).matches("[0-9a-f]{64}"))return Optional.empty();
            text(f.get(23));if(KnowledgeMode.valueOf(f.get(24))==KnowledgeMode.NONE)return Optional.empty();text(f.get(25));text(f.get(26));
            if(!readOperators(f.get(22)).containsAll(readOperators(f.get(29))))return Optional.empty();
            new KnowledgeBudget(Math.toIntExact(positive(f.get(30))),Math.toIntExact(positive(f.get(31))),Math.toIntExact(positive(f.get(32))),Math.toIntExact(positive(f.get(33))),positive(f.get(34)));
            if(Duration.ofMillis(positive(f.get(34))).compareTo(timeout)>0)return Optional.empty();
            instant(f.get(35));if(!f.get(36).isEmpty())instant(f.get(36));if(!Set.of("true","false").contains(f.get(37)))return Optional.empty();
            new EventId(f.get(38));new IdempotencyKey(f.get(39));new ActionRef(tenant,f.get(44));
            new ResultRef(f.get(45));text(f.get(46));
            return Optional.of(owner);
        }catch(RuntimeException invalid){return Optional.empty();}
    }

    private static Set<KnowledgeOperatorType> readOperators(String value) {
        if(value.isEmpty())return Set.of();var result=Arrays.stream(value.split(",",-1)).map(KnowledgeOperatorType::valueOf).collect(Collectors.toSet());
        if(!operators(result).equals(value))throw new IllegalArgumentException("knowledge_resource_operators");return result;
    }

    /** 完整知识资源核验后取得冻结 Run 与执行 Agent，供同一次 IAM 评估执行权限。 */
    public static Optional<SessionPolicyResource.ExecutionTarget> executionTarget(String action,TypedPayload resource) {
        if(owner(action,resource).isEmpty())return Optional.empty();
        var fields=AdmissionResource.flatStrings(resource.canonicalJson(),fieldCount(resource));
        return Optional.of(new SessionPolicyResource.ExecutionTarget(
                new RunRef(new TenantId(fields.get(0)),fields.get(6)),new AgentId(fields.get(3))));
    }
    /** 固定schema与字段数共同判定，未知schema及新增尾字段不被宽松接受。 */
    private static int fieldCount(TypedPayload resource) {
        return switch(resource.schemaId()) {
            case "skillforge.runtime.knowledge.execute-resource.v4" -> 47;
            default -> 0;
        };
    }
    private static String operators(Set<KnowledgeOperatorType> values){return values.stream().map(Enum::name).sorted().collect(Collectors.joining(","));}
    private static String versions(Set<KnowledgeSourceVersion> values){return digest(values.stream().map(value->orderedDigest(List.of(value.sourceId(),value.sourceVersion()))).toList());}
    /** 每项带长度前缀，集合排序后摘要；来源/版本pair内部须保留字段顺序。 */
    private static String digest(Collection<String> values) {
        return orderedDigest(values.stream().sorted().toList());
    }
    private static String orderedDigest(List<String> values) {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            for(String value:values) {
                var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
                hash.update(ByteBuffer.allocate(4).putInt(encoded.remaining()).array());hash.update(encoded);
            }
            return HexFormat.of().formatHex(hash.digest());
        }catch(java.security.NoSuchAlgorithmException|java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("knowledge_resource_invalid");}
    }
    private static long positive(String value){long n=Long.parseLong(value);if(n<=0||!Long.toString(n).equals(value))throw new IllegalArgumentException("knowledge_resource_number");return n;}
    private static void instant(String value){if(!Instant.parse(value).toString().equals(value))throw new IllegalArgumentException("knowledge_resource_instant");}
    private static void text(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("knowledge_resource_text");}
    private static String quote(String value){
        var result=new StringBuilder("\"");
        for(int i=0;i<value.length();i++){char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(i+1)))throw new IllegalArgumentException("knowledge_resource_text");result.append(c).append(value.charAt(++i));}
            else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("knowledge_resource_text");
            else if(c=='"'||c=='\\')result.append('\\').append(c);
            else if("\b\t\n\f\r".indexOf(c)>=0)result.append('\\').append("btnfr".charAt("\b\t\n\f\r".indexOf(c)));
            else if(c<32)result.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else result.append(c);
        }return result.append('"').toString();
    }
}
