package com.skillforge.institution.runtime;

import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.chat.contract.ChatInputReference;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.institution.media.SessionMediaService;
import com.skillforge.core.chat.contract.ChatReferenceView;
import com.skillforge.core.chat.contract.ProcessPresentation;
import com.skillforge.core.capability.connector.CodeExecutionArguments.ArtifactInput;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.artifact.ArtifactVersionService;
import com.skillforge.institution.artifact.ArtifactProvenance;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.artifact.DeliverableReader;
import com.skillforge.institution.attachment.SessionAttachmentService;
import com.skillforge.institution.team.MemberTaskDetails;
import com.skillforge.runtime.api.TypedPayload;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 公开材料选择器进入现有输入 ledger；权限、内容与版本均由宿主事实解析。 */
@Service
public final class ChatInputReferenceResolver {
    private static final String INPUT_SCHEMA="input.v1";
    private static final String ENCODING_ERROR="chat_reference_encoding_invalid";
    private static final String SNAPSHOT_ERROR="chat_reference_snapshot_invalid";
    private static final TypeReference<List<ChatInputReference>> REFERENCES=new TypeReference<>(){};
    private final ObjectMapper json;
    private final ArtifactVersionService artifacts;
    private final DeliverableReader deliverables;
    private final MemoryService messages;
    private final AgentRuntimeExecutionProjection projection;
    private final MemberTaskDetails members;
    private final SessionAttachmentService attachments;
    private final PublicOutputGuard guard;
    private final ArtifactAccess access;
    private final com.skillforge.core.chat.access.SessionAccessPolicy sessions;
    private final SessionMediaService media;

    public ChatInputReferenceResolver(ObjectMapper json,ArtifactVersionService artifacts,DeliverableReader deliverables,
            MemoryService messages,AgentRuntimeExecutionProjection projection,MemberTaskDetails members,
            SessionAttachmentService attachments,PublicOutputGuard guard,ArtifactAccess access,SessionMediaService media,
            com.skillforge.core.chat.access.SessionAccessPolicy sessions) {
        this.json=json;this.artifacts=artifacts;this.deliverables=deliverables;this.messages=messages;
        this.projection=projection;this.members=members;this.attachments=attachments;this.guard=guard;
        this.access=access;
        this.media=media;
        this.sessions=sessions;
    }

    public TypedPayload request(String text,Map<String,Object> context,List<ChatInputReference> references) {
        return request(ChatContent.text(text),context,references);
    }

    public TypedPayload request(List<ChatContent> content,Map<String,Object> context,List<ChatInputReference> references) {
        var business=new TreeMap<String,Object>(context==null?Map.of():context);
        for(String reserved:List.of("references","artifact_inputs","base_revision_id","input_references","reference_requests","content","contentRequests","attachment_readings"))
            if(business.containsKey(reserved))throw invalid("引用必须通过 references 提交");
        business.remove("trace_id");business.remove("span_id");
        return encode(new ReferenceRequest(content,business,references));
    }

