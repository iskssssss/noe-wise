package com.skillforge.platform.foundation.assets;

import com.skillforge.core.foundation.assets.FoundationFileService;
import com.skillforge.core.foundation.assets.web.FoundationFilesEndpoint;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台私有公共管理文件，不代理租户文件授权。 */
@RestController
@RequestMapping("/api/v1/admin/foundation/files")
public class PlatformFoundationFilesController extends FoundationFilesEndpoint {
    /** 平台入口注入同一受控文件应用服务。 */ public PlatformFoundationFilesController(FoundationFileService files){super(files);}
}
