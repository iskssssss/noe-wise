package com.skillforge.institution.artifact;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.skillforge.core.capability.connector.CodeExecutionArguments.ArtifactInput;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chat")
public class ArtifactController {
    private final ArtifactStorage storage; private final ArtifactAccess access; private final ArtifactVersionService versions;
    public ArtifactController(ArtifactStorage storage,ArtifactAccess access,ArtifactVersionService versions){
        this.storage=storage;this.access=access;this.versions=versions;
    }
    @Operation(summary="读取会话产物",description="复核当前成员或渠道与租户Session归属，聚合当前工作室合法成员子Run产物并返回清单和到期状态，不含文件正文")
    @GetMapping("/sessions/artifacts")
    public ApiResponse<List<ArtifactMetadata>> list(@RequestParam("sessionId") String sessionId){String tenant=access.tenant();return ApiResponses.wrap(access.listCurrent(tenant,sessionId));}
    @Operation(summary="签发流式下载票据",description="path选择清单中的文件，zip=true下载整个目录；票据有效120秒，每次使用映射原产品Session并重新鉴权")
    @PostMapping("/artifacts/download-ticket")
    public ApiResponse<Map<String,String>> download(@jakarta.validation.Valid @RequestBody DownloadCommand command){
        return ApiResponses.wrap(download(command.id(),command.payload()));
    }
    public Map<String,String> download(String id,Download request){
        return Map.of("url",access.ticket(id,request.zip()?"zip":"file",request.path()));
    }
    @Operation(summary="签发隔离网站预览票据",description="仅website产物；独立origin，不携带平台登录态；每次资源读取重新鉴权")
    @PostMapping("/artifacts/preview-ticket")
    public ApiResponse<Map<String,String>> preview(@jakarta.validation.Valid @RequestBody ArtifactCommand command){return ApiResponses.wrap(preview(command.id()));}
    public Map<String,String> preview(String id){return Map.of("url",access.ticket(id,"preview",null));}
    @Operation(summary="比较同一会话的两个产物文件",description="逐个重新鉴权并核验清单和文件摘要；每个文件最多128KiB、2000逻辑行，严格UTF-8解码")
    @PostMapping("/artifacts/diff")
    public ApiResponse<ArtifactVersionService.DiffResult> diff(@jakarta.validation.Valid @RequestBody DiffCommand command)throws IOException {
        return ApiResponses.wrap(versions.diff(command.base().input(),command.target().input()));
    }
    @Operation(summary="验证产物定向恢复输入",description="返回可用于下一次新执行的已核验artifact_inputs；不重跑旧调用、不修改旧产物，也不表示已经生成新版本")
    @PostMapping("/artifacts/recovery-inputs")
    public ApiResponse<ArtifactVersionService.RecoveryResult> recovery(@jakarta.validation.Valid @RequestBody RecoveryCommand command)throws IOException {
        return ApiResponses.wrap(versions.recovery(command.sessionId(),command.artifactInputs().stream().map(ArtifactReference::input).toList()));
    }
    @Operation(summary="流式读取产物",description="使用指定产物短期票据，每请求复核当前成员或渠道及Session权限；正文为附件或ZIP")
    @GetMapping("/artifact-content/detail")
    public void content(@RequestParam("ticket") String ticket,HttpServletResponse response)throws IOException {
        var granted=access.verify(ticket,null);
        if("preview".equals(granted.claim().kind()))throw ArtifactStorage.unavailable();
        boolean zip="zip".equals(granted.claim().kind());String filename=zip?"artifacts.zip":PathName.file(granted.claim().path());
        response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString());
        response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("X-Content-Type-Options","nosniff");response.setContentType(zip?"application/zip":"application/octet-stream");
        String previous=com.skillforge.common.context.TenantContext.getTenantId();
        try {
            com.skillforge.common.context.TenantContext.setTenantId(granted.claim().tenant());
            if(zip)storage.zip(granted.row(),response.getOutputStream());else storage.file(granted.row(),granted.claim().path(),response.getOutputStream());
        } finally {
            if(previous==null)com.skillforge.common.context.TenantContext.clear();else com.skillforge.common.context.TenantContext.setTenantId(previous);
        }
    }
    public record Download(String path,boolean zip) { }
    public record DownloadCommand(@jakarta.validation.constraints.NotBlank String id,
            @jakarta.validation.constraints.NotNull @jakarta.validation.Valid Download payload) { }
    public record ArtifactCommand(@jakarta.validation.constraints.NotBlank String id) { }
    public record ArtifactReference(
            @com.fasterxml.jackson.annotation.JsonProperty("artifact_id") @jakarta.validation.constraints.NotBlank String artifactId,
            @jakarta.validation.constraints.NotBlank String path) {
        ArtifactInput input(){return new ArtifactInput(artifactId,path);}
    }
    public record DiffCommand(
            @jakarta.validation.constraints.NotNull @jakarta.validation.Valid ArtifactReference base,
            @jakarta.validation.constraints.NotNull @jakarta.validation.Valid ArtifactReference target) { }
    public record RecoveryCommand(
            @com.fasterxml.jackson.annotation.JsonProperty("session_id") @jakarta.validation.constraints.NotBlank String sessionId,
            @com.fasterxml.jackson.annotation.JsonProperty("artifact_inputs")
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(max=256)
            List<@jakarta.validation.Valid ArtifactReference> artifactInputs) {
        public RecoveryCommand { artifactInputs=artifactInputs==null?null:List.copyOf(artifactInputs); }
    }
    private static class PathName {static String file(String path){ArtifactStorage.safePath(path);return path.substring(path.lastIndexOf('/')+1);}}
}