    /** 已受理同键只复用原快照；当前重新授权不替换原内容、标题、版本或目标。 */
    public TypedPayload freeze(SessionView session,TypedPayload requested,Optional<TypedPayload> original) {
        try {
            var root=json.readTree(requested.canonicalJson());
            List<ChatInputReference> refs=root.has("referenceRequests")?json.convertValue(root.get("referenceRequests"),REFERENCES):List.of();
            List<ChatContent> content=root.has("contentRequests")?json.convertValue(root.get("contentRequests"),new TypeReference<List<ChatContent>>(){}):List.of();
            if(refs.size()>ChatInputReference.MAX_REFERENCES||refs.stream().distinct().count()!=refs.size())throw invalid("引用材料超过限制或重复");
            var context=json.convertValue(root.path("extraParameters"),new TypeReference<Map<String,Object>>(){});
            boolean selected=root.has("contentRequests")||!refs.isEmpty()||context.containsKey("attachment_ids");
            if(!selected)return requested;
            // 纯文本只使用正式Session写权；没有材料时不申请附件、产物或媒体访问权。
            if(refs.isEmpty()&&!context.containsKey("attachment_ids")&&content.stream().allMatch(ChatContent.Text.class::isInstance)) {
                sessions.requireCurrent(session,"write");
                String fingerprint=digest(requested.canonicalJson());
                if(original.isPresent()) {
                    var old=json.readTree(original.orElseThrow().canonicalJson());
                    if(!fingerprint.equals(old.path("chatRequestFingerprint").asText()))throw conflict("该消息已绑定其他材料");
                    return original.orElseThrow();
                }
                context.remove("attachments");context.remove("attachment_readings");
                var text=content.stream().map(ChatContent.Text.class::cast).map(part->(SessionMediaService.ResolvedContent)new SessionMediaService.TextContent("text",part.text())).toList();
                return encode(new FrozenRequest(fingerprint,text,context));
            }
            access.authorizeCurrent(session.getTenantId().toString(),session.getSessionId());
            String fingerprint=digest(requested.canonicalJson());
            if(original.isPresent()) {
                var old=json.readTree(original.orElseThrow().canonicalJson());
                if(!fingerprint.equals(old.path("chatRequestFingerprint").asText()))throw conflict("该消息已绑定其他材料");
                // 只重新确认当前权限和可用性；旧消息/过程内容更新不改变已受理输入。
                var raw=old.path("extraParameters").path("input_references");
                List<FrozenMaterial> frozen=raw.isMissingNode()?List.of():json.convertValue(raw,new TypeReference<List<FrozenMaterial>>(){});
                if(!frozen.stream().map(FrozenMaterial::source).toList().equals(refs))throw conflict("该消息已绑定其他材料");
                for(var material:frozen)reauthorize(session,material);
                attachments.inputContext(session.getTenantId().toString(),session.getSessionId(),context);
                media.resolveContent(session.getSessionId(),content);
                return original.orElseThrow();
            }
            var frozen=new ArrayList<FrozenMaterial>();
            var publicScope=guard.capture(com.skillforge.institution.team.TeamWorkService.owner(session));
            var files=new HashSet<ArtifactInput>();
            int revisionBases=0;
            for(var ref:refs) {
                if(ref instanceof ChatInputReference.DeliverableRevision revision && ChatInputReference.REVISION_BASE_PURPOSE.equals(revision.purpose())
                        && ++revisionBases>1)throw invalid("一次消息只能选择一个成果基础版本");
                var material=material(session,ref);
                if(material.artifactInputs()!=null)material.artifactInputs().forEach(file -> files.add(new ArtifactInput(file.artifactId(),file.path())));
                if(files.size()>ChatInputReference.MAX_ARTIFACT_FILES)throw invalid("引用文件数量超过限制");
                frozen.add(publicScope.rejects(material.label())?material.withLabel("所选材料"):material);
            }
            var resolved=attachments.inputContext(session.getTenantId().toString(),session.getSessionId(),context,content.isEmpty());
            if(!frozen.isEmpty())resolved.put("input_references",List.copyOf(frozen));
            var resolvedContent=root.has("contentRequests")?media.resolveContent(session.getSessionId(),content)
                    :List.<SessionMediaService.ResolvedContent>of(new SessionMediaService.TextContent("text",root.path("text").asText()));
            return encode(new FrozenRequest(fingerprint,resolvedContent,resolved));
        } catch(ApiException error){throw error;}
        catch(Exception invalid){throw invalid("引用材料当前不可用，请重新选择");}
    }

    /** 群来源已持久且当前资格由群受理边界复验；只冻结原有序内容，不接受任意个人材料选择器。 */
    public TypedPayload freezeGroup(com.skillforge.runtime.api.SessionRef owner,List<ChatContent> content) {
        var requested=request(content,Map.of(),List.of());
        try {
            return encode(new FrozenRequest(digest(requested.canonicalJson()),media.resolveContent(owner,content),Map.of()));
        } catch(java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(ENCODING_ERROR,impossible);
        }
    }

    public void accepted(SessionView session,String inputId,TypedPayload payload) {
        try {
            var refs=json.readTree(payload.canonicalJson()).path("extraParameters").path("input_references");
            if(!refs.isArray())return;
            var views=new ArrayList<ChatReferenceView>();
            for(var ref:refs)views.add(new ChatReferenceView(ref.path("label").asText(),json.treeToValue(ref.path("source"),ChatInputReference.class)));
            messages.freezeInputReferences(session.getTenantId().toString(),session.getSessionId(),inputId,views);
        }catch(java.io.IOException error){throw new IllegalStateException(SNAPSHOT_ERROR,error);}
    }

