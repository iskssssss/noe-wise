package com.skillforge.institution.media;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.attachment.SessionAttachmentBodyStore;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/chat/sessions/media")
public class SessionMediaController {
    private final SessionMediaService media;
    public SessionMediaController(SessionMediaService media) { this.media = media; }
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SessionMediaService.MediaView> upload(@RequestParam("session_id") String sessionId,
            @RequestParam("upload_id") String uploadId, @RequestPart("file") MultipartFile file) throws java.io.IOException {
        return ApiResponses.wrap(media.upload(sessionId, uploadId, file));
    }
    @GetMapping("/upload-receipt")
    public ApiResponse<SessionMediaService.UploadReceipt> uploadReceipt(@RequestParam("session_id") String sessionId,
            @RequestParam("upload_id") String uploadId) {
        return ApiResponses.wrap(media.uploadReceipt(sessionId, uploadId));
    }
    @GetMapping("/content")
    public ResponseEntity<byte[]> content(@RequestParam("session_id") String session,
            @RequestParam("media_id") String id) {
        var ref = media.visible(session, id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ref.mimeType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(media.read(ref.owner(), ref));
    }
    @GetMapping("/limits")
    public ApiResponse<MediaLimits> limits() {
        return ApiResponses.wrap(new MediaLimits(SessionMediaImages.MAX_BYTES, SessionMediaImages.MAX_PIXELS, List.of("image/png", "image/jpeg", "image/webp")));
    }
    /** 共享存储容量以当前产品会话授权为边界，不返回租户总量。 */
    @GetMapping("/storage-budget")
    public ApiResponse<SessionAttachmentBodyStore.SessionStorageBudget> storageBudget(@RequestParam("session_id") String sessionId) {
        return ApiResponses.wrap(media.storageBudget(sessionId));
    }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MediaLimits(long maxFileBytes, long maxPixels, List<String> mimeTypes) { }
}
