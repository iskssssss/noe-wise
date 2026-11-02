package com.skillforge.core.agent.deployment;

import com.skillforge.core.agent.deployment.AgentBindingDeclarations.BindingShape;
import com.skillforge.core.agent.deployment.AgentBindingDeclarations.ResourceSelection;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.capability.CapabilityType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentBindingDeclarationsTest {
    private AgentTemplateDefinition definition() {
        return new AgentTemplateDefinition("{{parameters.store}} {{parameters.store}}","role","1",null,List.of(),
                List.of(new AgentTemplateDefinition.ResourceSlot("knowledge","知识","",CapabilityType.KNOWLEDGE_SOURCE)),
                List.of(new AgentTemplateDefinition.DelegationSlot("assistant","助理","")));
    }

    @Test void missingResourceRoleAndParameterHaveSameFormalPathsAsDeploymentReadiness() {
        var issues=AgentBindingDeclarations.assess(definition(),new BindingShape(List.of(),List.of(),Set.of()));
        assertEquals(List.of("resource_bindings.knowledge","delegation_bindings.assistant","parameters.store"),
                issues.stream().map(issue->issue.path()).toList());
        assertEquals(List.of("binding_required","binding_required","parameter_required"),issues.stream().map(issue->issue.code()).toList());
    }

    @Test void matchingOnlyDeclarationsDoesNotNeedAnyResourceIdentifierOrParameterValue() {
        var shape=new BindingShape(List.of(new ResourceSelection("knowledge",CapabilityType.KNOWLEDGE_SOURCE)),List.of("assistant"),Set.of("store"));
        assertTrue(AgentBindingDeclarations.assess(definition(),shape).isEmpty());
    }

    @Test void changedTypeAndRemovedOrDuplicateBindingCannotCountAsSatisfied() {
        for(var resources:List.of(List.of(new ResourceSelection("knowledge",CapabilityType.WORKFLOW)),
                List.of(new ResourceSelection("old",CapabilityType.KNOWLEDGE_SOURCE)),
                List.of(new ResourceSelection("knowledge",CapabilityType.KNOWLEDGE_SOURCE),new ResourceSelection("knowledge",CapabilityType.KNOWLEDGE_SOURCE)))) {
            var issues=AgentBindingDeclarations.assess(definition(),new BindingShape(resources,List.of("assistant"),Set.of("store")));
            assertEquals("binding_definition_changed",issues.getFirst().code());
            assertEquals("bindings",issues.getFirst().path());
        }
    }

    @Test void invalidRoleAndParameterKeyAreRejectedWithoutEchoingPrivateBindingKeys() {
        var issues=AgentBindingDeclarations.assess(definition(),new BindingShape(List.of(),List.of("private-role"),Set.of("secret.bad")));
        assertEquals("binding_definition_changed",issues.getFirst().code());
        assertFalse(issues.toString().contains("private-role"));assertFalse(issues.toString().contains("secret.bad"));
    }

    @Test void shapeMissingAWholeStructureIsTechnicalFailureRatherThanEmptyBindings() {
        assertThrows(NullPointerException.class,()->new BindingShape(null,List.of(),Set.of()));
        assertThrows(NullPointerException.class,()->new BindingShape(List.of(),null,Set.of()));
        assertThrows(NullPointerException.class,()->new BindingShape(List.of(),List.of(),null));
    }
}
