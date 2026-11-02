package com.skillforge.institution.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.institution.dto.capability.CapabilityDefinitionResponse;
import com.skillforge.institution.dto.capability.CapabilityVersionResponse;
import com.skillforge.institution.dto.capability.CapabilityDeploymentResponse;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.tool.ToolDeployment;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 机构部署页真正读取的引用必须在JSON.parse前已是字符串，修订保持数值。 */
class CapabilityCatalogWireContractTest {
    private static final long ID=9007199254740993L;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    @Test void capabilityVersionSummaryHasFiveFieldsWithPreciseIdentityAndNullableDescription()throws Exception {
        for(String description:java.util.Arrays.asList("历史说明",null)) {
            var value=com.skillforge.institution.dto.capability.CapabilityVersionSummaryResponse.from(
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
    @Test void skillVersionSummaryHasOnlyThreeFieldsAndPreciseIdentity()throws Exception {
        var value=com.skillforge.institution.dto.capability.SkillVersionSummaryResponse.from(
                new com.skillforge.core.capability.SkillVersionSummary(9007199254740993L,"v1","DEPRECATED"));
        var tree=json.readTree(json.writeValueAsString(value));
        assertEquals(3,tree.size());assertId(tree,"id",9007199254740993L);
        assertEquals("v1",tree.path("version_key").asText());assertEquals("DEPRECATED",tree.path("status").asText());
        assertFalse(tree.has("descriptor"));assertFalse(tree.has("files"));
    }
    @Test void requirementOptionIdsRemainExactInOnlyFourSnakeCaseFields()throws Exception {
        long id=9007199254740993L;
        var value=com.skillforge.institution.dto.capability.CapabilityRequirementOptionResponse.from(
                new com.skillforge.core.capability.CapabilityRequirementOption(id,"当前发布",id+2,"v2"));
        var tree=json.readTree(json.writeValueAsString(value));
        assertEquals(4,tree.size());assertId(tree,"definition_id",id);assertId(tree,"version_id",id+2);
        assertEquals("当前发布",tree.path("name").asText());assertEquals("v2",tree.path("version_key").asText());
        assertFalse(tree.has("descriptor"));assertFalse(tree.has("files"));assertFalse(tree.has("tenant_id"));
    }
    @Test void definitionIdentityAndDefaultVersionAreStringsBeforeFrontendMapping()throws Exception {
        var value=new CapabilityDefinitionResponse(ID,ID+2,CapabilityType.TOOL,"tool","工具",null,"platform","PUBLISHED",ID+4,3L,null,null);
        var tree=wire(value);
        assertId(tree,"id",ID);assertId(tree,"tenant_id",ID+2);assertId(tree,"default_version_id",ID+4);
        assertTrue(tree.get("revision").isIntegralNumber());assertEquals(3,tree.get("revision").longValue());
    }
    @Test void versionAndToolBindingReferencesRemainExact()throws Exception {
        var version=new CapabilityVersionResponse(ID,ID+2,ID+4,CapabilityType.TOOL,"v1","工具",null,"platform",Map.of(),"hash","PUBLISHED",null,null);
        var versionTree=wire(version);
        assertId(versionTree,"id",ID);assertId(versionTree,"definition_id",ID+2);assertId(versionTree,"tenant_id",ID+4);
        var deployment=new CapabilityDeploymentResponse(ID,ID+2,"target-tool",ID+4,ToolDeployment.ProviderType.PLATFORM,"tool",ToolDeployment.Environment.PRODUCTION,"ACTIVE",5L,null,null);
        var tree=wire(deployment);
        assertId(tree,"id",ID);assertId(tree,"tenant_id",ID+2);assertId(tree,"tool_version_id",ID+4);
        assertTrue(tree.get("revision").isIntegralNumber());assertEquals(5,tree.get("revision").longValue());
    }
    @Test void workflowCapabilityReferenceAndAgentSelectorAreAlsoStrings()throws Exception {
        var workflow=new com.skillforge.core.workflow.dto.response.BuilderWorkflowInstanceResponse(1L,"workflow-instance","workflow",2L,4L,ID,"active",Map.of(),null,null);
        var workflowTree=wire(workflow);assertId(workflowTree,"capability_version_id",ID);
        assertTrue(workflowTree.get("installation_revision").isIntegralNumber());
        var agent=new com.skillforge.core.agent.dto.response.AgentItemResponse(ID,"agent","助手",null,null,
                com.skillforge.core.agent.enums.AgentStatus.PUBLISHED,null,com.skillforge.core.agent.model.AgentDelegationConfig.disabled(),
                null,null,3,null,null,false);
        var agentTree=wire(agent);assertId(agentTree,"id",ID);assertTrue(agentTree.get("revision").isIntegralNumber());
        var knowledge=new com.skillforge.core.knowledge.dto.response.KnowledgeBaseRevisionResponse(1L,2L,3L,
                "published",java.util.List.of(),null,null,null,null,ID,null);
        var knowledgeTree=wire(knowledge);assertId(knowledgeTree,"capability_version_id",ID);
        assertTrue(knowledgeTree.get("revision").isIntegralNumber());
    }
    @Test void validationAndMountIdsStayTextualWhileRevisionsStayNumeric()throws Exception {
        var validation=new com.skillforge.institution.dto.capability.CapabilityValidationResponse(ID,ID+2,7L,true,java.util.List.of());
        var tree=wire(validation);assertId(tree,"definition_id",ID);assertId(tree,"version_id",ID+2);
        assertTrue(tree.get("definition_revision").isIntegralNumber());assertEquals(7,tree.get("definition_revision").longValue());
        var mount=new com.skillforge.core.agent.dto.response.CapabilityMountResponse("mount",CapabilityType.SKILL,
                ID,ID+2,null,null,null,null,3,8L,com.skillforge.core.agent.capabilitymount.CapabilityMountStatus.ACTIVE);
        tree=wire(mount);assertId(tree,"capability_definition_id",ID);assertId(tree,"capability_version_id",ID+2);
        assertTrue(tree.get("revision").isIntegralNumber());assertEquals(8,tree.get("revision").longValue());
        assertTrue(tree.get("priority").isIntegralNumber());
    }
    @Test void skillDraftAndPublicationIdsStayExactBeforeFrontendMapping()throws Exception {
        var draft=new com.skillforge.core.capability.SkillDraftView(ID,ID+2,ID+4,8L,"v1",
                Map.of(),true,true,com.skillforge.core.capability.SkillActivationMode.ALWAYS,
                null,java.util.List.of(),ID+6,null,null);
        var tree=wire(draft);assertId(tree,"id",ID);assertId(tree,"definition_id",ID+2);
        assertId(tree,"base_version_id",ID+4);assertId(tree,"published_version_id",ID+6);
        assertTrue(tree.get("revision").isIntegralNumber());assertEquals(8,tree.get("revision").longValue());
        tree=wire(new com.skillforge.core.capability.SkillDraftPublication(ID+6,9L));
        assertId(tree,"version_id",ID+6);assertTrue(tree.get("definition_revision").isIntegralNumber());
        assertEquals(9,tree.get("definition_revision").longValue());
    }
    private JsonNode wire(Object value)throws Exception {return json.readTree(json.writeValueAsString(value));}
    private static void assertId(JsonNode tree,String field,long id) {
        assertTrue(tree.get(field).isTextual(),field+" must reach JSON as string");
        assertEquals(Long.toString(id),tree.get(field).textValue());
    }
}