    private FrozenMaterial material(SessionView session,ChatInputReference reference)throws Exception {
        String tenant=session.getTenantId().toString();String productSession=session.getSessionId();
        if(reference instanceof ChatInputReference.ArtifactFiles files) {
            var recovery=artifacts.recovery(productSession,files.files().stream().map(file->new ArtifactInput(file.artifactId(),file.path())).toList());
            return new FrozenMaterial(reference,files.files().size()==1?filename(files.files().getFirst().path()):"所选文件",
                    null,null,null,null,recovery.artifactInputs(),null,null,null,null,null);
        }
        if(reference instanceof ChatInputReference.DeliverableRevision selected) {
            var revision=deliverables.revisionCurrent(selected.revisionId());
            if(!revision.sessionId().equals(productSession))throw invalid("成果不属于当前会话");
            if(ChatInputReference.REVISION_BASE_PURPOSE.equals(selected.purpose())&&!revision.current())throw conflict("成果版本已更新，请重新选择");
            var inputs=revision.files().stream().map(file->new ArtifactInput(file.artifactId(),deliverables.file(tenant,revision.id(),file.fileId()).path())).toList();
            var recovery=artifacts.recovery(productSession,inputs);
            return new FrozenMaterial(reference,revision.name(),null,null,null,null,recovery.artifactInputs(),
                    revision.deliverableId(),revision.id(),revision.revision(),selected.purpose(),
                    ChatInputReference.REVISION_BASE_PURPOSE.equals(selected.purpose())?revision.id():null);
        }
        var source=reference instanceof ChatInputReference.Message message?message.source():((ChatInputReference.Process)reference).source();
        com.skillforge.core.chat.dto.response.ChatHistoryResponse history;
        PublicOutputGuard.Scope scope;
        if(source instanceof ChatInputReference.WorkTaskSource task) {
            var member=members.resolve(session,task.workId(),task.taskKind(),task.taskId());
            history=members.history(member);scope=guard.capture(member.owner());
        } else {
            history=projection.read(session);scope=guard.capture(com.skillforge.institution.team.TeamWorkService.owner(session));
        }
        if(reference instanceof ChatInputReference.Message selected) {
            var message=history.messages().stream().filter(item->item.messageId().equals(selected.messageId())).findFirst().orElse(null);
            if(message==null&&source instanceof ChatInputReference.SessionSource) {
                var archived=messages.findByMessageId(tenant,selected.messageId());
                if(archived!=null&&archived.sessionId().equals(productSession)&&!"system".equals(archived.record().role()))message=archived.record();
            }
            if(message==null)throw invalid("消息当前不可见");
            if(message.messageVersion()!=selected.messageVersion())throw conflict("消息内容已更新，请重新选择");
            if(scope.rejects(message.content()))throw invalid("消息无法作为公开材料引用");
            return new FrozenMaterial(reference,"user".equals(message.role())?"用户消息":"回复消息",message.content(),
                    message.messageVersion(),null,null,null,null,null,null,null,null);
        }
        var selected=(ChatInputReference.Process)reference;
        var process=history.processes().stream().filter(item->item.processId().equals(selected.processId())).findFirst().orElseThrow(()->invalid("过程当前不可见"));
        if(process.updatedSequence()!=selected.updatedSequence())throw conflict("过程内容已更新，请重新选择");
        return new FrozenMaterial(reference,process.presentation().title(),null,null,process.presentation(),
                process.updatedSequence(),null,null,null,null,null,null);
    }

