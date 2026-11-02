package com.skillforge.institution.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OpenPlatformOpenApiContractTest {

    @Test
    void versionedContractDeclaresEveryPublishedOpenPlatformSurface() throws IOException {
        String contract = readContract();

        assertTrue(contract.contains("openapi: 3.1.0"));
        assertTrue(contract.contains("version: 1.0.0"));
        assertTrue(contract.contains("/open-platform/resources:"));
        assertTrue(contract.contains("/open-platform/resources/detail:"));
        assertTrue(contract.contains("/open-platform/resources/revisions:"));
        assertTrue(contract.contains("/open-platform/resources/revisions/ready:"));
        assertTrue(contract.contains("/open-platform/resources/revisions/publish:"));
        assertTrue(contract.contains("/open-platform/deployments/update:"));
        assertTrue(contract.contains("/open-platform/channels/binding:"));
        assertTrue(contract.contains("/open-platform/resources/revisions/detail:"));
        assertTrue(contract.contains("/open-platform/control-operations:"));
        assertTrue(contract.contains("/open-platform/sessions/messages:"));
        assertTrue(contract.contains("/open-platform/sessions/messages/receipt:"));
        assertTrue(contract.contains("MessageReceiptObservation:"));
        assertTrue(contract.contains("NOT_OBSERVED"));
        assertTrue(contract.contains("/open-platform/sessions/actions:"));
        assertTrue(contract.contains("/open-platform/sessions/callbacks:"));
        assertTrue(contract.contains("/open-platform/sessions/commands:"));
        assertTrue(contract.contains("/open-platform/sessions/events:"));
        assertTrue(contract.contains("/open-platform/resources/update:"));
        assertTrue(contract.contains("/open-platform/resources/revisions/update:"));
        assertTrue(contract.contains("/open-platform/agent-deployments/authorization:"));
        assertTrue(contract.contains("/open-platform/groups/ensure:"));
        assertTrue(contract.contains("/open-platform/groups/close:"));
        assertTrue(contract.contains("/open-platform/groups/detail:"));
        assertTrue(contract.contains("/open-platform/groups/members/update:"));
        assertTrue(contract.contains("/open-platform/groups/agents/update:"));
        assertTrue(contract.contains("/open-platform/groups/messages:"));
        assertTrue(contract.contains("/open-platform/groups/triggers:"));
        assertTrue(contract.contains("/open-platform/groups/triggers/detail:"));
        assertTrue(contract.contains("/open-platform/groups/runs/respond:"));
        assertTrue(contract.contains("/open-platform/groups/runs/commands:"));
        assertTrue(contract.contains("/open-platform/groups/runs/detail:"));
        assertTrue(contract.contains("/open-platform/groups/history:"));
        assertTrue(contract.contains("/open-platform/groups/events:"));
        assertTrue(contract.contains("/open-platform/groups/stream:"));
        assertTrue(contract.contains("/open-platform/groups/controls:"));
        assertTrue(contract.contains("/open-platform/groups/controls/current:"));
        assertTrue(contract.contains("/open-platform/groups/controls/detail:"));
        assertTrue(contract.contains("/open-platform/groups/controls/authorize:"));
        assertTrue(contract.contains("/open-platform/groups/controls/terminalize:"));
        assertTrue(contract.contains("/open-platform/groups/controls/release:"));
        assertTrue(contract.contains("/open-platform/groups/sender-endpoints/register:"));
        assertTrue(contract.contains("/open-platform/groups/sender-endpoints/retire:"));
        assertTrue(contract.contains("/open-platform/groups/sender-endpoints/ack:"));
        assertTrue(contract.contains("/open-platform/groups/deliveries/qualify:"));
        assertTrue(contract.contains("x-required-scope: groups.messages.write"));
        assertTrue(contract.contains("x-required-scope: groups.triggers.write"));
        assertTrue(contract.contains("x-required-scope: groups.triggers.read"));
        assertTrue(contract.contains("x-required-scope: groups.runs.respond"));
        assertTrue(contract.contains("x-required-scope: groups.runs.control"));
        assertTrue(contract.contains("x-required-scope: groups.runs.read"));
        assertTrue(contract.contains("x-required-scope: groups.members.write"));
        assertTrue(contract.contains("x-required-scope: groups.agents.write"));
        assertTrue(contract.contains("GroupView:"));
        assertTrue(contract.contains("UserSessionSubject:"));
        assertTrue(contract.contains("GroupSessionSubject:"));
        assertTrue(contract.contains("SwitchAuthorization:"));
        assertTrue(contract.contains("GrantChain:"));
        assertTrue(contract.contains("GrantHop:"));
        assertTrue(contract.contains("SessionMessageCommand:"));
        assertTrue(contract.contains("ResourceMappingCommand:"));
        assertFalse(contract.contains("in: path"));
        assertTrue(contract.contains("servicePrincipalHmac:"));
        assertTrue(contract.contains("x-required-scope: resources.read"));
        assertTrue(contract.contains("x-required-scope: sessions.events.read"));
        assertTrue(contract.contains("name: X-Resource-Revision"));
        assertTrue(contract.contains("ResourceMappingRequest:"));
        for (String resourceType : new String[] {
                "agent", "workflow", "skill", "persona", "policy", "tool", "connector", "channel", "knowledge_base"}) {
            assertTrue(contract.contains(resourceType), "resource type must be versioned: " + resourceType);
        }
    }

    @Test
    void contractDoesNotExposeDirectToolWorkflowOrCheckpointExecution() throws IOException {
        String contract = readContract();

        assertFalse(contract.contains("/tool/invoke"));
        assertFalse(contract.contains("/workflow/run"));
        assertFalse(contract.contains("/workflow/resume"));
        assertFalse(contract.contains("/skill/execute"));
        assertFalse(contract.contains("/checkpoint"));
    }

    @Test
    void explicitWorkflowAndContextArchiveDeclareTheirActualDistinctContracts() throws IOException {
        String contract=readContract();
        String workflow=contract.substring(contract.indexOf("  /open-platform/sessions/workflow-invocations:"),
                contract.indexOf("  /open-platform/sessions/context-messages:"));
        assertTrue(workflow.contains("servicePrincipalHmac: []"));
        assertTrue(workflow.contains("x-required-scope: sessions.write"));
        assertTrue(workflow.contains("#/components/parameters/IdempotencyKey"));
        assertTrue(workflow.contains("#/components/responses/InboundReceipt"));
        assertTrue(workflow.contains("workflow_invocation_input_invalid"));
        assertTrue(workflow.contains("execution_input_changed"));
        String context=contract.substring(contract.indexOf("  /open-platform/sessions/context-messages:"),
                contract.indexOf("  /open-platform/sessions/actions:"));
        assertTrue(context.contains("servicePrincipalHmac: []"));
        assertTrue(context.contains("x-required-scope: sessions.write"));
        assertFalse(context.contains("#/components/parameters/IdempotencyKey"));
        assertFalse(context.contains("#/components/responses/InboundReceipt"));
        assertTrue(context.contains("context_message_changed"));
        assertTrue(context.contains("context_message_session_closed"));
        String commands=contract.substring(contract.indexOf("    SessionWorkflowInvocationCommand:"),
                contract.indexOf("    SessionActionCommand:"));
        assertTrue(commands.contains("required: [session_id, mount_id, capability_id, version_id, parameters]"));
        assertTrue(commands.contains("required: [session_id, source_message_id]"));
        assertTrue(commands.contains("type: [string, 'null']"));
        assertTrue(commands.contains("maxLength: 65536"));
        assertTrue(commands.contains("required: [message_id, recorded, replayed]"));
        assertTrue(commands.contains("const: true"));
        assertTrue(commands.contains("additionalProperties: false"));
        assertTrue(commands.contains("pattern: '^[1-9][0-9]*$'"));
        assertTrue(contract.contains("/open-platform/sessions/messages/receipt:"));
        assertTrue(contract.contains("MessageReceiptObservation:"));
    }

    private String readContract() throws IOException {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("openapi/open-platform-v1.yaml")) {
            assertTrue(input != null, "versioned OpenAPI contract must be packaged");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
