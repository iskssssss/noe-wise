package com.skillforge.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.model.media.ModelMediaJob;
import com.skillforge.core.model.media.ModelMediaJobService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 平台管理员媒体任务入口；owner 来自登录身份，结果通过认证下载而非供应商签名URL公开。 */
@RestController
@RequestMapping("/api/v1/admin/models/media")
public class PlatformModelMediaController {
    private final ModelMediaJobService jobs;
    public PlatformModelMediaController(ModelMediaJobService jobs) { this.jobs = jobs; }

    @RequirePermission("foundation.models.run")
    @PostMapping("/jobs/create")
    public ApiResponse<ModelMediaJob> create(@RequestBody ModelMediaJob.Create command) {
        return ApiResponses.wrap(jobs.create(owner(), command));
    }
    @RequirePermission("foundation.models.read")
    @GetMapping("/jobs/detail")
    public ApiResponse<ModelMediaJob> detail(@RequestParam String id) { return ApiResponses.wrap(jobs.detail(owner(), id)); }
    @RequirePermission("foundation.models.run")
    @PostMapping("/jobs/query")
    public ApiResponse<ModelMediaJob> query(@RequestBody JobCommand command) { return ApiResponses.wrap(jobs.query(owner(), command.id())); }
    @RequirePermission("foundation.models.run")
    @PostMapping("/jobs/stop-waiting")
    public ApiResponse<ModelMediaJob> stop(@RequestBody JobCommand command) { return ApiResponses.wrap(jobs.stopWaiting(owner(), command.id())); }

    @RequirePermission("foundation.models.read")
    @GetMapping("/assets/content")
    public ResponseEntity<StreamingResponseBody> content(@RequestParam("job_id") String jobId,
            @RequestParam("asset_id") String assetId) throws IOException {
        var download = jobs.download(owner(), jobId, assetId);
        StreamingResponseBody body = output -> { try (var content = download.content()) { content.transferTo(output); } };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.mediaType()))
                .contentLength(download.sizeBytes()).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(download.name()).build().toString())
                .body(body);
    }
    public record JobCommand(String id) { }
    private static ModelMediaJob.Owner owner() { return new ModelMediaJob.Owner(null, StpUtil.getLoginIdAsString()); }
}
