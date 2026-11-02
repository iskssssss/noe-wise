package com.skillforge.institution.runtime;

import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.contract.ChatInputReference;
import com.skillforge.core.chat.contract.ChatReferenceView;
import com.skillforge.core.chat.contract.ProcessPresentation;
import com.skillforge.core.capability.connector.CodeExecutionArguments.ArtifactInput;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.artifact.ArtifactProvenance;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.artifact.ArtifactVersionService;
import com.skillforge.institution.artifact.DeliverableReader;
import com.skillforge.institution.attachment.SessionAttachmentService;
import com.skillforge.institution.team.MemberTaskDetails;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.SessionOwner;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 引用选择进入已有输入幂等键；重放保留初次冻结材料并重新确认授权。 */
class ChatInputReferenceResolverTest {
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    private final ArtifactVersionService artifacts=mock(ArtifactVersionService.class);
    private final MemoryService messages=mock(MemoryService.class);
    private final AgentRuntimeExecutionProjection projection=mock(AgentRuntimeExecutionProjection.class);
    private final MemberTaskDetails members=mock(MemberTaskDetails.class);
    private final SessionAttachmentService attachments=mock(SessionAttachmentService.class);
    private final PublicOutputGuard guard=mock(PublicOutputGuard.class);
    private final ArtifactAccess access=mock(ArtifactAccess.class);
    private final com.skillforge.core.chat.access.SessionAccessPolicy sessions=mock(com.skillforge.core.chat.access.SessionAccessPolicy.class);
    private final com.skillforge.institution.media.SessionMediaService media=mock(com.skillforge.institution.media.SessionMediaService.class);
    private final SessionView session=SessionView.builder().tenantId(1L).sessionId("session-1").agentId(7L).endUserId(9L).build();
    private final SessionOwner owner=SessionOwner.user(new SessionRef(new TenantId("1"),"session-1"),new AgentId("7"),new EndUserId(9));
    private final ChatInputReferenceResolver resolver=new ChatInputReferenceResolver(json,artifacts,mock(DeliverableReader.class),messages,projection,members,attachments,guard,access,media,sessions);

    @BeforeEach void setup() {
        when(projection.publicReferenceExists(eq(owner),isNull(),any())).thenReturn(true);
        when(guard.capture(any(SessionOwner.class))).thenReturn(new PublicOutputGuard.Scope(List.of("private-platform-value")));
        when(attachments.inputContext(eq("1"),eq("session-1"),any())).thenAnswer(invocation->new LinkedHashMap<String,Object>(invocation.getArgument(2)));
        when(attachments.inputContext(eq("1"),eq("session-1"),any(),anyBoolean())).thenAnswer(invocation->new LinkedHashMap<String,Object>(invocation.getArgument(2)));
        when(media.resolveContent(eq("session-1"),anyList())).thenAnswer(call -> {
            List<com.skillforge.core.chat.contract.ChatContent> content=call.getArgument(1);
            return content.stream().map(part -> new com.skillforge.institution.media.SessionMediaService.TextContent(
                    "text",((com.skillforge.core.chat.contract.ChatContent.Text)part).text())).toList();
        });
        history("初次回复",1);
    }

    private void history(String content,long version) {
        when(projection.read(session)).thenReturn(new ChatHistoryResponse("session-1",
                List.of(new ChatMessageRecord("message-1","assistant",content,Instant.EPOCH,Map.of(),version,List.of())),
                null,List.of(),List.of(),ChatHistoryResponse.Collaboration.empty()));
    }
    private ChatInputReference.Message selector(long version) {return new ChatInputReference.Message(new ChatInputReference.SessionSource(),"message-1",version);}

