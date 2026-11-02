package com.skillforge.institution.media;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SessionMediaServiceTest {
    private final SessionMediaMapper mapper = mock(SessionMediaMapper.class);
    private final ArtifactAccess access = mock(ArtifactAccess.class);
    private final SessionRef owner = new SessionRef(new TenantId("7"), "session");
    private final SessionMediaService media = new SessionMediaService(mapper, access, mock(com.skillforge.institution.runtime.AgentRunBindings.class),
            mock(com.skillforge.institution.attachment.SessionAttachmentBodyStore.class),mock(com.skillforge.runtime.session.spi.ExecutionStore.class),mock(org.springframework.beans.factory.ObjectProvider.class));
    @BeforeEach void setup() {
        TenantContext.setTenantId(owner.tenantId().value());
        var stored = new HashMap<String, SessionMediaRow>();
        when(mapper.insert(any())).thenAnswer(call -> stored.putIfAbsent(((SessionMediaRow) call.getArgument(0)).id(), call.getArgument(0)) == null ? 1 : 0);
        when(mapper.find(anyString(), anyString(), anyString())).thenAnswer(call -> {
            var row = stored.get(call.getArgument(2));
            return row != null && row.tenantId().equals(call.getArgument(0)) && row.sessionId().equals(call.getArgument(1)) ? row : null;
        });
        when(mapper.readBytes(anyString(), anyString(), anyString())).thenAnswer(call -> {
            var row = stored.get(call.getArgument(2));
            return row != null && row.tenantId().equals(call.getArgument(0)) && row.sessionId().equals(call.getArgument(1)) ? new SessionMediaMapper.StoredBytes(row.content()) : null;
        });
        when(access.tenant()).thenReturn("7");
    }
    @AfterEach void clearTenant() { TenantContext.clear(); }
    @Test void rawImageAndNativeUploadSharePixelValidationAndStableMediaIdentity() throws Exception {
        String id=java.util.UUID.randomUUID().toString();
        byte[] png=SessionMediaImages.encode(new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB),"image/png").bytes();
        var raw=media.upload("session",id,png);
        var receipt=media.uploadReceipt("session",id);
        assertEquals("FOUND",receipt.status());assertEquals(raw,receipt.media());
        verify(mapper,never()).pin(anyString(),anyString(),anyString());
        var nativeReplay=media.upload("session",id,new org.springframework.mock.web.MockMultipartFile("file","input.png","image/png",png));
        assertEquals(raw,nativeReplay);assertEquals(3,raw.width());assertEquals(2,raw.height());
        assertThrows(IllegalArgumentException.class,()->media.upload("session",java.util.UUID.randomUUID().toString(),new byte[]{1,2,3}));
        doThrow(new SecurityException("session denied")).when(access).authorizeWritableCurrent("7","foreign");
        assertThrows(SecurityException.class,()->media.upload("foreign",java.util.UUID.randomUUID().toString(),png));
        verify(mapper,never()).find(eq("7"),eq("foreign"),anyString());
    }
    @Test void actualPixelsSurviveAdmissionCropAndDeterministicRequestVariant() throws Exception {
        var pixels = new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB);
        pixels.setRGB(10, 5, 0x80ff0000);
        byte[] bytes = SessionMediaImages.encode(pixels, "image/png").bytes();
        var ref = media.register(owner, "upload-1", "图", bytes);
        assertEquals(ref, media.register(owner, "upload-1", "图", bytes));
        var cropped = media.crop(owner, ref, new SessionMediaService.Crop(10, 5, 8, 6));
        assertEquals(0x80ff0000, SessionMediaImages.decode(media.read(owner, cropped.image())).pixels().getRGB(0, 0));
        var policy = new ModelImagePolicy(true, true, 4, 5000, 30000, 4, 512, 85, 170, "test-v1");
        var prepared = media.prepare(owner, cropped.image(), policy);
        assertEquals(4, prepared.width()); assertEquals(3, prepared.height());
        assertEquals(prepared, media.prepare(owner, cropped.image(), policy));
        assertTrue(prepared.byteLength() <= 5000);
        assertEquals("image/png", prepared.mimeType());
        assertArrayEquals(bytes, media.read(owner, ref));
        var content = media.resolveContent("session", List.of(new ChatContent.Image(ref.id()), new ChatContent.Text("说明"), new ChatContent.Image(ref.id())));
        var codec = new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        String encoded = codec.writeValueAsString(content);
        List<SessionMediaService.ResolvedContent> restored = codec.readValue(encoded,
                codec.getTypeFactory().constructCollectionType(List.class, SessionMediaService.ResolvedContent.class));
        assertEquals(content, restored, "冻结内容的 text/image 类型及 type 字段必须完整恢复");
        assertEquals(encoded, codec.writeValueAsString(restored), "类型元数据不能改写冻结 JSON 形状");
        var json = codec.readTree(encoded);
        assertEquals("image", json.get(0).get("type").asText());
        assertEquals("text", json.get(1).get("type").asText());
        assertEquals("7", json.get(2).path("media").path("owner").path("tenantId").asText());
        var frozenImage = json.get(2).path("media");
        assertTrue(frozenImage.has("mimeType"));
        assertTrue(frozenImage.has("byteLength"));
        assertTrue(frozenImage.path("owner").has("sessionId"));
        assertFalse(frozenImage.has("mime_type"));
        assertFalse(frozenImage.has("byte_length"));
        assertFalse(frozenImage.path("owner").has("tenant_id"));
        assertFalse(frozenImage.path("owner").has("session_id"));
    }
    @Test void rejectsCorruptImagesCrossOwnerReferencesAndReboundUploads() {
        assertThrows(IllegalArgumentException.class, () -> media.register(owner, "invalid", "坏图", new byte[]{1,2,3}));
        var first = SessionMediaImages.encode(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "image/png").bytes();
        var ref = media.register(owner, "same", "图", first);
        assertThrows(IllegalArgumentException.class, () -> media.read(new SessionRef(new TenantId("8"), "session"), ref));
        assertThrows(ApiException.class, () -> media.reference(new SessionRef(new TenantId("7"), "other"), ref.id()));
        var changed = SessionMediaImages.encode(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "image/png").bytes();
        assertThrows(ApiException.class, () -> media.register(owner, "same", "图", changed));
        assertThrows(IllegalArgumentException.class, () -> media.prepare(owner, ref, ModelImagePolicy.textOnly()));
    }
    @Test void normalizesExifRotationBeforeUsingImageCoordinates() {
        var pixels = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        pixels.setRGB(0, 0, 0xff00ff00);
        var normalized = SessionMediaImages.normalize(new SessionMediaImages.Decoded(pixels, "image/jpeg", 6));
        assertEquals(2, normalized.getWidth()); assertEquals(3, normalized.getHeight());
        assertEquals(0xff00ff00, normalized.getRGB(1, 0));
    }
    @Test @SuppressWarnings("unchecked") void programMediaUsesProvenParentAuthorizationWhileKeepingActualChildProvenance() {
        var executions=mock(com.skillforge.runtime.session.spi.ExecutionStore.class);var programs=mock(com.skillforge.institution.runtime.AgentRuntimeProgramTools.class);
        var provider=(org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentRuntimeProgramTools>)mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getObject()).thenReturn(programs);
        var bindings=mock(com.skillforge.institution.runtime.AgentRunBindings.class);var binding=mock(com.skillforge.institution.runtime.AgentRunBindings.Binding.class,RETURNS_DEEP_STUBS);
        var sessionOwner=com.skillforge.runtime.api.port.SessionOwner.user(owner,new com.skillforge.runtime.api.AgentId("7"),new com.skillforge.runtime.api.EndUserId(9));
        var input=new com.skillforge.runtime.api.InputId("input");var trace=new com.skillforge.runtime.api.TraceEnvelope("trace","span",input,java.util.Optional.empty());
        var parent=new com.skillforge.runtime.api.port.RunIdentity(sessionOwner, new AgentId((sessionOwner).target().id()), input, new com.skillforge.runtime.api.RunRef(owner.tenantId(),"parent"), trace, new com.skillforge.runtime.api.ResultRef("identity"), new RunRequester.EndUser((sessionOwner).requireUser()), java.util.Optional.empty(), new RunOriginRef((sessionOwner).sessionRef().tenantId(), "input:" + (input).value()));
        var child=new com.skillforge.runtime.api.port.RunIdentity(sessionOwner, new AgentId((sessionOwner).target().id()), input, new com.skillforge.runtime.api.RunRef(owner.tenantId(),"program-tool:actual"), trace, new com.skillforge.runtime.api.ResultRef("identity"), new RunRequester.EndUser((sessionOwner).requireUser()), java.util.Optional.empty(), new RunOriginRef((sessionOwner).sessionRef().tenantId(), "input:" + (input).value()));
        var actual=mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);when(actual.identity()).thenReturn(child);when(executions.load(child.runRef())).thenReturn(java.util.Optional.of(actual));when(programs.resourceOrigin(child)).thenReturn(parent);
        when(bindings.find(parent.runRef())).thenReturn(java.util.Optional.of(binding));when(binding.owner()).thenReturn(sessionOwner);when(binding.actor()).thenReturn(parent.agentId());when(binding.scope().origin()).thenReturn(sessionOwner);
        var service=new SessionMediaService(mapper,access,bindings,mock(com.skillforge.institution.attachment.SessionAttachmentBodyStore.class),executions,provider);
        var bytes=SessionMediaImages.encode(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"image/png").bytes();var reference=service.registerForRun(owner,child.runRef(),"program-media","图",bytes);
        assertEquals(owner,reference.owner());verify(bindings).authorizeRead(binding,"7");verify(bindings,never()).find(child.runRef());
        verify(mapper,atLeastOnce()).insert(argThat(row->child.runRef().runId().equals(row.runId())));
        clearInvocations(mapper,bindings);when(programs.resourceOrigin(child)).thenThrow(new SecurityException("program_child_source_rejected"));
        assertThrows(SecurityException.class,()->service.registerForRun(owner,child.runRef(),"unproven-program-media","图",bytes));verify(mapper,never()).insert(any());verifyNoInteractions(bindings);
    }
}
