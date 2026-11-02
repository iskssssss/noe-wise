package com.skillforge.institution.group;

import static com.skillforge.institution.group.GroupMemoryContracts.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.memory.MemoryContent;
import com.skillforge.institution.runtime.AgentRuntimeHostContracts;
import com.skillforge.institution.memory.MemoryGovernanceMapper;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 当前群成员治理同一 MemoryRuntime；群归属、当前操作者与真实来源分别校验。 */
@Service
public class GroupMemoryService {
    private final GroupBindingService groups;
    private final InstitutionIntegrationService integrations;
    private final GroupMemoryMapper mapper;
    private final GroupMemorySources sources;
    private final JdbcMemoryPort memories;
    private final MemoryRuntime runtime;
    private final ObjectMapper json;
    private final MemoryGovernanceMapper governance;

    public GroupMemoryService(GroupBindingService groups, InstitutionIntegrationService integrations,
            GroupMemoryMapper mapper, GroupMemorySources sources, JdbcMemoryPort memories, MemoryRuntime runtime, ObjectMapper json,
            MemoryGovernanceMapper governance) {
        this.groups=groups; this.integrations=integrations; this.mapper=mapper;
        this.sources=sources; this.memories=memories; this.runtime=runtime;
        this.governance=governance;
        this.json=json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Transactional(readOnly=true)
    public List<MemoryFact> list(String group, String reader, String agent, String subject, String scope, String status, int limit) {
        var access=access(group,reader,agent,false,null);
        if(scope!=null&&!Set.of("GROUP","GROUP_MEMBER").contains(scope))throw bad("group_memory_scope_invalid");
        String selectedStatus=status==null?FactStatus.ACTIVE.name():status;
        try { FactStatus.valueOf(selectedStatus); } catch (IllegalArgumentException invalid) { throw bad("memory_status_invalid"); }
        long selected=subject==null?access.user():id(subject);
        // 显式描述对象可已退出；只验证读取者当前资格，描述对象绝不转成调用者。
        if(limit<1||limit>200)throw bad("memory_limit_invalid");
        var result=new ArrayList<MemoryFact>();
        for(String fact:mapper.facts(access.actor().tenantId(),group,selected,scope,selectedStatus,limit)) {
            var value=current(access,fact); requireGroup(access,value);
            if(value.status().name().equals(selectedStatus))result.add(value);
        }
        access(group,reader,agent,false,null);
        return List.copyOf(result);
    }

    @Transactional(rollbackFor=Exception.class)
    public MemoryFact create(Create command,String key) {
        var access=access(command.groupBindingId(),command.operatorEndUserId(),command.agentId(),true,key);
        var binding=new GroupBindingRef(new TenantId(access.actor().tenantId()),command.groupBindingId());
        MemoryOwner owner=switch(command.scope()) {
            case "GROUP" -> { if(command.subjectEndUserId()!=null)throw bad("group_memory_subject_forbidden"); manager(access); yield new MemoryOwner.Group(binding); }
            case "GROUP_MEMBER" -> new MemoryOwner.GroupMember(binding,new EndUserId(id(command.subjectEndUserId())));
            default -> throw bad("group_memory_scope_invalid");
        };
        if(!Set.of("preference","episode").contains(command.type()))throw bad("memory_type_invalid");
        var payload=explicitPayload(command.type(),command.objectJson());
        var rows=sources.messages(access.context(),access.actor().integrationId(),command.groupBindingId(),command.sourceMessageIds());
        boolean pending=command.reviewRequired();
        if(owner instanceof MemoryOwner.GroupMember member) {
            boolean self=member.subjectEndUserId().value()==access.user()
                    && rows.stream().allMatch(row->row.authorEndUserId()==access.user());
            if(!self&&!pending)throw denied("group_memory_subject_source_required");
        }
        var eventIds=sources.project(access.context(),access.actor().integrationId(),command.groupBindingId(),command.sourceMessageIds());
        Instant validFrom=eventIds.stream().map(event->sourcesEvent(access,event).occurredAt()).max(Instant::compareTo).orElseThrow();
        var draft=new MemoryFactDraft(owner,new MemoryTypeRef(command.type()),new RetentionClassRef("standard"),AgentRuntimeHostContracts.MEMORY_SCHEMA_VERSION,
                command.subject(),command.predicate(),payload,
                eventIds,access.context().runIdentity().owner().sessionRef(),Optional.empty(),Optional.empty(),"group-governance-v1",
                validFrom,Optional.empty(),Optional.empty(),command.confidence(),command.importance(),pending,Optional.empty(),
                pending?FactStatus.PENDING_CONFIRM:FactStatus.ACTIVE);
        return recorded(runtime.mutate(new MemoryRuntime.MutationCall(access.context(),
                new MemoryRuntime.Mutation.Create(key(key),draft),validFrom)));
    }

    @Transactional(rollbackFor=Exception.class)
    public MemoryFact review(Review command,String key) {
        var access=access(command.groupBindingId(),command.operatorEndUserId(),command.agentId(),true,key);
        var current=current(access,command.factId()); governance(access,current,command.approved());
        var proposal=MemoryContent.revisionProposal(MemoryContent.decode(current.object().canonicalJson()));
        if(command.approved()&&proposal.isPresent()) {
            var target=proposal.orElseThrow();
            requireRevisionBinding(access,current,target);
            var mutation=new MemoryRuntime.Mutation.AcceptRevision(key(key),
                    new MemoryFactRef(new TenantId(access.actor().tenantId()),target.factId()),target.expectedRevision(),
                    current.ref(),command.expectedRevision(),current.scope(),current.type());
            return recorded(runtime.mutate(new MemoryRuntime.MutationCall(access.context(),mutation,
                    revisionTime(access,current,command.expectedRevision()))));
        }
        return transition(access,current,command.expectedRevision(),command.approved()?FactStatus.ACTIVE:FactStatus.REJECTED,
                command.approved()?"group_memory_review_approved":"group_memory_review_rejected",key);
    }

    @Transactional(rollbackFor=Exception.class)
    public MemoryFact correct(Correct command,String key) {
        var access=access(command.groupBindingId(),command.operatorEndUserId(),command.agentId(),true,key);
        var current=current(access,command.factId()); governance(access,current,true);
        if(current.status()==FactStatus.PENDING_CONFIRM)throw bad("group_memory_review_required");
        var draft=new MemoryFactDraft(current.owner(),current.type(),current.retentionClass(),current.schemaVersion(),
                text(command.subject(),current.subject()),text(command.predicate(),current.predicate()),
                validatedPayload(current.type().value(),command.objectJson()),current.sourceEventIds(),current.sourceSessionRef(),
                current.sourceExecutionRef(),current.exportPath(),"group-governance-v1",current.validFrom(),Optional.empty(),current.expiresAt(),
                command.confidence(),command.importance(),false,Optional.of(current.ref()),FactStatus.ACTIVE);
        return recorded(runtime.mutate(new MemoryRuntime.MutationCall(access.context(),new MemoryRuntime.Mutation.Supersede(
                key(key),current.ref(),command.expectedRevision(),draft),current.createdAt())));
    }

    private TypedPayload explicitPayload(String type,String valueJson) {
        try {
            return new TypedPayload(payloadSchema(type),MemoryContent.encode(
                    MemoryContent.explicitContent(json.readTree(valueJson),type)));
        } catch (java.io.IOException | IllegalArgumentException invalid) { throw bad("memory_payload_rejected"); }
    }
    private TypedPayload validatedPayload(String type,String contentJson) {
        try {
            return new TypedPayload(payloadSchema(type),MemoryContent.encode(MemoryContent.decode(contentJson)));
        } catch (IllegalArgumentException invalid) { throw bad("memory_payload_rejected"); }
    }
    private static String payloadSchema(String type) {
        return switch(type) {
            case "preference" -> AgentRuntimeHostContracts.MEMORY_PREFERENCE_PAYLOAD;
            case "episode" -> AgentRuntimeHostContracts.MEMORY_EPISODE_PAYLOAD;
            default -> throw bad("memory_type_invalid");
        };
    }

    @Transactional(rollbackFor=Exception.class)
    public MemoryFact forget(Revision command,String key) { return change(command,key,FactStatus.FORGOTTEN,"group_memory_forgotten"); }
    @Transactional(rollbackFor=Exception.class)
    public MemoryFact restore(Revision command,String key) { return change(command,key,FactStatus.ACTIVE,"group_memory_restored"); }

    private MemoryFact change(Revision command,String key,FactStatus status,String reason) {
        var access=access(command.groupBindingId(),command.operatorEndUserId(),command.agentId(),true,key);
        var current=current(access,command.factId()); governance(access,current,status==FactStatus.ACTIVE);
        if(status==FactStatus.ACTIVE&&current.status()==FactStatus.PENDING_CONFIRM)throw bad("group_memory_review_required");
        return transition(access,current,command.expectedRevision(),status,reason,key);
    }
    private MemoryFact transition(Access access,MemoryFact current,long revision,FactStatus target,String reason,String key) {
        return recorded(runtime.mutate(new MemoryRuntime.MutationCall(access.context(),new MemoryRuntime.Mutation.Transition(
                key(key),current.ref(),revision,current.scope(),current.type(),target,reason),revisionTime(access,current,revision))));
    }

    /** 同键重投仍绑定原候选修订，当前head推进不改变原命令时间。 */
    private Instant revisionTime(Access access,MemoryFact current,long revision) {
        return memories.findRevision(access.context(),current.ref(),revision)
                .orElseThrow(()->new ApiException(HttpStatus.CONFLICT,"memory_revision_conflict:"+current.revision())).createdAt();
    }

    /** 只消费正式提取准备记录签发的修订关系；模型正文不能单独授权替代。 */
    private void requireRevisionBinding(Access access,MemoryFact candidate,MemoryContent.RevisionProposal proposal) {
        var targetRef=new MemoryFactRef(new TenantId(access.actor().tenantId()),proposal.factId());
        if(!candidate.derivedFrom().equals(Optional.of(targetRef)))throw bad("memory_revision_proposal_invalid");
        var bindings=governance.revisionTargets(access.actor().tenantId(),candidate.ref().factId());
        if(bindings.size()!=1)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"memory_revision_proposal_not_prepared");
        var binding=bindings.getFirst();
        if(!binding.targetFactId().equals(proposal.factId())||binding.targetRevision()!=proposal.expectedRevision())
            throw bad("memory_revision_proposal_invalid");
        var target=current(access,proposal.factId());
        if(!target.owner().equals(candidate.owner())||!target.type().equals(candidate.type())
                ||!target.subject().equals(candidate.subject())||!target.predicate().equals(candidate.predicate()))
            throw denied("group_memory_revision_target_mismatch");
        governance(access,target,true);
    }
    private MemoryFact current(Access access,String fact) {
        var current=memories.findCurrent(access.context(),new MemoryFactRef(new TenantId(access.actor().tenantId()),fact))
                .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"memory_fact_not_found"));
        requireGroup(access,current); return current;
    }
    private void requireGroup(Access access,MemoryFact fact) {
        var ref=switch(fact.owner()) {case MemoryOwner.Group owner->owner.groupBindingRef();
            case MemoryOwner.GroupMember owner->owner.groupBindingRef(); default->throw denied("group_memory_owner_denied");};
        if(!ref.groupBindingId().equals(access.group())||!ref.tenantId().value().equals(access.actor().tenantId()))
            throw denied("group_memory_owner_denied");
    }
    /** 本人拒绝/遗忘只收窄使用；激活、恢复和纠正仍要求本人明确提供的来源。 */
    private void governance(Access access,MemoryFact fact,boolean selfSourceRequired) {
        requireGroup(access,fact);
        if(fact.owner() instanceof MemoryOwner.Group) { manager(access); return; }
        var member=(MemoryOwner.GroupMember)fact.owner();
        if(member.subjectEndUserId().value()!=access.user())throw denied("group_memory_subject_governance_denied");
        var originalSources=sources.read(access.context(),access.actor().integrationId(),access.group(),fact.sourceEventIds());
        if(selfSourceRequired&&originalSources.stream().anyMatch(row->row.authorEndUserId()!=access.user()))
            throw denied("group_memory_subject_source_required");
    }
    private void manager(Access access) { groups.requireMember(access.actor().tenantId(),access.actor().integrationId(),access.group(),access.user(),true); }
    private Access access(String group,String user,String agent,boolean write,String requestKey) {
        var actor=ServicePrincipalContext.get();
        if(actor==null||!Objects.equals(actor.tenantId(),TenantContext.getTenantId()))throw denied("group_memory_principal_required");
        integrations.requirePrincipal(actor,write?"groups.memory.write":"groups.memory.read");
        long member=id(user),serviceAgent=id(agent); id(group);
        var binding=groups.requireActive(actor.tenantId(),actor.integrationId(),group);
        groups.requireMember(actor.tenantId(),actor.integrationId(),group,member,false);
        groups.requireServiceAgent(actor.tenantId(),actor.integrationId(),group,serviceAgent);
        if(write&&!mapper.lockEligibility(Long.parseLong(actor.tenantId()),actor.integrationId(),id(group),member,
                serviceAgent,actor.principalId(),actor.credentialVersion()))throw denied("group_memory_current_qualification_denied");
        TenantId tenant=new TenantId(actor.tenantId()); var session=new SessionRef(tenant,binding.sessionId());
        var owner=new SessionOwner(session,SessionSubject.group(new GroupBindingRef(tenant,group)),SessionTarget.group(new GroupBindingRef(tenant,group)));
        String suffix=requestKey==null?UUID.randomUUID().toString():UUID.nameUUIDFromBytes((actor.tenantId()+":"+group+":"+user+":"+agent+":"+key(requestKey).value()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); var input=new InputId("group-memory:"+suffix);
        var principal=new PrincipalRef.ServicePrincipal(tenant,actor.principalId(),actor.integrationId(),actor.credentialVersion());
        var audit=new ResultRef("group-memory-identity:"+suffix);
        var run=new RunIdentity(owner,new AgentId(agent),input,new RunRef(tenant,"group-memory:"+suffix),
                new TraceEnvelope(input.value(),input.value(),input,Optional.empty()),audit,
                new RunRequester.EndUser(new EndUserId(member)),Optional.empty(),new RunOriginRef(tenant,input.value()));
        var verified=new VerifiedIdentity(principal,owner,Set.of(write?"memory:group:manage":"memory:group:read"),1,audit);
        return new Access(actor,group,member,new PortCallContext(run,verified,new InvocationControl(Instant.now().plusSeconds(30),()->false)));
    }
    private CommittedSessionEvent sourcesEvent(Access access,EventId event) { return sources.event(access.context(),event); }
    private static MemoryFact recorded(MemoryRuntime.MutationResult result) {
        if(result instanceof MemoryRuntime.MutationResult.Recorded done)return done.fact();
        if(result instanceof MemoryRuntime.MutationResult.Conflict conflict)throw new ApiException(HttpStatus.CONFLICT,"memory_revision_conflict:"+conflict.currentRevision());
        if(result instanceof MemoryRuntime.MutationResult.Pending || result instanceof MemoryRuntime.MutationResult.Uncertain)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"MEMORY_OUTCOME_UNKNOWN",
                    "原记忆命令的提交或审计回执尚未确认，请保留原Idempotency-Key与完整请求对账",false);
        String reason=switch(result) {case MemoryRuntime.MutationResult.Rejected value->value.failure().reasonCode();
            case MemoryRuntime.MutationResult.Pending value->value.failure().reasonCode();case MemoryRuntime.MutationResult.Uncertain value->value.failure().reasonCode();
            case MemoryRuntime.MutationResult.Stopped value->value.failure().reasonCode();default->throw new IllegalStateException("memory_mutation_result_invalid");};
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,reason);
    }
    private record Access(ServicePrincipalContext.Identity actor,String group,long user,PortCallContext context) { }
    private static long id(String value) { try {if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw new NumberFormatException(); return Long.parseLong(value);}catch(NumberFormatException invalid){throw bad("group_memory_id_invalid");} }
    private static String text(String value,String fallback) {return value==null||value.isBlank()?fallback:value;}
    private static IdempotencyKey key(String value) {if(value==null||value.isBlank()||value.length()>256)throw bad("memory_idempotency_key_invalid");return new IdempotencyKey(value);}
    private static ApiException denied(String reason) {return new ApiException(HttpStatus.FORBIDDEN,reason);}
    private static ApiException bad(String reason) {return new ApiException(HttpStatus.BAD_REQUEST,reason);}
}