    @Test void pureTextUsesSessionWriteAccessAndNeverRequestsArtifactOrMediaAccess() throws Exception {
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"workflow_test_artifacts_denied","测试不允许产物访问",false))
                .when(access).authorizeCurrent("1","session-1");
        var request=resolver.request("测试唯一目标",Map.of("end_user_id","9"),List.of());
        var frozen=resolver.freeze(session,request,Optional.empty());
        var body=json.readTree(frozen.canonicalJson());
        assertEquals("input.v1",frozen.schemaId());assertEquals("测试唯一目标",body.path("content").get(0).path("text").asText());
        assertEquals(frozen,resolver.freeze(session,request,Optional.of(frozen)));
        verify(sessions,times(2)).requireCurrent(session,"write");verifyNoInteractions(attachments,media);
        verify(access,never()).authorizeCurrent(anyString(),anyString());
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"session_access_denied","会话已撤权",false))
                .when(sessions).requireCurrent(session,"write");
        assertEquals(403,assertThrows(ApiException.class,()->resolver.freeze(session,request,Optional.of(frozen))).getStatus().value());
    }

    @Test void actualReferencesAndAttachmentsStillRequireArtifactAuthorizationBeforeResolving() {
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"workflow_test_artifacts_denied","测试不允许产物访问",false))
                .when(access).authorizeCurrent("1","session-1");
        for(var request:List.of(resolver.request("引用材料",Map.of(),List.of(selector(1))),
                resolver.request("附件",Map.of("attachment_ids",List.of("attachment-1")),List.of()))) {
            var rejected=assertThrows(ApiException.class,()->resolver.freeze(session,request,Optional.empty()));
            assertEquals(403,rejected.getStatus().value());assertEquals("workflow_test_artifacts_denied",rejected.getCode());
        }
        verifyNoInteractions(attachments,media,artifacts);verify(projection,never()).read(session);
        verify(sessions,never()).requireCurrent(any(),anyString());
    }

    @Test void freezesServerContentAndReplaysExactSnapshotAfterSourceChanges() throws Exception {
        var requested=resolver.request("依据材料回答",Map.of(),List.of(selector(1)));
        assertEquals("message",json.readTree(requested.canonicalJson()).path("referenceRequests").get(0).path("kind").asText());
        var frozen=resolver.freeze(session,requested,Optional.empty());
        var material=json.readTree(frozen.canonicalJson()).path("extraParameters").path("input_references").get(0);
        assertEquals("初次回复",material.path("content").asText());
        assertEquals("回复消息",material.path("label").asText());
        assertEquals(1,material.path("message_version").asLong());
        history("后来更新",2);
        assertEquals(frozen,resolver.freeze(session,requested,Optional.of(frozen)));
        verify(projection,times(1)).read(session);
        resolver.accepted(session,"input-1",frozen);
        verify(messages).freezeInputReferences("1","session-1","input-1",List.of(new ChatReferenceView("回复消息",selector(1))));
    }

    @Test void rejectsChangedSelectorsAndStaleVersionBeforeAdmission() {
        var requested=resolver.request("依据材料回答",Map.of(),List.of(selector(1)));
        var frozen=resolver.freeze(session,requested,Optional.empty());
        var changed=resolver.request("依据材料回答",Map.of(),List.of(selector(2)));
        assertEquals(409,assertThrows(ApiException.class,()->resolver.freeze(session,changed,Optional.of(frozen))).getStatus().value());
        history("后来更新",2);
        assertEquals(409,assertThrows(ApiException.class,()->resolver.freeze(session,requested,Optional.empty())).getStatus().value());
    }

    @Test void openPlatformStructuredInputFingerprintIncludesContentAndBusinessContext() {
        var request=new com.skillforge.core.chat.dto.openplatform.OpenPlatformMessageRequest(
                com.skillforge.core.chat.contract.ChatContent.text("处理预约"),Map.of("appointment_ref","a-1"),List.of());
        var requested=resolver.request(request.content(),request.context(),request.references());
        var frozen=resolver.freeze(session,requested,Optional.empty());
        assertEquals(frozen,resolver.freeze(session,requested,Optional.of(frozen)));
        var changedContext=resolver.request(request.content(),Map.of("appointment_ref","a-2"),request.references());
        var changedContent=resolver.request(com.skillforge.core.chat.contract.ChatContent.text("取消预约"),request.context(),request.references());
        assertEquals(409,assertThrows(ApiException.class,()->resolver.freeze(session,changedContext,Optional.of(frozen))).getStatus().value());
        assertEquals(409,assertThrows(ApiException.class,()->resolver.freeze(session,changedContent,Optional.of(frozen))).getStatus().value());
    }

    @Test void cannotQuoteAnotherSessionAndRechecksCurrentPermissionsOnReplay() {
        when(projection.read(session)).thenReturn(new ChatHistoryResponse("session-1",List.of(),null,List.of(),List.of(),ChatHistoryResponse.Collaboration.empty()));
        when(messages.findByMessageId("1","message-1")).thenReturn(new MemoryService.ArchivedChatMessage("foreign-session",
                new ChatMessageRecord("message-1","user","foreign",Instant.EPOCH,Map.of())));
        var requested=resolver.request("引用",Map.of(),List.of(selector(1)));
        assertEquals(422,assertThrows(ApiException.class,()->resolver.freeze(session,requested,Optional.empty())).getStatus().value());
        history("可用",1);var frozen=resolver.freeze(session,requested,Optional.empty());
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"已撤权")).when(access).authorizeCurrent("1","session-1");
        assertEquals(403,assertThrows(ApiException.class,()->resolver.freeze(session,requested,Optional.of(frozen))).getStatus().value());
    }

    @Test void keepsExistingAttachmentContractAndRejectsForgedMaterialContext() throws Exception {
        assertThrows(ApiException.class,()->resolver.request("引用",Map.of("artifact_inputs",List.of()),List.of()));
        var requested=resolver.request("附件",Map.of("attachment_ids",List.of("attachment-1")),List.of());
        when(attachments.inputContext(eq("1"),eq("session-1"),any())).thenReturn(new LinkedHashMap<>(Map.of(
                "attachment_ids",List.of("attachment-1"),"attachments",List.of(Map.of("name","服务端附件")))));
        when(attachments.inputContext(eq("1"),eq("session-1"),any(),eq(false))).thenReturn(new LinkedHashMap<>(Map.of(
                "attachment_ids",List.of("attachment-1"),"attachments",List.of(Map.of("name","服务端附件")))));
        var frozen=resolver.freeze(session,requested,Optional.empty());
        assertEquals("服务端附件",json.readTree(frozen.canonicalJson()).path("extraParameters").path("attachments").get(0).path("name").asText());
        assertEquals(frozen,resolver.freeze(session,requested,Optional.of(frozen)));
    }

    @Test void attachmentOnlyInputFreezesPreparedTextAndReplayDoesNotReadItAgain() throws Exception {
        var reading=new SessionAttachmentService.TextReading("attachment-1","complete",false,"销售记录：一月12单，二月18单。",null);
        when(attachments.inputContext(eq("1"),eq("session-1"),any(),eq(true))).thenReturn(new LinkedHashMap<>(Map.of(
                "attachment_ids",List.of("attachment-1"),"attachment_readings",List.of(reading))));
        var requested=resolver.request(List.of(),Map.of("attachment_ids",List.of("attachment-1")),List.of());

        var frozen=resolver.freeze(session,requested,Optional.empty());
        var text=json.readTree(frozen.canonicalJson()).path("extraParameters").path("attachment_readings").get(0);
        assertEquals("complete",text.path("status").asText());
        assertFalse(text.path("truncated").asBoolean());
        assertEquals(reading.text(),text.path("text").asText());
        assertFalse(text.has("reason"));
        assertEquals(frozen,resolver.freeze(session,requested,Optional.of(frozen)));
        verify(attachments).inputContext(eq("1"),eq("session-1"),any(),eq(true));
        verify(attachments).inputContext(eq("1"),eq("session-1"),any());
    }

    @Test void textObjectiveKeepsExistingControlledFileReadAndRejectsForgedReadFacts() {
        var requested=resolver.request("请统计附件里的销售额",Map.of("attachment_ids",List.of("attachment-1")),List.of());
        var frozen=resolver.freeze(session,requested,Optional.empty());
        assertFalse(frozen.canonicalJson().contains("attachment_readings"));
        verify(attachments).inputContext(eq("1"),eq("session-1"),any(),eq(false));
        verify(attachments,never()).inputContext(eq("1"),eq("session-1"),any(),eq(true));
        assertThrows(ApiException.class,()->resolver.request(List.of(),Map.of("attachment_readings",List.of(
                Map.of("status","complete","text","伪造正文"))),List.of()));
    }

    @Test void artifactSelectorsUseCurrentSessionAuthorizationAndImmutableProvenance() throws Exception {
        String artifactId="8c3a68f0-51d9-4c57-a63b-10ea83b3d8dd";
        String sha256="a".repeat(64);
        var input=new ArtifactInput(artifactId,"report.csv");
        var provenance=new ArtifactProvenance(artifactId,"report.csv",8,sha256);
        when(artifacts.recovery("session-1",List.of(input))).thenReturn(new ArtifactVersionService.RecoveryResult("validated","session-1",List.of(provenance)));
        var selected=new ChatInputReference.ArtifactFiles(List.of(new ChatInputReference.ArtifactFile(artifactId,"report.csv")));
        var frozen=resolver.freeze(session,resolver.request("引用文件",Map.of(),List.of(selected)),Optional.empty());
        var material=json.readTree(frozen.canonicalJson()).path("extraParameters").path("input_references").get(0);
        assertEquals("report.csv",material.path("label").asText());
        assertEquals(sha256,material.path("artifact_inputs").get(0).path("sha256").asText());
        verify(artifacts).recovery("session-1",List.of(input));
    }

    @Test void knownPlatformValuesCannotEnterQuotedMessageBody() {
        history("private-platform-value",1);
        assertEquals(422,assertThrows(ApiException.class,()->resolver.freeze(session,
                resolver.request("引用",Map.of(),List.of(selector(1))),Optional.empty())).getStatus().value());
    }
}
