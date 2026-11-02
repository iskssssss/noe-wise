package com.skillforge.core.agent.integration.runtime.session;

import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionCapabilityCodec;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.identity.SessionCapabilityPolicyResource;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.IdentityPolicyPort.*;
import com.skillforge.runtime.session.spi.SessionCapabilities;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** V90 原挂载中的 Skill、Workflow、Knowledge 一次冻结到 V116；Tool 仅提供当前目录。 */
public final class JdbcSessionCapabilities implements SessionCapabilities {
    private static final System.Logger LOG = System.getLogger(JdbcSessionCapabilities.class.getName());
    private final SqlSessionFactory sessions;
    private final JdbcIdentityPolicyPort identities;
    private final Set<String> toolProviderTypes;
    private final JdbcWorkflowBindings workflows;
    private final JdbcKnowledgeBindings knowledge;
    public JdbcSessionCapabilities(DataSource source,JdbcIdentityPolicyPort identities){
        this(source,identities,Set.of("MCP"));
    }
    /** 宿主只声明可进入当前目录的 Provider 类型；Provider binding 由目录边界完整复核。 */
    public JdbcSessionCapabilities(DataSource source,JdbcIdentityPolicyPort identities,
            Set<String> toolProviderTypes){
        this.toolProviderTypes=Set.copyOf(Objects.requireNonNull(toolProviderTypes));
        if(this.toolProviderTypes.isEmpty()||this.toolProviderTypes.stream().anyMatch(value->
                value.isBlank()||!value.matches("[A-Z][A-Z0-9_]*")))throw invalid();
        this.identities=Objects.requireNonNull(identities);Objects.requireNonNull(source);
        workflows=new JdbcWorkflowBindings(source);knowledge=new JdbcKnowledgeBindings(source);
        var config=new Configuration(new Environment("session-capabilities",new JdbcTransactionFactory(),source));
        config.setMapUnderscoreToCamelCase(true);String resource="mapper/AgentRuntimeSessionCapabilitiesMapper.xml";
        try(InputStream xml=getClass().getClassLoader().getResourceAsStream(resource)){
            if(xml==null)throw invalid();new XMLMapperBuilder(xml,config,resource,config.getSqlFragments()).parse();
        }catch(Exception failure){throw new IllegalStateException("session_capabilities_initialization_failed",failure);}
        sessions=new SqlSessionFactoryBuilder().build(config);
    }
    @Override public Snapshot freeze(VerifiedIdentity current,AgentId actualAgentId,InputId input,Targets candidates){
        Objects.requireNonNull(current);Objects.requireNonNull(actualAgentId);Objects.requireNonNull(input);Objects.requireNonNull(candidates);
        var owner=current.owner();var authorized=authorize(current,input);
        requireExecutionAgent(owner,actualAgentId);
        try(var session=sessions.openSession(TransactionIsolationLevel.READ_COMMITTED)){
            var mapper=mapper(session,owner);mapper.lockInput(owner,input.value());
            var old=mapper.find(owner,input.value());
            if(old!=null){
                var original=restore(owner,input,old);
                if(!old.agentId.equals(actualAgentId.value()))throw invalid();
                session.commit();return original;
            }
            // 与管理面的装配代行锁相同，整个全部mount读取与freeze期间不能混入下一代mutation。
            var resolved=resolveNonToolMounts(mapper,owner,actualAgentId,input);
            var generation=resolved.generation();
            if(candidates.generation()!=generation)throw invalid();
            var source=resolved.mounts();var mounts=new ArrayList<Mount>();
            int workflowCount=0,knowledgeCount=0;
            for(var row:source){
                var type=CapabilityType.valueOf(row.capabilityType);
                if(type==CapabilityType.TOOL)continue;
                if(!row.valid||row.descriptor==null)throw invalid();
                var capability=new CapabilityRef(type,row.capabilityId,row.versionId);
                var descriptor=new TypedPayload("skillforge.runtime.capability.descriptor.v1",row.descriptor);
                Optional<Skill> skill=Optional.empty();
                Optional<com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget> workflow=Optional.empty();
                Optional<com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget> known=Optional.empty();
                switch(capability.type()){
                    case SKILL -> skill=Optional.of(new Skill(row.activationMode,row.tokenBudget));
                    case TOOL -> throw invalid();
                    case WORKFLOW -> {
                        var found=candidates.workflows().stream().filter(t->t.handle().value().equals(workflowHandle(row,input))).toList();
                        if(found.size()!=1)throw invalid();var target=found.getFirst();
                        if(!target.capability().equals(capability)||!target.instanceId().equals(row.instanceId)
                                ||target.instanceRevision()!=row.instanceRevision||target.installationRevision()!=row.installationRevision
                                ||workflows.find(owner.sessionRef().tenantId(),target).isEmpty())throw invalid();
                        workflow=Optional.of(target);workflowCount++;
                    }
                    case KNOWLEDGE_SOURCE -> {
                        var found=candidates.knowledge().stream().filter(t->t.handle().value().equals("knowledge:"+row.mountId)).toList();
                        if(found.size()!=1)throw invalid();var target=found.getFirst();
                        if(!target.capability().equals(capability)||!target.descriptorId().equals(row.knowledgeId)
                                ||target.descriptorRevision()!=row.knowledgeRevision||knowledge.find(owner.sessionRef().tenantId(),target).isEmpty())throw invalid();
                        known=Optional.of(target);knowledgeCount++;
                    }
                }
                mounts.add(new Mount(row.mountId,row.revision,row.priority,capability,descriptor,skill,workflow,known,
                        Optional.ofNullable(row.knowledgeRevisionId)));
            }
            if(workflowCount!=candidates.workflows().size()||knowledgeCount!=candidates.knowledge().size())throw invalid();
            var reference=new CapabilitySnapshotRef(owner.sessionRef(),input,input.value()+":capability-mount-snapshot",generation);
            // 真实registry读取之后再次检查当前IAM；失败整个snapshot事务回滚。
            authorize(current,input);
            var snapshot=new Snapshot(owner,reference,current,authorized.auditRef(),mounts,mapper.now());
            if(mapper.insert(owner,actualAgentId.value(),input.value(),reference.snapshotId(),generation,JdbcSessionCapabilityCodec.encode(snapshot))!=1)throw invalid();
            session.commit();return snapshot;
        }catch(RuntimeException failure){throw new IllegalStateException("session_capabilities_freeze_failed",failure);}
    }
    /**
     * 从当前 ACTIVE 挂载与权威 Catalog 重建当前候选；不代替已发布 binding。
     *
     * <p>宿主必须先按返回值发布 JDBC binding，再把同一批候选交给 {@link #freeze}。
     * 挂载无效或缺发布字段时失败关闭，不跳过。</p>
     */
    public Catalog catalog(SessionOwner owner,AgentId actualAgentId){
        if(owner.target().kind()!=SessionTarget.Kind.AGENT)throw new IllegalArgumentException("team catalog requires execution input");
        return catalog(owner,actualAgentId,null);
    }

