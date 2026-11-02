package com.skillforge.institution.foundation.assets;

import com.skillforge.core.foundation.assets.FoundationFileService;
import com.skillforge.core.foundation.assets.web.FoundationFilesEndpoint;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 机构公共管理文件，只使用登录主体固定机构。 */
@RestController
@RequestMapping("/api/v1/tenant/foundation/files")
public class TenantFoundationFilesController extends FoundationFilesEndpoint {
    /** 机构入口注入同一受控文件应用服务。 */ public TenantFoundationFilesController(FoundationFileService files){super(files);}
}
