package com.skillforge.institution.foundation.catalog;

import com.skillforge.core.foundation.catalog.CatalogEndpoints;
import com.skillforge.core.foundation.catalog.dictionary.DictionaryService;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementService;
import com.skillforge.core.foundation.catalog.navigation.NavigationService;
import com.skillforge.core.foundation.catalog.preferences.PreferenceService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 机构 catalog 固定路径入口；所有资源使用当前认证租户。 */
@RestController
@RequestMapping("/api/v1/tenant/foundation/catalog")
public class TenantCatalogController extends CatalogEndpoints {
    /** 复用完整领域应用规则。 */
    public TenantCatalogController(DictionaryService dictionaries,NavigationService navigation,PreferenceService preferences,EntitlementService entitlements) {super(dictionaries,navigation,preferences,entitlements);}
}