    /** 重新确认当前权限和已冻结来源仍可访问；从不构造新的材料或内容版本。 */
    private void reauthorize(SessionView session,FrozenMaterial frozen)throws Exception {
        String tenant=session.getTenantId().toString();
        if(frozen.source() instanceof ChatInputReference.ArtifactFiles
                || frozen.source() instanceof ChatInputReference.DeliverableRevision) {
            if(frozen.source() instanceof ChatInputReference.DeliverableRevision selected) {
                var original=deliverables.revisionCurrent(selected.revisionId());
                if(!session.getSessionId().equals(original.sessionId()))throw invalid("成果不属于当前会话");
                original.files().forEach(file->deliverables.file(tenant,original.id(),file.fileId()));
            }
            var provenance=artifacts.recovery(session.getSessionId(),frozen.artifactInputs().stream()
                    .map(file->new ArtifactInput(file.artifactId(),file.path())).toList()).artifactInputs();
            if(!provenance.equals(frozen.artifactInputs()))throw invalid("引用文件来源已不可核验");
            return;
        }
        var source=frozen.source() instanceof ChatInputReference.Message message?message.source():((ChatInputReference.Process)frozen.source()).source();
        var owner=com.skillforge.institution.team.TeamWorkService.owner(session);
        com.skillforge.runtime.api.RunRef run=null;
        if(source instanceof ChatInputReference.WorkTaskSource task) {
            var member=members.resolve(session,task.workId(),task.taskKind(),task.taskId());
            owner=member.owner();run=member.run();
            if (members.primary(member)) {
                var scoped=members.history(member);
                boolean visible=frozen.source() instanceof ChatInputReference.Process process && scoped.processes().stream()
                        .anyMatch(item->item.processId().equals(process.processId()) && item.updatedSequence()==process.updatedSequence());
                if (!visible) throw invalid("引用材料当前不可见");
                return;
            }
        }
        if(frozen.source() instanceof ChatInputReference.Message message && source instanceof ChatInputReference.SessionSource
                && messages.publicMessageExists(tenant,session.getSessionId(),message.messageId()))return;
        if(!projection.publicReferenceExists(owner,run,frozen.source()))throw invalid("引用材料当前不可见");
    }

    /** 受理后冻结的材料事实；动态业务参数之外不以散落 Map 表达引用契约。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FrozenMaterial(ChatInputReference source,String label,String content,
            @JsonProperty("message_version") Long messageVersion,ProcessPresentation presentation,
            @JsonProperty("updated_sequence") Long updatedSequence,
            @JsonProperty("artifact_inputs") List<ArtifactProvenance> artifactInputs,
            @JsonProperty("deliverable_id") String deliverableId,@JsonProperty("revision_id") String revisionId,
            Integer revision,String purpose,@JsonProperty("base_revision_id") String baseRevisionId) {
        public FrozenMaterial { Objects.requireNonNull(source);Objects.requireNonNull(label);artifactInputs=artifactInputs==null?null:List.copyOf(artifactInputs); }
        FrozenMaterial withLabel(String value) { return new FrozenMaterial(source,value,content,messageVersion,
                presentation,updatedSequence,artifactInputs,deliverableId,revisionId,revision,purpose,baseRevisionId); }
        @Override public String toString(){return "FrozenChatMaterial[REDACTED]";}
    }
    private sealed interface InputPayload permits ReferenceRequest,FrozenRequest { }
    @com.fasterxml.jackson.annotation.JsonPropertyOrder(alphabetic = true)
    private record ReferenceRequest(@JsonProperty("contentRequests") List<ChatContent> content,
            @JsonProperty("extraParameters") Map<String,Object> extraParameters,
            @JsonProperty("referenceRequests") List<ChatInputReference> references) implements InputPayload {
        @Override public String toString(){return "ChatReferenceRequest[REDACTED]";}
    }
    @com.fasterxml.jackson.annotation.JsonPropertyOrder(alphabetic = true)
    private record FrozenRequest(@JsonProperty("chatRequestFingerprint") String fingerprint,
            List<SessionMediaService.ResolvedContent> content,
            @JsonProperty("extraParameters") Map<String,Object> extraParameters) implements InputPayload {
        @Override public String toString(){return "FrozenChatRequest[REDACTED]";}
    }
    private TypedPayload encode(InputPayload value) {
        try {
            // 类型对象只在规范 JSON 边界转为树；包含多态 type 的嵌套对象也按键排序。
            var tree=json.convertValue(value,new TypeReference<TreeMap<String,Object>>(){});
            return new TypedPayload(INPUT_SCHEMA,json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(tree));
        }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException(ENCODING_ERROR,invalid);}
    }
    private static String filename(String path){return path.substring(path.lastIndexOf('/')+1);}
    private static String digest(String text)throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static ApiException invalid(String message){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,message);}
    private static ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,message);}
}
