package com.skillforge.institution.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.access.OpenPlatformSessionAccessService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway;
import com.skillforge.institution.runtime.AgentRuntimeRunControls;
import com.skillforge.institution.runtime.AgentRuntimeExecutionSteering;
import com.skillforge.institution.artifact.*;
import com.skillforge.institution.attachment.SessionAttachmentService;
import com.skillforge.institution.media.SessionMediaService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 外部接入读取已授权公开投影和正式交付物；所有入口共享应用/机构/integration 归属。 */
@RestController
@RequestMapping(value="/api/v1/open-platform/sessions",produces="application/json")
public class OpenPlatformSessionQueryController {
    private static final Logger LOG=LoggerFactory.getLogger(OpenPlatformSessionQueryController.class);
    private final OpenPlatformSessionAccessService access;
    private final AgentRuntimeExecutionGateway runtime;
    private final DeliverableReader deliverables;
    private final DeliverableDownloadService downloads;
    private final SessionAttachmentService attachments;
    private final SessionMediaService media;
    private final AgentRuntimeRunControls controls;

    public OpenPlatformSessionQueryController(OpenPlatformSessionAccessService access,
            AgentRuntimeExecutionGateway runtime,DeliverableReader deliverables,DeliverableDownloadService downloads,
            SessionAttachmentService attachments,SessionMediaService media, AgentRuntimeRunControls controls) {
        this.access=access;this.runtime=runtime;this.deliverables=deliverables;
        this.downloads=downloads;this.attachments=attachments;this.media=media;
        this.controls=controls;
    }

    /** 历史消息与过程直接使用当前公开投影，保留 message_id/version/phase/state。 */
    @GetMapping("/history")
    public ApiResponse<ChatHistoryResponse> history(@RequestParam("sessionId") String sessionId) {
        LOG.debug("开放平台会话历史读取进入");
        try {
            var authorized=access.require("sessions.read");
            return ApiResponses.wrap(runtime.openPlatformHistory(authorized.principal(),access.session(authorized,sessionId)));
        }
        finally { LOG.debug("开放平台会话历史读取退出"); }
    }

    /** Run 状态和待交互卡来自 Runtime 与其公开投影，不从事件名称推断终态。 */
    @GetMapping("/status")
    public ApiResponse<AgentRuntimeExecutionGateway.OpenPlatformRunStatus> status(@RequestParam("sessionId") String sessionId,
            @RequestParam("runId") String runId) {
        var authorized=access.require("sessions.read");
        return ApiResponses.wrap(runtime.openPlatformStatus(authorized.principal(),access.session(authorized,sessionId),runId));
    }

    /** 补充指令遵循既有持久 steering 和运行状态规则，重复键比较原内容。 */
    @PostMapping("/steering")
    public ApiResponse<AgentRuntimeExecutionSteering.Snapshot> steer(@Valid @RequestBody Steering request,
            @io.swagger.v3.oas.annotations.Parameter(schema=@io.swagger.v3.oas.annotations.media.Schema(maxLength=256))
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var authorized=access.require("sessions.write");
        var session=access.session(authorized,request.sessionId());
        if(idempotencyKey.isBlank()||idempotencyKey.length()>256)throw new ApiException(HttpStatus.BAD_REQUEST,"Idempotency-Key 无效");
        return ApiResponses.wrap(controls.steerOpenPlatform(authorized.principal(),session,request.runId(),
                OpenPlatformInputIds.create(authorized.principal(),session.getSessionId(),"steer",idempotencyKey),request.text()));
    }

    /** 正式发布成果清单；工作区中间文件不能经本接口下载。 */
    @GetMapping("/deliverables")
    public ApiResponse<List<DeliverableView>> deliverables(@RequestParam("sessionId") String sessionId,
            @RequestParam(value="history",defaultValue="false") boolean history) {
        session("artifacts.read",sessionId);
        return ApiResponses.wrap(deliverables.list(sessionId,history));
    }

    /** 精确版本与文件选择器先匹配授权成果清单，再读取不可变文件；响应为附件。 */
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",useReturnTypeSchema=false,
            content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/octet-stream",
                    schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",format="binary")))
    @GetMapping(value="/deliverables/file",produces="application/octet-stream")
    public void file(@RequestParam("sessionId") String sessionId,@RequestParam("revisionId") String revisionId,
            @RequestParam("fileId") String fileId,HttpServletResponse response) throws IOException {
        LOG.debug("开放平台成果下载进入");
        try {
            session("artifacts.read",sessionId);
            downloads.transfer(sessionId,revisionId,fileId,grant->{
                response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(grant.downloadName(),StandardCharsets.UTF_8).build().toString());
                response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
                response.setContentType("application/octet-stream");
            },response.getOutputStream());
        } finally { LOG.debug("开放平台成果下载退出"); }
    }

    /** 原始文件字节与完整查询串共同验签，再复用现有类型、大小、内容和同键摘要校验。 */
    @PostMapping(value="/attachments",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<SessionAttachmentService.Metadata> attachment(@RequestParam("sessionId") String sessionId,
            @RequestParam("uploadId") String uploadId,@RequestParam("filename") String filename,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@io.swagger.v3.oas.annotations.media.Content(
                    mediaType=MediaType.APPLICATION_OCTET_STREAM_VALUE,schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",format="binary")))
            @RequestBody byte[] bytes) throws IOException {
        session("sessions.write",sessionId);
        return ApiResponses.wrap(attachments.upload(sessionId,uploadId,filename,bytes));
    }

    /** 恢复后的附件选择器仍从授权会话清单读取，不能通过任意附件 ID 跨会话取原件。 */
    @GetMapping("/attachments")
    public ApiResponse<List<SessionAttachmentService.Metadata>> attachments(@RequestParam("sessionId") String sessionId) {
        session("sessions.read",sessionId);
        return ApiResponses.wrap(attachments.list(sessionId));
    }

    /** 原始图片字节经签名校验后登记；只返回服务端引用，不接受自报 URL 或MIME。 */
    @PostMapping(value="/media",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<SessionMediaService.MediaView> media(@RequestParam("sessionId") String sessionId,
            @RequestParam("uploadId") String uploadId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@io.swagger.v3.oas.annotations.media.Content(
                    mediaType=MediaType.APPLICATION_OCTET_STREAM_VALUE,schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",format="binary")))
            @RequestBody byte[] bytes) {
        session("sessions.write",sessionId);
        return ApiResponses.wrap(media.upload(sessionId,uploadId,bytes));
    }

    /** 媒体输出复用当前媒体归属和委派权限，不输出未授权的内部 Session 地址。 */
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",useReturnTypeSchema=false,
            content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/octet-stream",
                    schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",format="binary")))
    @GetMapping(value="/media/content",produces=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> mediaContent(@RequestParam("sessionId") String sessionId,@RequestParam("mediaId") String mediaId) {
        session("sessions.read",sessionId);
        var ref=media.visible(sessionId,mediaId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .contentType(MediaType.parseMediaType(ref.mimeType())).body(media.read(ref.owner(),ref));
    }

    private SessionView session(String scope,String sessionId) { return access.session(access.require(scope),sessionId); }
    public record Steering(@JsonProperty("session_id") @NotBlank String sessionId,
            @JsonProperty("run_id") @NotBlank String runId,@NotBlank @Size(max=8192) String text) {}
}
