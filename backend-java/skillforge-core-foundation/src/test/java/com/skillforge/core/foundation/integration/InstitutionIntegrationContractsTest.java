package com.skillforge.core.foundation.integration;

import static com.skillforge.core.foundation.integration.IntegrationContracts.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 新接入协议的身份必须使用JSON字符串；数字和布尔不会经Jackson隐式转成合法身份。 */
class InstitutionIntegrationContractsTest {
    private final ObjectMapper json = new ObjectMapper();

    /** 大于JavaScript安全整数的字符串身份精确保留，数字输入明确拒绝。 */
    @Test void executionIdentityRejectsNumericOrBooleanCoercion() throws Exception {
        var value = json.readValue("{\"tenant_id\":\"9007199254740993\",\"integration_id\":\"crm\",\"principal_id\":\"9007199254740995\",\"expected_revision\":1}", ExecutionCredentialCommand.class);
        assertEquals("9007199254740993",value.tenantId()); assertEquals("9007199254740995",value.principalId());
        for (String invalid : new String[]{"9007199254740993","true"}) {
            assertThrows(JsonProcessingException.class, () -> json.readValue("{\"tenant_id\":" + invalid + ",\"integration_id\":\"crm\",\"principal_id\":\"10\",\"expected_revision\":1}", ExecutionCredentialCommand.class));
            assertThrows(JsonProcessingException.class, () -> json.readValue("{\"tenant_id\":\"7\",\"integration_id\":" + invalid + ",\"scopes\":[\"sessions.write\"]}", IssueExecutionCredential.class));
            assertThrows(JsonProcessingException.class, () -> json.readValue("{\"scope_id\":" + invalid + ",\"status\":\"revoked\",\"expected_revision\":1}", BusinessScopeStatus.class));
        }
    }

    /** 外部键和scope集合不将数值强制转换为新的外部身份或授权动作。 */
    @Test void externalKeysAndExecutionScopesRequireStrings() {
        assertThrows(JsonProcessingException.class, () -> json.readValue("{\"tenant_id\":\"7\",\"namespace\":\"crm\",\"external_institution_ref\":123}", AssociateInstitution.class));
        assertThrows(JsonProcessingException.class, () -> json.readValue("{\"scope_type\":\"store\",\"external_ref\":true,\"display_name\":\"门店\"}", EnsureBusinessScope.class));
        assertThrows(JsonProcessingException.class, () -> json.readValue("{\"tenant_id\":\"7\",\"integration_id\":\"crm\",\"scopes\":[123]}", IssueExecutionCredential.class));
    }

    /** CAS仅接受精确JSON整数，不将字符串、浮点或超出Long的值截断为合法版本。 */
    @Test void expectedRevisionRejectsCoercedOrOverflowValues() {
        for (String invalid : new String[]{"\"1\"", "1.5", "true", "9223372036854775808"})
            assertThrows(JsonProcessingException.class, () -> json.readValue("{\"scope_id\":\"101\",\"status\":\"revoked\",\"expected_revision\":" + invalid + "}", BusinessScopeStatus.class));
    }
}
