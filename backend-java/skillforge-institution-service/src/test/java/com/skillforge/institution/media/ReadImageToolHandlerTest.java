package com.skillforge.institution.media;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.port.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

/** 只读图片的已知业务拒绝必须可观察，存储和序列化失败仍交给 Provider 判定未知结果。 */
class ReadImageToolHandlerTest {
    private static final SessionRef OWNER = new SessionRef(new TenantId("7"), "session");
    private static final String ID = "c77e9b67-aab5-4f0e-ae52-62a3df176b63";
    private static final MediaRef SOURCE = new MediaRef(OWNER, ID, "a".repeat(64), "image/png", 100, 40, 20);

    @Test
    void rejectsInvalidArgumentsAndArtifactPathsWithoutReadingMedia() {
        var media = mock(SessionMediaService.class);
        var handler = new ReadImageToolHandler(media, new ObjectMapper());
        for (var args : List.<Map<String, Object>>of(Map.of(), Map.of("media_id", 17),
                Map.of("media_id", ID, "path", "page-1.png")))
            assertRejection(handler.execute(args, context()), "read_image_arguments_invalid");
        for (String id : List.of("", "artifact/pages/page-1.png", ID + "/pages/page-1.png", "1-1-1-1-1"))
            assertRejection(handler.execute(Map.of("media_id", id), context()), "read_image_media_id_invalid");
        verifyNoInteractions(media);
    }

    @Test
    void rejectsMalformedCropWithoutReadingMedia() {
        var media = mock(SessionMediaService.class);
        var handler = new ReadImageToolHandler(media, new ObjectMapper());
        for (Object crop : List.of("rectangle", Map.of("x", 0, "y", 0, "width", 3),
                Map.of("x", 0, "y", 0, "width", "3", "height", 2),
                Map.of("x", 0, "y", 0, "width", 3.5, "height", 2),
                Map.of("x", 0, "y", 0, "width", Long.MAX_VALUE, "height", 2),
                Map.of("x", 0, "y", 0, "width", 3, "height", 2, "extra", 1)))
            assertRejection(handler.execute(Map.of("media_id", ID, "crop", crop), context()), "read_image_crop_invalid");
        verifyNoInteractions(media);
    }

    @Test
    void rejectsOutOfBoundsCropBeforeReadingOrTransformingPixels() {
        var media = mock(SessionMediaService.class);
        when(media.reference(OWNER, ID)).thenReturn(SOURCE);
        var handler = new ReadImageToolHandler(media, new ObjectMapper());
        for (var crop : List.of(new SessionMediaService.Crop(-1, 0, 3, 2),
                new SessionMediaService.Crop(0, 0, 0, 2), new SessionMediaService.Crop(0, 19, 3, 2),
                new SessionMediaService.Crop(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 2)))
            assertRejection(handler.execute(Map.of("media_id", ID, "crop", cropArguments(crop)), context()),
                    "read_image_crop_invalid");
        verify(media, never()).crop(any(), any(), any());
    }

