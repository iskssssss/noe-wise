package com.skillforge.institution.foundation;

import com.skillforge.core.foundation.access.FoundationOptionEndpoints;
import com.skillforge.core.foundation.access.FoundationOptionService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 机构管理动作的真实选择目录，不能通过平台账号访问。 */
@RestController
@RequestMapping("/api/v1/tenant/foundation/options")
public class FoundationOptionController extends FoundationOptionEndpoints {
    /** 固定机构账号域。 */
    public FoundationOptionController(FoundationOptionService service) { super(service, false); }
}
