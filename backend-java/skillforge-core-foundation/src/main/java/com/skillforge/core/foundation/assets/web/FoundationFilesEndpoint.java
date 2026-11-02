package com.skillforge.core.foundation.assets.web;

import com.skillforge.core.foundation.assets.FoundationFileContracts;
import com.skillforge.core.foundation.assets.FoundationFileService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/** 双宿主公共文件协议转换；领域服务始终重新校验身份、作用域、权限及引用。 */
public abstract class FoundationFilesEndpoint {
    private final FoundationFileService files;
    /** 接入已拥有授权规则的服务。 */ protected FoundationFilesEndpoint(FoundationFileService files){this.files=files;}
    /** 固定路径列表，仅 GET 使用查询参数。 */
    @GetMapping public ApiResponse<FoundationFileContracts.Page> list(@RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int size){return ApiResponses.wrap(files.list(page,size));}
    /** 上传不接受租户、所有者、MIME 或路径声明。 */
    @PostMapping(value="/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FoundationFileContracts.FileView> upload(@RequestPart("file") MultipartFile file)throws IOException{try(var stream=file.getInputStream()){return ApiResponses.wrap(files.upload(file.getOriginalFilename(),stream));}}
    /** 下载原始字节，每次重新授权，强制 attachment 与 nosniff。 */
    @GetMapping("/download") public ResponseEntity<byte[]> download(@RequestParam(name="file_id") String id){var material=files.download(id);return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(material.file().mimeType())).contentLength(material.bytes().length)
        .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(material.file().name(),StandardCharsets.UTF_8).build().toString())
        .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'").header(HttpHeaders.CACHE_CONTROL,"private, no-store")
        .body(material.bytes());}
    /** 删除请求只指定文件身份，不允许任意路径或强制跳过引用。 */
    @PostMapping("/delete") public ApiResponse<Void> delete(@RequestBody FileId request){files.delete(request.fileId());return ApiResponses.wrap((Void)null);}
    /** @param fileId 公共文件 UUID */ public record FileId(@com.fasterxml.jackson.annotation.JsonProperty("file_id") String fileId){ }
}
