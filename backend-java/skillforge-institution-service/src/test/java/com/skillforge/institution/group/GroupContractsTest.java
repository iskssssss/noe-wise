package com.skillforge.institution.group;

import static com.skillforge.institution.group.GroupContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/** 群协议保护字符串身份、数值版本与初次CAS必填；不让缺字段默认成0。 */
class GroupContractsTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @Test void preciseMemberIdAndNumericRevisionsRoundTrip() throws Exception {
        var value=json.readValue("{\"group_binding_id\":\"41\",\"end_user_id\":\"9007199254740993\",\"role\":\"member\",\"status\":\"active\",\"expected_revision\":0,\"source_event_id\":\"event\",\"source_revision\":1}",UpdateMember.class);
        assertEquals(9007199254740993L,value.endUserId());assertEquals(0L,value.expectedRevision());
        var tree=json.readTree(json.writeValueAsString(new MemberView("41",value.endUserId(),"member","active",1,"event",1)));
        assertEquals("9007199254740993",tree.get("end_user_id").textValue());assertTrue(tree.get("revision").isIntegralNumber());assertTrue(tree.get("source_revision").isIntegralNumber());
    }
    @Test void numericIdentityAndStringRevisionAreRejected() {
        assertThrows(JsonProcessingException.class,()->json.readValue("{\"group_binding_id\":\"41\",\"agent_id\":9007199254740993,\"status\":\"active\",\"expected_revision\":0,\"source_event_id\":\"event\",\"source_revision\":1}",UpdateAgent.class));
        assertThrows(JsonProcessingException.class,()->json.readValue("{\"group_binding_id\":\"41\",\"expected_revision\":\"1\"}",Close.class));
    }
    @Test void missingInitialExpectedRevisionIsNotAnImplicitZero() throws Exception {
        var command=json.readValue("{\"group_binding_id\":\"41\",\"agent_id\":\"31\",\"status\":\"active\",\"source_event_id\":\"event\",\"source_revision\":1}",UpdateAgent.class);
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(command).stream().anyMatch(v->v.getPropertyPath().toString().equals("expectedRevision")));
        }
    }
}
