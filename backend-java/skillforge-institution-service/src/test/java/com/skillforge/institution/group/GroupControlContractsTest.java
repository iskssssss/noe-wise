package com.skillforge.institution.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.application.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 公开控制协议的类型/存在性和跨语言指纹；事务、当前授权与RLS由正式PG/HTTP用例证明。 */
class GroupControlContractsTest {
    /** 指纹向量来自独立UTF-8/SHA256计算，scope集合顺序不产生另一命令身份。 */
    @Test void fingerprintMatchesThePublishedCrossLanguageCanonicalVector() {
        var service=new GroupControlService(mock(GroupControlMapper.class),mock(InstitutionIntegrationService.class),mock(AuditService.class),new ObjectMapper());
        var command=new GroupControlContracts.Command("pause","11",null,0L,"paused",List.of("execution","send"));
        assertEquals("8b6c216e1b04c7ab8a37db09b3b7ddc7ade0b5e4e9c919c03b09b9c84da9a898",service.commandFingerprint(command));
        assertEquals(service.commandFingerprint(command),service.commandFingerprint(new GroupControlContracts.Command("pause","11",null,0L,"paused",List.of("send","execution"))));
    }
    @Test void fixedIdentityAndScopeStringsRejectNumericCoercion() {
        var json=new ObjectMapper();
        assertThrows(JsonProcessingException.class,()->json.readValue("""
            {"command_id":"pause","group_binding_id":"11","run_id":null,"expected_revision":0,"desired_state":"paused","scopes":["send",1]}
            """,GroupControlContracts.Command.class));
        assertThrows(JsonProcessingException.class,()->json.readValue("""
            {"group_binding_id":"11","endpoint_id":123,"generation":1,"expected_generation":0,"ledger_ref":"ledger"}
            """,GroupSenderContracts.Register.class));
        assertThrows(JsonProcessingException.class,()->json.readValue("""
            {"group_binding_id":"11","endpoint_id":"sender","generation":1,"expected_generation":"0","ledger_ref":"ledger"}
            """,GroupSenderContracts.Register.class));
        assertThrows(JsonProcessingException.class,()->json.readValue("""
            {"group_binding_id":"11","endpoint_id":"sender","generation":1,
             "command_id":"initial","control_revision":0,"ledger_ref":"ledger",
             "applied_boundary_ref":"boundary","fence_revision":1,
             "in_flight":[{"delivery_id":123,"status":"UNKNOWN","result_ref":null}]}
            """,GroupSenderContracts.Ack.class));
    }
    /** 合法零CAS值与字段缺失严格区分，不能把缺失值转换成初代注册或初态ACK。 */
    @Test void revisionAndGenerationPresenceRemainExplicit() throws Exception {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();var json=new ObjectMapper();
            var missing=json.readValue("""
                {"group_binding_id":"11","endpoint_id":"sender","generation":1,"ledger_ref":"ledger"}
                """,GroupSenderContracts.Register.class);
            assertTrue(validator.validate(missing).stream().anyMatch(value->value.getPropertyPath().toString().equals("expectedGeneration")));
            assertTrue(validator.validate(new GroupSenderContracts.Register("11","sender",1L,0L,"ledger")).isEmpty());
            var ack=new GroupSenderContracts.Ack("11","sender",1L,"initial",null,"ledger","boundary",1L,List.of());
            assertTrue(validator.validate(ack).stream().anyMatch(value->value.getPropertyPath().toString().equals("controlRevision")));
        }
    }
    @Test void newControlGetRejectsDuplicateAndUnknownQueryFieldsBeforeServiceRead() {
        var controls=mock(GroupControlService.class);var controller=new GroupControlController(controls,mock(GroupDeliveryService.class),mock(GroupSenderService.class));
        var duplicate=mock(HttpServletRequest.class);when(duplicate.getParameterMap()).thenReturn(Map.of("group_binding_id",new String[]{"11","22"}));
        assertEquals(400,assertThrows(ApiException.class,()->controller.current("11",duplicate)).getStatus().value());
        var unknown=mock(HttpServletRequest.class);when(unknown.getParameterMap()).thenReturn(Map.of("group_binding_id",new String[]{"11"},"actor",new String[]{"forged"}));
        assertEquals(400,assertThrows(ApiException.class,()->controller.detail("11","pause",unknown)).getStatus().value());
        verifyNoInteractions(controls);
    }
}
