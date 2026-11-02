package com.skillforge.core.foundation.catalog;

import com.skillforge.core.foundation.catalog.dictionary.DictionaryContracts;
import com.skillforge.core.foundation.catalog.dictionary.DictionaryService;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementContracts;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementService;
import com.skillforge.core.foundation.catalog.navigation.ControlledRoutes;
import com.skillforge.core.foundation.catalog.navigation.NavigationContracts;
import com.skillforge.core.foundation.catalog.navigation.NavigationService;
import com.skillforge.core.foundation.catalog.preferences.PreferenceContracts;
import com.skillforge.core.foundation.catalog.preferences.PreferenceService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 双宿主共享的固定路径 HTTP 投影；平台和机构的授权仍由应用服务限定。 */
public abstract class CatalogEndpoints {
    private final DictionaryService dictionaries; private final NavigationService navigation; private final PreferenceService preferences; private final EntitlementService entitlements;
    /** 宿主子类提供路径前缀和显式生产装配。 */
    protected CatalogEndpoints(DictionaryService dictionaries,NavigationService navigation,PreferenceService preferences,EntitlementService entitlements) {this.dictionaries=dictionaries;this.navigation=navigation;this.preferences=preferences;this.entitlements=entitlements;}
    /** 字典目录。 */ @GetMapping("/dictionaries") public ApiResponse<List<DictionaryContracts.DictionaryView>> dictionaries() {return ApiResponses.wrap(dictionaries.list());}
    /** 完整字典保存。 */ @PutMapping("/dictionaries") public ApiResponse<DictionaryContracts.DictionaryView> dictionary(@Valid @RequestBody DictionaryContracts.SaveDictionary command) {return ApiResponses.wrap(dictionaries.save(command));}
    /** 引用受保护删除。 */ @DeleteMapping("/dictionaries") public ApiResponse<Void> deleteDictionary(@Valid @RequestBody DictionaryContracts.Delete command) {dictionaries.delete(command);return ApiResponses.wrap((Void)null);}
    /** 所有值管理目录。 */ @GetMapping("/dictionary-values") public ApiResponse<List<DictionaryContracts.ValueView>> values(@RequestParam("dictionary_code") String code) {return ApiResponses.wrap(dictionaries.listValues(code));}
    /** 活跃选择器。 */ @GetMapping("/dictionary-options") public ApiResponse<List<DictionaryContracts.ValueView>> options(@RequestParam("dictionary_code") String code) {return ApiResponses.wrap(dictionaries.options(code));}
    /** 稳定值创建或 CAS 更新。 */ @PutMapping("/dictionary-values") public ApiResponse<DictionaryContracts.ValueView> value(@Valid @RequestBody DictionaryContracts.SaveValue command) {return ApiResponses.wrap(dictionaries.saveValue(command));}
    /** 当前有权限菜单。 */ @GetMapping("/navigation/current") public ApiResponse<List<NavigationContracts.Menu>> currentMenu() {return ApiResponses.wrap(navigation.current());}
    /** 管理菜单结构。 */ @GetMapping("/navigation") public ApiResponse<List<NavigationContracts.Node>> navigation() {return ApiResponses.wrap(navigation.list());}
    /** 受控代码路由目录。 */ @GetMapping("/navigation/routes") public ApiResponse<List<ControlledRoutes.Route>> routes() {return ApiResponses.wrap(navigation.routes());}
    /** 菜单保存，客户端没有任意 path/component 字段。 */ @PutMapping("/navigation") public ApiResponse<NavigationContracts.Node> navigation(@Valid @RequestBody NavigationContracts.SaveNode command) {return ApiResponses.wrap(navigation.save(command));}
    /** 非级联删除。 */ @DeleteMapping("/navigation") public ApiResponse<Void> deleteNavigation(@Valid @RequestBody NavigationContracts.DeleteNode command) {navigation.delete(command);return ApiResponses.wrap((Void)null);}
    /** 机构品牌，平台主体服务端拒绝。 */ @GetMapping("/branding") public ApiResponse<PreferenceContracts.Branding> branding() {return ApiResponses.wrap(preferences.branding());}
    /** 品牌完整 CAS 保存。 */ @PutMapping("/branding") public ApiResponse<PreferenceContracts.Branding> branding(@Valid @RequestBody PreferenceContracts.SaveBranding command) {return ApiResponses.wrap(preferences.saveBranding(command));}
    /** 当前本人偏好。 */ @GetMapping("/preferences") public ApiResponse<PreferenceContracts.Personal> personal() {return ApiResponses.wrap(preferences.personal());}
    /** 本人偏好保存。 */ @PutMapping("/preferences") public ApiResponse<PreferenceContracts.Personal> personal(@Valid @RequestBody PreferenceContracts.SavePersonal command) {return ApiResponses.wrap(preferences.savePersonal(command));}
    /** 功能目录。 */ @GetMapping("/features") public ApiResponse<List<EntitlementContracts.Feature>> features() {return ApiResponses.wrap(entitlements.features());}
    /** 平台功能目录维护。 */ @PutMapping("/features") public ApiResponse<EntitlementContracts.Feature> feature(@Valid @RequestBody EntitlementContracts.SaveFeature command) {return ApiResponses.wrap(entitlements.saveFeature(command));}
    /** 现有套餐目录。 */ @GetMapping("/plans") public ApiResponse<List<EntitlementContracts.Plan>> plans() {return ApiResponses.wrap(entitlements.plans());}
    /** 套餐授权关系。 */ @GetMapping("/plan-features") public ApiResponse<List<EntitlementContracts.Grant>> planFeatures(@RequestParam("plan_id") String id) {return ApiResponses.wrap(entitlements.planGrants(id));}
    /** 套餐授权保存。 */ @PutMapping("/plan-features") public ApiResponse<EntitlementContracts.Grant> planFeature(@Valid @RequestBody EntitlementContracts.SavePlanGrant command) {return ApiResponses.wrap(entitlements.savePlanGrant(command));}
    /** 机构明确覆盖。 */ @GetMapping("/tenant-features") public ApiResponse<List<EntitlementContracts.Grant>> tenantFeatures(@RequestParam(value="tenant_id",required=false) String id) {return ApiResponses.wrap(entitlements.tenantGrants(id));}
    /** 平台保存或删除机构覆盖。 */ @PutMapping("/tenant-features") public ApiResponse<Void> tenantFeature(@Valid @RequestBody EntitlementContracts.SaveTenantGrant command) {entitlements.saveTenantGrant(command);return ApiResponses.wrap((Void)null);}
    /** 套餐 typed 配额及对应精确CAS修订。 */ @GetMapping("/plan-quota") public ApiResponse<EntitlementContracts.PlanQuota> planQuota(@RequestParam("plan_id") String id) {return ApiResponses.wrap(entitlements.planQuota(id));}
    /** 套餐限额保存。 */ @PutMapping("/plan-quota") public ApiResponse<EntitlementContracts.Limits> planQuota(@Valid @RequestBody EntitlementContracts.SavePlanQuota command) {return ApiResponses.wrap(entitlements.savePlanQuota(command));}
    /** 当前有效机构授权和真实配额计数。 */ @GetMapping("/tenant-entitlements") public ApiResponse<EntitlementContracts.TenantEntitlements> tenantEntitlements(@RequestParam(value="tenant_id",required=false) String id) {return ApiResponses.wrap(entitlements.inspect(id));}
    /** 机构覆盖保存。 */ @PutMapping("/tenant-quota") public ApiResponse<EntitlementContracts.TenantEntitlements> tenantQuota(@Valid @RequestBody EntitlementContracts.SaveTenantQuota command) {return ApiResponses.wrap(entitlements.saveTenantQuota(command));}
}
