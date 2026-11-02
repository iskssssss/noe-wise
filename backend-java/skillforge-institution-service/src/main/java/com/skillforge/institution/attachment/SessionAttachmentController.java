package com.skillforge.institution.attachment;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 两端共用的会话输入文件入口；上传和清单都重新校验当前会话权限。 */
@RestController
@RequestMapping("/api/v1/chat/sessions/attachments")
public class SessionAttachmentController {
    private final SessionAttachmentService attachments;
    public SessionAttachmentController(SessionAttachmentService attachments) { this.attachments = attachments; }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SessionAttachmentService.Metadata> upload(
            @RequestParam("session_id") String sessionId, @RequestParam("upload_id") String uploadId,
            @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponses.wrap(attachments.upload(sessionId, uploadId, file));
    }
    @GetMapping
    public ApiResponse<List<SessionAttachmentService.Metadata>> list(@RequestParam("session_id") String sessionId) {
        return ApiResponses.wrap(attachments.list(sessionId));
    }
    /** 仅返回当前获授权产品会话的容量，不泄露同机构其它会话聚合。 */
    @GetMapping("/storage-budget")
    public ApiResponse<SessionAttachmentBodyStore.SessionStorageBudget> storageBudget(@RequestParam("session_id") String sessionId) {
        return ApiResponses.wrap(attachments.storageBudget(sessionId));
    }
}