    /** 团队目录通过稳定输入查持久 Run 绑定，不使用团队最新发布指针。 */
    public Catalog catalog(SessionOwner owner,AgentId actualAgentId,InputId input){
        Objects.requireNonNull(owner);Objects.requireNonNull(actualAgentId);
        requireExecutionAgent(owner,actualAgentId);
        try(var session=sessions.openSession(false)){
            var mapper=mapper(session,owner);
            var resolved=resolveNonToolMounts(mapper,owner,actualAgentId,input);
            var generation=resolved.generation();
            var workflows=new ArrayList<com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget>();
            var knowledge=new ArrayList<com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget>();
            for(var row:resolved.mounts()){
                var type=CapabilityType.valueOf(row.capabilityType);
                if(type==CapabilityType.TOOL)continue;
                if(!row.valid||row.descriptor==null)throw invalid();
                switch(type){
                    case SKILL -> { }
                    case TOOL -> throw invalid();
                    case WORKFLOW -> workflows.add(workflowTarget(row,input));
                    case KNOWLEDGE_SOURCE -> knowledge.add(knowledgeTarget(row));
                }
            }
            session.commit();
            return new Catalog(generation,List.copyOf(workflows),List.copyOf(knowledge));
        }catch(IllegalArgumentException rejected){
            throw rejected;
        }catch(RuntimeException failure){
            throw new IllegalStateException("session_capability_catalog_failed",failure);
        }
    }

