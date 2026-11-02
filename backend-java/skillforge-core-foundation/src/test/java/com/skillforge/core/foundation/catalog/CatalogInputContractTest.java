package com.skillforge.core.foundation.catalog;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.foundation.catalog.dictionary.DictionaryContracts;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementContracts;
import org.junit.jupiter.api.Test;

/** 完整PUT输入不能把缺失/NULL布尔与修订当成默认有效值，未知字段拒绝。 */
class CatalogInputContractTest {
    private final ObjectMapper json=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    /** 显式 false/0 合法；缺失、NULL及任意组件字段都必须拒绝。 */
    @Test void requiredPrimitivesAndUnknownFieldsAreRejected() throws Exception {
        var valid=json.readValue("{\"code\":\"status\",\"name\":\"状态\",\"enabled\":false,\"expected_revision\":0}",DictionaryContracts.SaveDictionary.class);
        assertFalse(valid.enabled());assertEquals(0,valid.expectedRevision());
        assertThrows(Exception.class,()->json.readValue("{\"code\":\"status\",\"name\":\"状态\",\"expected_revision\":0}",DictionaryContracts.SaveDictionary.class));
        assertThrows(Exception.class,()->json.readValue("{\"code\":\"status\",\"name\":\"状态\",\"enabled\":null,\"expected_revision\":0}",DictionaryContracts.SaveDictionary.class));
        assertThrows(Exception.class,()->json.readValue("{\"code\":\"status\",\"name\":\"状态\",\"enabled\":true,\"expected_revision\":0,\"component\":\"evil\"}",DictionaryContracts.SaveDictionary.class));
    }
    /** 所有 nullable 限额在完整更新中仍必须出现，显式NULL才表示不限/继承。 */
    @Test void nullableLimitsAndTenantOverrideDistinguishMissingFromExplicitNull() throws Exception {
        var grant=json.readValue("{\"tenant_id\":\"11\",\"feature_code\":\"agents\",\"enabled\":null,\"expected_revision\":1}",EntitlementContracts.SaveTenantGrant.class);
        assertNull(grant.enabled());
        assertThrows(Exception.class,()->json.readValue("{\"tenant_id\":\"11\",\"feature_code\":\"agents\",\"expected_revision\":1}",EntitlementContracts.SaveTenantGrant.class));
        assertThrows(Exception.class,()->json.readValue("{\"agents\":1}",EntitlementContracts.Limits.class));
        String full="{\"agents\":0,\"workflows\":null,\"knowledge_bases\":null,\"members\":null,\"channels\":null,\"file_bytes\":\"9007199254740993\",\"admin_tasks\":null,\"sub_institutions\":0}";
        var limits=json.readValue(full,EntitlementContracts.Limits.class);
        assertEquals("9007199254740993",limits.fileBytes());assertEquals(0,limits.agents());assertEquals(0,limits.subInstitutions());
        assertNull(json.readValue(full.replace("\"sub_institutions\":0","\"sub_institutions\":null"),EntitlementContracts.Limits.class).subInstitutions());
        assertThrows(Exception.class,()->json.readValue(full.replace(",\"sub_institutions\":0",""),EntitlementContracts.Limits.class));
    }
}
