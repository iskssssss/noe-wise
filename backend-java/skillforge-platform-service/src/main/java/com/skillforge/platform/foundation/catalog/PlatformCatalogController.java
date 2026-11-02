package com.skillforge.platform.foundation.catalog;

import com.skillforge.core.foundation.catalog.CatalogEndpoints;
import com.skillforge.core.foundation.catalog.dictionary.DictionaryService;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementService;
import com.skillforge.core.foundation.catalog.navigation.NavigationService;
import com.skillforge.core.foundation.catalog.preferences.PreferenceService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台 catalog 固定路径入口，组织目标不能从请求获得权限。 */
@RestController
@RequestMapping("/api/v1/admin/foundation/catalog")
public class PlatformCatalogController extends CatalogEndpoints {
    /** 复用完整领域应用规则。 */
    public PlatformCatalogController(DictionaryService dictionaries,NavigationService navigation,PreferenceService preferences,EntitlementService entitlements) {super(dictionaries,navigation,preferences,entitlements);}
}
