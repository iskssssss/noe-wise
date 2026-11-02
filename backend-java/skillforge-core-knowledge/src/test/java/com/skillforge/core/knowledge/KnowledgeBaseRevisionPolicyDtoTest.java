package com.skillforge.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.knowledge.dto.request.MarkKnowledgeBaseRevisionReadyRequest;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ready DTO 的显式 policy、未知字段与标量类型拒绝契约。 */
class KnowledgeBaseRevisionPolicyDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void acceptsCompletePolicyWithoutInferringAnyField() throws Exception {
        MarkKnowledgeBaseRevisionReadyRequest request = objectMapper.readValue("""
                {
                  "source_versions": ["v-2", "v-1"],
                  "policy": {
                    "model_invocable": false,
                    "user_invocable": true,
                    "allowed_operators": ["VECTOR", "KEYWORD"],
                    "freshness_policy": "PINNED",
                    "citation_required": false
                  }
                }
                """, MarkKnowledgeBaseRevisionReadyRequest.class);

        assertEquals(Boolean.FALSE, request.policy().modelInvocable());
        assertEquals(Boolean.TRUE, request.policy().userInvocable());
        assertEquals(Set.of("VECTOR", "KEYWORD"), request.policy().allowedOperators());
        assertEquals("PINNED", request.policy().freshnessPolicy());
        assertEquals(Boolean.FALSE, request.policy().citationRequired());
    }

    @Test
    void rejectsUnknownTopLevelAndPolicyFields() {
        assertThrows(Exception.class, () -> objectMapper.readValue("""
                {"source_versions":["v1"],"policy":{
                  "model_invocable":true,"user_invocable":false,
                  "allowed_operators":["VECTOR"],"freshness_policy":"PINNED",
                  "citation_required":true,"retrieval_mode":"hybrid"}}
                """, MarkKnowledgeBaseRevisionReadyRequest.class));
        assertThrows(Exception.class, () -> objectMapper.readValue("""
                {"source_versions":["v1"],"policy":{
                  "model_invocable":true,"user_invocable":false,
                  "allowed_operators":["VECTOR"],"freshness_policy":"PINNED",
                  "citation_required":true},"unknown":1}
                """, MarkKnowledgeBaseRevisionReadyRequest.class));
    }

    @Test
    void rejectsWrongScalarTypesInsteadOfJacksonCoercion() {
        assertThrows(Exception.class, () -> objectMapper.readValue("""
                {"source_versions":["v1"],"policy":{
                  "model_invocable":"true","user_invocable":false,
                  "allowed_operators":["VECTOR"],"freshness_policy":"PINNED",
                  "citation_required":true}}
                """, MarkKnowledgeBaseRevisionReadyRequest.class));
        assertThrows(Exception.class, () -> objectMapper.readValue("""
                {"source_versions":["v1"],"policy":{
                  "model_invocable":true,"user_invocable":false,
                  "allowed_operators":["VECTOR"],"freshness_policy":7,
                  "citation_required":true}}
                """, MarkKnowledgeBaseRevisionReadyRequest.class));
    }

    @Test
    void rejectsWrongOperatorElementTypesInsteadOfStringCoercion() {
        assertThrows(Exception.class, () -> objectMapper.readValue("""
                {"source_versions":["v1"],"policy":{
                  "model_invocable":true,"user_invocable":false,
                  "allowed_operators":[7],"freshness_policy":"PINNED",
                  "citation_required":true}}
                """, MarkKnowledgeBaseRevisionReadyRequest.class));
        assertThrows(Exception.class, () -> objectMapper.readValue("""
                {"source_versions":[7],"policy":{
                  "model_invocable":true,"user_invocable":false,
                  "allowed_operators":["VECTOR"],"freshness_policy":"PINNED",
                  "citation_required":true}}
                """, MarkKnowledgeBaseRevisionReadyRequest.class));
    }
}
