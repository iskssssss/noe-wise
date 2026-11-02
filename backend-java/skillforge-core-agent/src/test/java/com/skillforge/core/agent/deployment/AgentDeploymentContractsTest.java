package com.skillforge.core.agent.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.skillforge.core.agent.deployment.AgentDeploymentContracts.*;

/** 新公开部署协议分别保护雪花字符串ID与数值CAS，不接受隐式coercion。 */
class AgentDeploymentContractsTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    @Test void decimalStringIdsRoundTripWithoutJavascriptPrecisionLoss()throws Exception {
        String id="9007199254740993";
        var command=json.readValue("{\"definition_id\":\""+id+"\",\"grant_id\":\"41\",\"external_agent_ref\":\"doctor\",\"resource_bindings\":[],\"delegation_bindings\":[],\"parameters\":{}}",Deploy.class);
        assertEquals(Long.valueOf(id),command.definitionId());
        assertEquals(id,json.readTree(json.writeValueAsString(command)).get("definition_id").textValue());
    }
    @Test void numericIdsAndStringRevisionAreRejected() {
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->json.readValue("{\"definition_id\":9007199254740993,\"grant_id\":\"41\",\"external_agent_ref\":\"doctor\"}",Deploy.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->json.readValue("{\"deployment_id\":\"31\",\"expected_revision\":\"1\"}",Bindings.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->json.readValue("{\"definition_id\":\"31\",\"grant_id\":\"41\",\"integration_id\":7,\"external_agent_ref\":\"doctor\"}",Deploy.class));
    }
    @Test void responseIdsAreStringsAndRevisionsRemainNumbers()throws Exception {
        var view=new View(31L,7L,null,11L,"doctor",51L,41L,1,"定义","1.0.0","FOLLOW_PUBLISHED",2,true,List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),chain());
        var tree=json.readTree(json.writeValueAsString(view));
        assertEquals("31",tree.get("deployment_id").textValue());assertEquals("7",tree.get("tenant_id").textValue());
        assertTrue(tree.get("deployment_revision").isIntegralNumber());assertTrue(tree.get("grant_revision").isIntegralNumber());
        var chainTree=tree.get("grant_chain");
        assertEquals("51",chainTree.get("definition_id").textValue());
        assertEquals("9007199254740993",chainTree.get("owner_tenant_id").textValue());
        assertEquals(2,chainTree.get("hops").size());
        var root=chainTree.get("hops").get(0);var leaf=chainTree.get("hops").get(1);
        assertEquals("9007199254740995",root.get("grant_id").textValue());
        assertEquals("9007199254740993",root.get("grantor_tenant_id").textValue());
        assertEquals("9007199254740997",root.get("target_tenant_id").textValue());
        assertEquals("9007199254740999",root.get("relation_ref").textValue());
        assertTrue(root.get("parent_grant_id").isNull());assertTrue(root.get("parent_grant_revision").isNull());
        assertEquals("41",leaf.get("grant_id").textValue());assertEquals("7",leaf.get("target_tenant_id").textValue());
        assertEquals("9007199254740995",leaf.get("parent_grant_id").textValue());
        assertEquals("9007199254741001",leaf.get("relation_ref").textValue());
        for(var hop:chainTree.get("hops")) {
            assertTrue(hop.get("revision").isIntegralNumber());assertTrue(hop.get("relation_revision").isIntegralNumber());
        }
        assertTrue(leaf.get("parent_grant_revision").isIntegralNumber());
        assertEquals(1,leaf.get("parent_grant_revision").longValue());assertEquals(6,leaf.get("relation_revision").longValue());
        assertEquals(chain(),json.readValue(json.writeValueAsString(chain()),GrantChain.class));
    }
    /** 绑定DTO同时出现在请求和部署响应中，嵌套能力ID不得经过JavaScript number。 */
    @Test void bindingCapabilityIdsRoundTripAsDecimalStrings()throws Exception {
        var mount=new com.skillforge.core.agent.dto.request.CapabilityMountRequest(
                com.skillforge.core.capability.CapabilityType.TOOL,9007199254740993L,9007199254740995L,
                null,new com.skillforge.core.agent.dto.request.ToolMountConfigRequest("9007199254740997",1L),null,null,0);
        var binding=new ResourceBinding("tool",mount);
        var view=new View(31L,7L,null,11L,"doctor",51L,41L,1,"定义","1.0.0","FOLLOW_PUBLISHED",2,true,
                List.of(),List.of(),List.of(),List.of(binding),List.of(),Map.of(),chain());
        var encoded=json.writeValueAsString(view);
        var tree=json.readTree(encoded).get("resource_bindings").get(0).get("mount");
        assertEquals("9007199254740993",tree.get("capability_definition_id").textValue());
        assertEquals("9007199254740995",tree.get("capability_version_id").textValue());
        var restored=json.readValue(json.writeValueAsString(binding),ResourceBinding.class).mount();
        assertEquals(mount.capabilityDefinitionId(),restored.capabilityDefinitionId());
        assertEquals(mount.capabilityVersionId(),restored.capabilityVersionId());
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->json.readValue(
                "{\"slot_key\":\"tool\",\"mount\":{\"capability_type\":\"TOOL\",\"capability_definition_id\":9007199254740993,\"capability_version_id\":\"9007199254740995\"}}",ResourceBinding.class));
    }
    private GrantChain chain() {
        return new GrantChain(51L,"tenant",9007199254740993L,List.of(
                new GrantHop(9007199254740995L,1,"tenant",9007199254740993L,9007199254740997L,null,null,9007199254740999L,4L),
                new GrantHop(41L,2,"tenant",9007199254740997L,7L,9007199254740995L,1L,9007199254741001L,6L)));
    }
    /** 授权目录实际返回的基础能力引用使用相同严格字符串协议。 */
    @Test void directoryBaseCapabilityIdsArePreciseStrings()throws Exception {
        var base=new com.skillforge.core.agent.model.AgentTemplateDefinition.BaseCapability(
                com.skillforge.core.capability.CapabilityType.TOOL,9007199254740993L,9007199254740995L,null,"tool",0);
        var encoded=json.writeValueAsString(base);var tree=json.readTree(encoded);
        assertEquals("9007199254740993",tree.get("capability_definition_id").textValue());
        assertEquals("9007199254740995",tree.get("capability_version_id").textValue());
        assertEquals(base,json.readValue(encoded,com.skillforge.core.agent.model.AgentTemplateDefinition.BaseCapability.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->json.readValue(
                "{\"capability_type\":\"TOOL\",\"capability_definition_id\":\"9007199254740993\",\"capability_version_id\":9007199254740995}",
                com.skillforge.core.agent.model.AgentTemplateDefinition.BaseCapability.class));
    }
}