    @Override public Optional<Snapshot> read(SessionOwner owner,InputId input){
        Objects.requireNonNull(owner);Objects.requireNonNull(input);
        try {
            SessionCapabilityRow row;
            // 快照与原身份均不可变；先释放读取连接，再核验 IAM，避免准备冻结形成三层连接占用。
            try(var session=sessions.openSession(false)) {
                row=mapper(session,owner).find(owner,input.value());
            }
            return row==null?Optional.empty():Optional.of(restore(owner,input,row));
        }catch(RuntimeException failure){throw new IllegalStateException("session_capabilities_read_failed",failure);}
    }
    private Snapshot restore(SessionOwner owner,InputId input,SessionCapabilityRow row){
        var snapshot=JdbcSessionCapabilityCodec.decode(row.snapshotData);var ref=snapshot.reference();
        if(!snapshot.owner().equals(owner)||!ref.inputId().equals(input)||!row.tenantId.equals(owner.sessionRef().tenantId().value())
                ||!row.sessionId.equals(owner.sessionRef().sessionId())
                ||!row.targetKind.equals(owner.target().kind().name())||!row.targetId.equals(owner.target().id())
                ||row.agentId==null||row.agentId.isBlank()
                ||!Objects.equals(row.endUserId,owner.subject() instanceof SessionSubject.User user?user.endUserId().value():null)||!row.inputId.equals(input.value())||!row.snapshotId.equals(ref.snapshotId())
                ||row.generation!=ref.generation()||!identities.originalVerification(owner,snapshot.originalIdentity().verificationRef()).equals(Optional.of(snapshot.originalIdentity())))throw invalid();
        return snapshot;
    }
    private AuthorizationResult.Allowed authorize(VerifiedIdentity current,InputId input){
        var result=identities.authorize(new AuthorizationRequest(current,SessionCapabilityPolicyResource.ACTION,
                SessionCapabilityPolicyResource.resource(current.owner(),input),"capability-freeze","capability-freeze"));
        if(!(result instanceof AuthorizationResult.Allowed allowed))throw invalid();return allowed;
    }
    private ResolvedMounts resolveNonToolMounts(SessionCapabilitiesMapper mapper,
            SessionOwner owner,AgentId actualAgentId,InputId input){
        String tenant=owner.sessionRef().tenantId().value();
        mapper.ensureGeneration(tenant,actualAgentId.value());
        Long currentGeneration=mapper.generation(tenant,actualAgentId.value());
        if(currentGeneration==null||currentGeneration<=0)throw invalid();
        RunCapabilityBinding binding=input==null?null:mapper.runBinding(owner,actualAgentId.value(),input.value());
        var current=mapper.mounts(tenant,actualAgentId.value(),toolProviderTypes,nonToolTypes(),
                binding==null?null:binding.frozenMember);
        return resolveCurrentOrFrozen(owner.target().kind(),input,currentGeneration,current,binding);
    }
    private static ResolvedMounts resolveCurrentOrFrozen(SessionTarget.Kind target,InputId input,long currentGeneration,
            List<SessionCapabilityMountRow> current,RunCapabilityBinding binding){
        if(binding==null){
            if(target==SessionTarget.Kind.AGENT)return new ResolvedMounts(currentGeneration,current);
            throw invalid();
        }
        var frozen=FrozenMemberMounts.parse(binding.frozenMember);
        var currentById=new LinkedHashMap<String,SessionCapabilityMountRow>();
        for(var row:current){
            if(currentById.putIfAbsent(row.mountId,row)!=null)throw invalid();
        }
        var selected=new ArrayList<SessionCapabilityMountRow>();
        for(var mount:frozen.mounts()){
            if(!mount.active()||!nonToolTypes().contains(mount.capabilityType()))continue;
            var row=currentById.get(mount.mountId());
            if(row==null||!row.capabilityType.equals(mount.capabilityType())
                    ||!row.capabilityId.equals(mount.capabilityId())
                    ||!row.versionId.equals(mount.versionId()))throw invalid();
            selected.add(row);
        }
        return new ResolvedMounts(frozen.generation(),List.copyOf(selected));
    }
    /** 运行绑定保存的是宿主冻结的成员快照；适配器只读取能力校验所需字段。 */
    private record FrozenMemberMounts(long generation,List<FrozenMount> mounts){
        private FrozenMemberMounts{
            if(generation<=0)throw invalid();mounts=List.copyOf(mounts);
        }
        static FrozenMemberMounts parse(String source){
            var root=FrozenMemberJson.object(source);
            var mounts=requiredObject(root,"mounts");
            long generation=requiredPositiveLong(mounts,"generation");
            var entries=requiredArray(mounts,"mounts");
            var result=new ArrayList<FrozenMount>();var ids=new HashSet<String>();
            for(var value:entries){
                if(!(value instanceof Map<?,?> raw))throw invalid();
                var mount=object(raw);
                String mountId=requiredString(mount,"mount_id");
                if(!ids.add(mountId))throw invalid();
                long revision=requiredPositiveLong(mount,"revision");
                boolean active=requiredBoolean(mount,"active");
                var capability=requiredObject(mount,"capability");
                String type=requiredString(capability,"type");
                try{CapabilityType.valueOf(type);}catch(IllegalArgumentException rejected){throw invalid();}
                result.add(new FrozenMount(mountId,revision,type,requiredString(capability,"capability_id"),
                        requiredString(capability,"version_id"),active));
            }
            return new FrozenMemberMounts(generation,result);
        }
        @SuppressWarnings("unchecked")
        private static Map<String,Object> object(Map<?,?> value){
            var result=new LinkedHashMap<String,Object>();
            for(var entry:value.entrySet()){
                if(!(entry.getKey() instanceof String key)||result.containsKey(key))throw invalid();
                result.put(key,entry.getValue());
            }
            return result;
        }
        private static Map<String,Object> requiredObject(Map<String,Object> value,String key){
            Object found=value.get(key);if(!(found instanceof Map<?,?> raw))throw invalid();return object(raw);
        }
        private static List<?> requiredArray(Map<String,Object> value,String key){
            Object found=value.get(key);if(!(found instanceof List<?> list))throw invalid();return list;
        }
        private static String requiredString(Map<String,Object> value,String key){
            Object found=value.get(key);if(!(found instanceof String text)||text.isBlank())throw invalid();return text;
        }
        private static boolean requiredBoolean(Map<String,Object> value,String key){
            Object found=value.get(key);if(!(found instanceof Boolean flag))throw invalid();return flag;
        }
        private static long requiredPositiveLong(Map<String,Object> value,String key){
            Object found=value.get(key);if(!(found instanceof FrozenMemberJson.NumberToken number)
                    ||!number.value().matches("[1-9][0-9]*"))throw invalid();
            try{return Long.parseLong(number.value());}catch(NumberFormatException rejected){throw invalid();}
        }
    }
    private record FrozenMount(String mountId,long revision,String capabilityType,String capabilityId,String versionId,boolean active){ }
    /** JDBC 适配器不引入宿主 JSON 依赖，完整读取 JSON 后只接受冻结能力所需的严格形状。 */
    private static final class FrozenMemberJson {
        private final String source;private int position;
        private FrozenMemberJson(String source){this.source=source;}
        static Map<String,Object> object(String source){
            if(source==null||source.isBlank())throw invalid();
            var reader=new FrozenMemberJson(source);Object value=reader.value();reader.space();
            if(reader.position!=source.length()||!(value instanceof Map<?,?> raw))throw invalid();
            return FrozenMemberMounts.object(raw);
        }
        private Object value(){
            space();if(position>=source.length())throw invalid();
            return switch(source.charAt(position)){
                case '{' -> objectValue();case '[' -> arrayValue();case '"' -> string();
                case 't' -> literal("true",Boolean.TRUE);case 'f' -> literal("false",Boolean.FALSE);
                case 'n' -> literal("null",null);default -> number();
            };
        }
        private Map<String,Object> objectValue(){
            position++;space();var result=new LinkedHashMap<String,Object>();
            if(consume('}'))return result;
            while(true){
                space();if(position>=source.length()||source.charAt(position)!='"')throw invalid();
                String key=string();space();expect(':');Object item=value();
                if(result.containsKey(key))throw invalid();result.put(key,item);space();
                if(consume('}'))return result;expect(',');
            }
        }
        private List<Object> arrayValue(){
            position++;space();var result=new ArrayList<Object>();
            if(consume(']'))return result;
            while(true){
                result.add(value());space();if(consume(']'))return result;expect(',');
            }
        }
        private String string(){
            expect('"');var result=new StringBuilder();
            while(position<source.length()){
                char value=source.charAt(position++);if(value=='"')return result.toString();
                if(value<' ')throw invalid();
                if(value!='\\'){result.append(value);continue;}
                if(position>=source.length())throw invalid();char escape=source.charAt(position++);
                switch(escape){
                    case '"','\\','/' -> result.append(escape);case 'b' -> result.append('\b');case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');case 'r' -> result.append('\r');case 't' -> result.append('\t');
                    case 'u' -> result.append((char)hex());default -> throw invalid();
                }
            }
            throw invalid();
        }
        private int hex(){
            if(position+4>source.length())throw invalid();int result=0;
            for(int index=0;index<4;index++){
                int digit=Character.digit(source.charAt(position++),16);if(digit<0)throw invalid();result=(result<<4)|digit;
            }
            return result;
        }
        private NumberToken number(){
            int start=position;if(consume('-')){ }
            if(position>=source.length())throw invalid();
            if(source.charAt(position)=='0')position++;
            else {if(!digit(source.charAt(position)))throw invalid();while(position<source.length()&&digit(source.charAt(position)))position++;}
            if(consume('.')){if(position>=source.length()||!digit(source.charAt(position)))throw invalid();while(position<source.length()&&digit(source.charAt(position)))position++;}
            if(position<source.length()&&(source.charAt(position)=='e'||source.charAt(position)=='E')){
                position++;if(position<source.length()&&(source.charAt(position)=='+'||source.charAt(position)=='-'))position++;
                if(position>=source.length()||!digit(source.charAt(position)))throw invalid();while(position<source.length()&&digit(source.charAt(position)))position++;
            }
            return new NumberToken(source.substring(start,position));
        }
        private Object literal(String text,Object value){
            if(!source.startsWith(text,position))throw invalid();position+=text.length();return value;
        }
        private boolean consume(char expected){if(position<source.length()&&source.charAt(position)==expected){position++;return true;}return false;}
        private void expect(char expected){if(!consume(expected))throw invalid();}
        private void space(){while(position<source.length()&&Character.isWhitespace(source.charAt(position)))position++;}
        private static boolean digit(char value){return value>='0'&&value<='9';}
        private record NumberToken(String value){ }
    }
    private static void requireExecutionAgent(SessionOwner owner,AgentId actualAgentId){
        if(owner.target().kind()==SessionTarget.Kind.AGENT
                && !owner.target().requireAgent().equals(actualAgentId))throw invalid();
    }
    private static SessionCapabilitiesMapper mapper(SqlSession session,SessionOwner owner){
        var mapper=session.getMapper(SessionCapabilitiesMapper.class);mapper.bindTenant(owner.sessionRef().tenantId().value());return mapper;
    }
    /**
     * 从权威挂载身份构造原精确执行 handle；不解析或裁剪调用方传入的不透明 handle。
     * input 为空时返回该挂载的稳定模型身份，非空时保留原有输入隔离绑定格式。
     */
    public static ToolPortal.ToolHandle toolHandle(String mountId, InputId input) {
        LOG.log(System.Logger.Level.DEBUG, "构造工具挂载身份进入 inputScoped={0}", input != null);
        try {
            if (mountId == null || mountId.isBlank() || !mountId.equals(mountId.trim())) throw invalid();
            return new ToolPortal.ToolHandle("tool:" + mountId + (input == null ? "" : ":" + input.value()));
        } finally {
            LOG.log(System.Logger.Level.DEBUG, "构造工具挂载身份退出 inputScoped={0}", input != null);
        }
    }
    private static String workflowHandle(SessionCapabilityMountRow row,InputId input){
        return "workflow:"+row.mountId+(input==null?"":":"+input.value());
    }
    private static com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget workflowTarget(SessionCapabilityMountRow row,InputId input){
        if(row.instanceId==null||row.instanceId.isBlank()||row.instanceRevision<=0)throw invalid();
        long timeout=row.timeoutMs==null||row.timeoutMs<=0?300_000L:row.timeoutMs;
        return new com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget(
                new com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle(workflowHandle(row,input)),
                row.instanceId,row.instanceRevision,
                new CapabilityRef(CapabilityType.WORKFLOW,row.capabilityId,row.versionId),
                Duration.ofMillis(timeout), row.installationRevision);
    }
    private static com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget knowledgeTarget(SessionCapabilityMountRow row){
        if(row.knowledgeId==null||row.knowledgeId.isBlank()||row.knowledgeRevision<=0
                ||row.sourceVersions==null||row.sourceVersions.isBlank()
                ||row.allowedOperators==null||row.allowedOperators.isBlank())throw invalid();
        var versions=new LinkedHashSet<KnowledgeSourceVersion>();
        for(String version:row.sourceVersions.split(",")){
            if(version.isBlank())throw invalid();
            versions.add(new KnowledgeSourceVersion(row.knowledgeId,version));
        }
        var operators=new LinkedHashSet<KnowledgeOperatorType>();
        for(String operator:row.allowedOperators.split(",")){
            if(operator.isBlank())throw invalid();
            operators.add(KnowledgeOperatorType.valueOf(operator));
        }
        long timeout=row.timeoutMs==null||row.timeoutMs<=0?30_000L:row.timeoutMs;
        return new com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget(
                new com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeHandle("knowledge:"+row.mountId),
                new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE,row.capabilityId,row.versionId),
                row.knowledgeId,row.knowledgeRevision,versions,operators,Duration.ofMillis(timeout));
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("session_capability_source_rejected");}
    private static Set<String> nonToolTypes(){
        return Set.of(CapabilityType.SKILL.name(),CapabilityType.WORKFLOW.name(),
                CapabilityType.KNOWLEDGE_SOURCE.name());
    }
    private record ResolvedMounts(long generation,List<SessionCapabilityMountRow> mounts){
        private ResolvedMounts{if(generation<=0)throw invalid();mounts=List.copyOf(mounts);}
    }
    /** 宿主读取的当前 Catalog 候选。 */
    public record Catalog(long generation,
            List<com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget> workflows,
            List<com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget> knowledge){
        public Catalog{
            if(generation<=0)throw invalid();workflows=List.copyOf(workflows);knowledge=List.copyOf(knowledge);
        }
    }

}
interface SessionCapabilitiesMapper {
    String bindTenant(@Param("tenant")String tenant);
    String lockInput(@Param("owner")SessionOwner owner,@Param("input")String input);
    int ensureGeneration(@Param("tenant")String tenant,@Param("agent")String agent);
    Long generation(@Param("tenant")String tenant,@Param("agent")String agent);
    List<SessionCapabilityMountRow> mounts(@Param("tenant")String tenant,@Param("agent")String agent,
            @Param("toolProviderTypes")Set<String> toolProviderTypes,
            @Param("capabilityTypes")Set<String> capabilityTypes,@Param("frozenMember")String frozenMember);
    RunCapabilityBinding runBinding(@Param("owner")SessionOwner owner,@Param("agent")String agent,@Param("input")String input);
    Instant now();
    SessionCapabilityRow find(@Param("owner")SessionOwner owner,@Param("input")String input);
    int insert(@Param("owner")SessionOwner owner,@Param("agent")String agent,
               @Param("input")String input,@Param("snapshot")String snapshot,
               @Param("generation")long generation,@Param("data")String data);
}

final class RunCapabilityBinding { public String frozenMember; }
final class SessionCapabilityRow {
    public String tenantId,sessionId,targetKind,targetId,agentId,inputId,snapshotId,snapshotData;
    public Long endUserId;public long generation;
}
final class SessionCapabilityMountRow {
    public String mountId,capabilityType,capabilityId,versionId,descriptor,activationMode,deploymentId,instanceId,knowledgeId,knowledgeRevisionId;
    public String inputSchema,outputSchema,effect,toolName,sourceVersions,allowedOperators,providerType,providerRef;
    public String confirmationPolicy,sensitiveFields,providerRequirements;
    public Long timeoutMs;public Boolean idempotent,modelInvocable,outputSchemaDeclared;
    public long revision,tokenBudget,deploymentRevision,instanceRevision,installationRevision,knowledgeRevision;public int priority;public boolean valid;
}
