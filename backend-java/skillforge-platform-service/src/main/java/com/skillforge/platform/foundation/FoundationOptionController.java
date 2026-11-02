package com.skillforge.platform.foundation;

import com.skillforge.core.foundation.access.FoundationOptionEndpoints;
import com.skillforge.core.foundation.access.FoundationOptionService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台管理动作的真实选择目录，不能通过机构账号访问。 */
@RestController
@RequestMapping("/api/v1/admin/foundation/options")
public class FoundationOptionController extends FoundationOptionEndpoints {
    /** 固定平台账号域。 */
    public FoundationOptionController(FoundationOptionService service) { super(service, true); }
}