    @Test
    void returnsSafeKnown400And404ErrorsWithoutExceptionDetails() {
        for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND)) {
            var media = mock(SessionMediaService.class);
            when(media.reference(OWNER, ID)).thenThrow(new ApiException(status, "private-db-and-other-session-details"));
            var handler = new ReadImageToolHandler(media, new ObjectMapper());
            var result = handler.execute(Map.of("media_id", ID), context());
            assertRejection(result, status == HttpStatus.NOT_FOUND ? "read_image_media_not_found" : "read_image_request_invalid");
            assertFalse(result.toString().contains("private-db"));
            verify(media, never()).crop(any(), any(), any());

            reset(media);
            when(media.reference(OWNER, ID)).thenReturn(SOURCE);
            when(media.crop(OWNER, SOURCE, null)).thenThrow(new ApiException(status, "private-pixel-storage-details"));
            result = handler.execute(Map.of("media_id", ID), context());
            assertRejection(result, status == HttpStatus.NOT_FOUND ? "read_image_media_not_found" : "read_image_request_invalid");
            assertFalse(result.toString().contains("private-pixel"));
        }
    }

    @Test
    void propagatesUnknownDatabasePermissionDecodeAndSerializationFailures() {
        var media = mock(SessionMediaService.class);
        var handler = new ReadImageToolHandler(media, new ObjectMapper());
        for (var failure : List.of(new DataAccessResourceFailureException("database unavailable"),
                new IllegalArgumentException("unexpected media implementation failure"),
                new ApiException(HttpStatus.FORBIDDEN, "authorization failure"),
                new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "storage IO failure"))) {
            doThrow(failure).when(media).reference(OWNER, ID);
            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> handler.execute(Map.of("media_id", ID), context())));
        }
        doReturn(SOURCE).when(media).reference(OWNER, ID);
        var decodeFailure = new IllegalArgumentException("corrupt persisted image");
        doThrow(decodeFailure).when(media).crop(OWNER, SOURCE, null);
        assertSame(decodeFailure, assertThrows(IllegalArgumentException.class,
                () -> handler.execute(Map.of("media_id", ID), context())));

        doReturn(new SessionMediaService.CropResult(SOURCE, SOURCE,
                new SessionMediaService.Crop(0, 0, 40, 20))).when(media).crop(OWNER, SOURCE, null);
        var codec = mock(ObjectMapper.class);
        var serializationFailure = new IllegalArgumentException("result encoding failed");
        when(codec.convertValue(any(), any(TypeReference.class))).thenThrow(serializationFailure);
        var serializing = new ReadImageToolHandler(media, codec);
        assertSame(serializationFailure, assertThrows(IllegalArgumentException.class,
                () -> serializing.execute(Map.of("media_id", ID), context())));
    }

    @Test
    void preservesActualCurrentSessionImageAndCropMetadata() {
        var mapper = mock(SessionMediaMapper.class);
        var stored = new HashMap<String, SessionMediaRow>();
        when(mapper.insert(any())).thenAnswer(call -> stored.putIfAbsent(((SessionMediaRow) call.getArgument(0)).id(),
                call.getArgument(0)) == null ? 1 : 0);
        when(mapper.find(anyString(), anyString(), anyString())).thenAnswer(call -> {
            var row = stored.get(call.getArgument(2));
            return row != null && row.tenantId().equals(call.getArgument(0)) && row.sessionId().equals(call.getArgument(1))
                    ? row : null;
        });
        when(mapper.readBytes(anyString(), anyString(), anyString())).thenAnswer(call -> {
            var row = stored.get(call.getArgument(2));
            return row == null ? null : new SessionMediaMapper.StoredBytes(row.content());
        });
        var media = new SessionMediaService(mapper, mock(ArtifactAccess.class), mock(AgentRunBindings.class),
                mock(com.skillforge.institution.attachment.SessionAttachmentBodyStore.class),mock(com.skillforge.runtime.session.spi.ExecutionStore.class),mock(org.springframework.beans.factory.ObjectProvider.class));
        var pixels = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        pixels.setRGB(10, 5, 0xffff0000);
        var source = media.register(OWNER, "read-image-test", "测试图片", SessionMediaImages.encode(pixels, "image/png").bytes());
        var codec = new ObjectMapper();
        var handler = new ReadImageToolHandler(media, codec);

        var complete = codec.valueToTree(handler.execute(Map.of("media_id", source.id()), context()));
        assertFalse(complete.path("isError").asBoolean());
        assertEquals(source.id(), complete.path("media_id").asText());
        assertEquals(40, complete.path("width").asInt());
        assertEquals("7", complete.path("content").get(0).path("media").path("owner").path("tenantId").asText());

        var crop = new SessionMediaService.Crop(10, 5, 8, 6);
        var result = codec.valueToTree(handler.execute(Map.of("media_id", source.id(), "crop", cropArguments(crop)), context()));
        assertFalse(result.path("isError").asBoolean());
        assertEquals(source.id(), result.path("source_media_id").asText());
        assertEquals(8, result.path("width").asInt());
        assertEquals(6, result.path("height").asInt());
        assertEquals(10, result.path("coordinate_mapping").path("source_x").asInt());
        assertEquals(5, result.path("coordinate_mapping").path("source_y").asInt());
        var actual = media.reference(OWNER, result.path("media_id").asText());
        assertEquals(0xffff0000, SessionMediaImages.decode(media.read(OWNER, actual)).pixels().getRGB(0, 0));
        var otherSession = media.reference(OWNER, source.id());
        assertThrows(ApiException.class, () -> media.reference(new SessionRef(OWNER.tenantId(), "other"), otherSession.id()));
    }

    private static void assertRejection(Map<String, Object> result, String code) {
        assertEquals(true, result.get("isError"));
        assertEquals(code, result.get("error_code"));
        var content = new ObjectMapper().valueToTree(result).path("content");
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).path("type").asText());
        assertFalse(content.get(0).path("text").asText().isBlank());
        assertFalse(content.get(0).has("media"));
    }

    private static Map<String, Object> cropArguments(SessionMediaService.Crop crop) {
        return Map.of("x", crop.x(), "y", crop.y(), "width", crop.width(), "height", crop.height());
    }

    private static PlatformToolExecutionContext context() {
        var input = new InputId("input");
        var owner = SessionOwner.user(OWNER, new AgentId("agent"), new EndUserId(1));
        var run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(OWNER.tenantId(), "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("verification"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(OWNER.tenantId(), owner.requireUser()),
                owner, Set.of(), 1, new ResultRef("identity"));
        return new PlatformToolExecutionContext(new PortCallContext(run, identity,
                new InvocationControl(Instant.now().plusSeconds(30), () -> false)), "invocation", "attempt");
    }
}
