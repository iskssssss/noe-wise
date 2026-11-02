package com.skillforge.institution.artifact;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.chat.contract.ChatReferenceView;
import com.skillforge.core.chat.contract.ChatInputReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;

/** 用户入口只读取已发布成果；单个下载不隐式创建压缩包。 */
@RestController
@RequestMapping("/api/v1/chat")
public final class DeliverableController {
    private final DeliverableReader reader; private final DeliverableAccess tickets; private final ArtifactStorage storage;
    private final ArtifactAccess access;
    public DeliverableController(DeliverableReader reader,DeliverableAccess tickets,ArtifactStorage storage,ArtifactAccess access){this.reader=reader;this.tickets=tickets;this.storage=storage;this.access=access;}
    @GetMapping("/sessions/deliverables")
    public ApiResponse<List<DeliverableView>> list(@RequestParam String sessionId,@RequestParam(defaultValue="false") boolean includeHistory){return ApiResponses.wrap(reader.list(sessionId,includeHistory));}
    @PostMapping("/deliverables/download-ticket")
    public ApiResponse<Map<String,String>> download(@Valid @RequestBody FileCommand command){return ApiResponses.wrap(tickets.ticket(command.id(),command.fileId(),false));}
    @PostMapping("/deliverables/preview-ticket")
    public ApiResponse<Map<String,String>> preview(@Valid @RequestBody FileCommand command){return ApiResponses.wrap(tickets.ticket(command.id(),command.fileId(),true));}
    @PostMapping("/deliverables/recovery-inputs")
    public ApiResponse<RecoveryResponse> recovery(@Valid @RequestBody RevisionCommand command){
        // 先凭文件映射解析会话，再走完整会话视图鉴权；调用方不能任意指定他人会话。
        var all=reader.revisionCurrent(command.revisionId());
        return ApiResponses.wrap(new RecoveryResponse("validated",all.sessionId(),all.id(),all.deliverableId(),
                all.files().stream().map(f->new RecoveryFile(f.artifactId(),reader.file(access.tenant(),all.id(),f.fileId()).path(),f.fileId(),f.size(),f.sha256())).toList(),
                List.of(new ChatReferenceView("成果版本 " + all.revision(),new ChatInputReference.DeliverableRevision(all.id(),ChatInputReference.REVISION_BASE_PURPOSE)))));
    }
    @GetMapping("/deliverable-content/detail")
    public void content(@RequestParam String ticket,HttpServletResponse response)throws Exception{
        var granted=tickets.verify(ticket,null);if("html".equals(granted.claim().kind()))throw ArtifactStorage.unavailable();
        var file=granted.file();
        response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(file.downloadName(),StandardCharsets.UTF_8).build().toString());
        response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("X-Content-Type-Options","nosniff");
        response.setContentType(mime(file.path()));
        String previous=com.skillforge.common.context.TenantContext.getTenantId();
        try {
            com.skillforge.common.context.TenantContext.setTenantId(granted.claim().tenant());
            storage.file(file.artifact(),file.path(),response.getOutputStream());
        } finally {
            if(previous==null)com.skillforge.common.context.TenantContext.clear();
            else com.skillforge.common.context.TenantContext.setTenantId(previous);
        }
    }
    static String mime(String path){return switch(DeliverableService.extension(path)){
        case "md"->"text/markdown; charset=utf-8";case "txt","csv","json","yaml","yml"->"text/plain; charset=utf-8";
        case "pdf"->"application/pdf";case "docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        case "xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";case "pptx"->"application/vnd.openxmlformats-officedocument.presentationml.presentation";
        default->ArtifactPreviewServer.mime(path);};}
    public record FileCommand(@NotBlank String id,@NotBlank @JsonProperty("file_id") String fileId) { }
    public record RevisionCommand(@NotBlank @JsonProperty("revision_id") String revisionId) { }
    public record RecoveryResponse(String status,@JsonProperty("session_id") String sessionId,
            @JsonProperty("base_revision_id") String baseRevisionId,@JsonProperty("deliverable_id") String deliverableId,
            @JsonProperty("artifact_inputs") List<RecoveryFile> artifactInputs,List<ChatReferenceView> references) {
        public RecoveryResponse { artifactInputs=List.copyOf(artifactInputs);references=List.copyOf(references); }
    }
    public record RecoveryFile(@JsonProperty("artifact_id") String artifactId,String path,
            @JsonProperty("file_id") String fileId,long size,String sha256) { }
}
