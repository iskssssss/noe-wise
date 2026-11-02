package com.skillforge.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.platform.dto.capability.CapabilityDefinitionResponse;
import com.skillforge.core.capability.CapabilityType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 平台定义编辑器选择基础能力时，定义和默认版本不得先变成不安全JSON number。 */
class CapabilityCatalogWireContractTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    @Test void capabilityVersionSummaryHasFiveFieldsWithPreciseIdentityAndNullableDescription()throws Exception {
        for(String description:java.util.Arrays.asList("历史说明",null)) {
            var value=com.skillforge.platform.dto.capability.CapabilityVersionSummaryResponse.from(
                    new com.skillforge.core.capability.CapabilityVersionSummary(
                            9007199254740993L,"v1","历史名称",description,"DEPRECATED"));
            var tree=json.readTree(json.writeValueAsString(value));
            assertEquals(5,tree.size());assertId(tree,"id",9007199254740993L);
            assertEquals("v1",tree.path("version_key").asText());assertEquals("历史名称",tree.path("name").asText());
            if(description==null)assertTrue(tree.get("description").isNull());
            else assertEquals(description,tree.path("description").asText());
            assertEquals("DEPRECATED",tree.path("status").asText());
            assertFalse(tree.has("descriptor"));assertFalse(tree.has("files"));assertFalse(tree.has("tenant_id"));
        }
    }
    @Test void exactVersionRequiresPlanIsABooleanAndEditingDraftIsExplicitNull()throws Exception {
        for (Boolean requiresPlan : java.util.Arrays.asList(Boolean.TRUE, Boolean.FALSE, null)) {
            var value=new com.skillforge.core.capability.SkillDraftView(9007199254740993L,11L,null,0L,"v1",
                    java.util.Map.of(),true,true,com.skillforge.core.capability.SkillActivationMode.ALWAYS,
                    null,java.util.List.of(),null,null,requiresPlan);
            var tree=json.readTree(json.writeValueAsString(value));
            assertTrue(tree.has("requires_plan"));
            if(requiresPlan==null) assertTrue(tree.get("requires_plan").isNull());
            else {assertTrue(tree.get("requires_plan").isBoolean());assertEquals(requiresPlan.booleanValue(),tree.get("requires_plan").booleanValue());}
        }
    }
    @Test void requirementOptionIdsRemainExactInOnlyFourSnakeCaseFields()throws Exception {
        long id=9007199254740993L;
        var value=com.skillforge.platform.dto.capability.CapabilityRequirementOptionResponse.from(
                new com.skillforge.core.capability.CapabilityRequirementOption(id,"当前发布",id+2,"v2"));
        var tree=json.readTree(json.writeValueAsString(value));
        assertEquals(4,tree.size());assertId(tree,"definition_id",id);assertId(tree,"version_id",id+2);
        assertEquals("当前发布",tree.path("name").asText());assertEquals("v2",tree.path("version_key").asText());
        assertFalse(tree.has("descriptor"));assertFalse(tree.has("files"));assertFalse(tree.has("tenant_id"));
    }
    @Test void skillCatalogHasDescriptionAndPreciseNullableVersionIdentity()throws Exception {
        long id=9007199254740993L;
        var summary=new com.skillforge.core.capability.SkillCatalogSummary(id,"skill-key","技能","处理订单","PUBLISHED",id+2,"v1");
        var tree=json.valueToTree(com.skillforge.platform.dto.capability.SkillCatalogSummaryResponse.from(summary));
        assertEquals(7,tree.size());
        assertId(tree,"id",id);assertId(tree,"default_version_id",id+2);
        assertEquals("skill-key",tree.path("capability_key").asText());
        assertEquals("v1",tree.path("default_version_key").asText());
        assertFalse(tree.has("descriptor"));assertFalse(tree.has("files"));
        assertEquals("处理订单",tree.path("description").asText());assertFalse(tree.has("tenant_id"));
        summary=new com.skillforge.core.capability.SkillCatalogSummary(id,"draft","草稿",null,"DRAFT",null,null);
        tree=json.valueToTree(com.skillforge.platform.dto.capability.SkillCatalogSummaryResponse.from(summary));
        assertEquals(7,tree.size());assertTrue(tree.get("default_version_id").isNull());
        assertTrue(tree.get("default_version_key").isNull());
    }
    @Test void catalogDefinitionAndDefaultVersionIdsArePreciseStrings()throws Exception {
        var value=new CapabilityDefinitionResponse(9007199254740993L,null,CapabilityType.TOOL,"tool","工具",null,"platform","PUBLISHED",9007199254740995L,4L,null,null);
        var tree=json.readTree(json.writeValueAsString(value));
        assertTrue(tree.get("id").isTextual());assertEquals("9007199254740993",tree.get("id").textValue());
        assertTrue(tree.get("default_version_id").isTextual());assertEquals("9007199254740995",tree.get("default_version_id").textValue());
        assertTrue(tree.get("tenant_id").isNull());assertTrue(tree.get("revision").isIntegralNumber());
        assertEquals(4L,tree.get("revision").longValue());
    }
    @Test void catalogVersionAndValidationIdsRemainExactWithNumericRevision()throws Exception {
        long id=9007199254740993L;
        var version=new com.skillforge.platform.dto.capability.CapabilityVersionResponse(id,id+2,id+4,
                CapabilityType.SKILL,"v1","技能",null,"platform",java.util.Map.of(),"hash","PUBLISHED",null,null);
        var tree=json.readTree(json.writeValueAsString(version));
        assertId(tree,"id",id);assertId(tree,"definition_id",id+2);assertId(tree,"tenant_id",id+4);
        var validation=new com.skillforge.platform.dto.capability.CapabilityValidationResponse(id,id+2,7L,true,java.util.List.of());
        tree=json.readTree(json.writeValueAsString(validation));
        assertId(tree,"definition_id",id);assertId(tree,"version_id",id+2);
        assertTrue(tree.get("definition_revision").isIntegralNumber());assertEquals(7,tree.get("definition_revision").longValue());
    }
    @Test void skillDraftAndPublicationIdsArePreciseStrings()throws Exception {
        long id=9007199254740993L;
        var draft=new com.skillforge.core.capability.SkillDraftView(id,id+2,id+4,8L,"v1",
                java.util.Map.of(),true,true,com.skillforge.core.capability.SkillActivationMode.ALWAYS,
                null,java.util.List.of(),id+6,null,null);
        var tree=json.readTree(json.writeValueAsString(draft));
        assertId(tree,"id",id);assertId(tree,"definition_id",id+2);assertId(tree,"base_version_id",id+4);assertId(tree,"published_version_id",id+6);
        assertTrue(tree.get("revision").isIntegralNumber());assertEquals(8,tree.get("revision").longValue());
        tree=json.readTree(json.writeValueAsString(new com.skillforge.core.capability.SkillDraftPublication(id+6,9L)));
        assertId(tree,"version_id",id+6);assertTrue(tree.get("definition_revision").isIntegralNumber());
        assertEquals(9,tree.get("definition_revision").longValue());
    }
    private static void assertId(com.fasterxml.jackson.databind.JsonNode tree,String field,long id) {
        assertTrue(tree.get(field).isTextual(),field+" must reach JSON as string");
        assertEquals(Long.toString(id),tree.get(field).textValue());
    }
}
