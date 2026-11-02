package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.institution.team.TeamService;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** 可信用户调用与模型目录独立授权，二者仍消费同一精确冻结挂载和事务内当前版本。 */
class AuthorizedWorkflowTargetTest {
    @Test void userOnlyWorkflowResolvesItsExactTargetWithoutEnteringTheModelDirectory() {
        var f = new Fixture(false, true);

        assertTrue(f.mounts.workflows(f.mapper, f.identity, f.member).isEmpty());
        assertEquals(List.of(f.target), f.mounts.authorizedWorkflows(f.mapper, f.identity, f.member));
        assertEquals(f.target, f.mounts.currentAuthorized(f.sql, f.identity, f.target, f.member));
        assertThrows(IllegalArgumentException.class, () -> f.mounts.current(f.sql, f.identity, f.target, f.member));

        verify(f.mapper, times(2)).currentWorkflow("7", "111", "121", "91", 101, 131);
        assertEquals("workflow:13:initial-request", f.target.handle().value());
    }

    @Test void workflowWithBothInvocationFlagsDisabledIsUnavailableToEitherEntry() {
        var f = new Fixture(false, false);

        assertTrue(f.mounts.workflows(f.mapper, f.identity, f.member).isEmpty());
        assertTrue(f.mounts.authorizedWorkflows(f.mapper, f.identity, f.member).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> f.mounts.current(f.sql, f.identity, f.target, f.member));
        assertThrows(IllegalArgumentException.class, () -> f.mounts.currentAuthorized(f.sql, f.identity, f.target, f.member));
    }

    @Test void modelInvocationPermissionDoesNotGrantTheTrustedUserEntry() {
        var f = new Fixture(true, false);

        assertEquals(List.of(f.target), f.mounts.workflows(f.mapper, f.identity, f.member));
        assertEquals(f.target, f.mounts.current(f.sql, f.identity, f.target, f.member));
        assertTrue(f.mounts.authorizedWorkflows(f.mapper, f.identity, f.member).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> f.mounts.currentAuthorized(f.sql, f.identity, f.target, f.member));
    }

    @Test void authorizedCurrentGuardRejectsTargetDriftMissingCurrentVersionAndRevokedUserPermission() {
        var f = new Fixture(false, true);
        var original = f.mounts.authorizedWorkflows(f.mapper, f.identity, f.member).getFirst();
        clearInvocations(f.mapper);
        var drifted = new FrozenWorkflowTarget(original.handle(), original.instanceId(), original.instanceRevision(),
                original.capability(), original.timeout(), original.installationRevision() + 1);
        assertThrows(IllegalArgumentException.class, () -> f.mounts.currentAuthorized(f.sql, f.identity, drifted, f.member));

        when(f.mapper.currentWorkflow("7", "111", "121", "91", 101, 131)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> f.mounts.currentAuthorized(f.sql, f.identity, original, f.member));
        when(f.mapper.currentWorkflow("7", "111", "121", "91", 101, 131)).thenReturn(f.row(false, false));
        assertThrows(IllegalArgumentException.class, () -> f.mounts.currentAuthorized(f.sql, f.identity, original, f.member));

        verify(f.mapper, times(3)).currentWorkflow("7", "111", "121", "91", 101, 131);
        verify(f.mapper, never()).workflow("7", "111", "121");
    }

    /** 使用正式descriptor codec、冻结成员和挂载record；只控制目录端口，不模拟解析规则。 */
    private static final class Fixture {
        private final AgentRuntimeExecutionMounts.Mapper mapper = mock(AgentRuntimeExecutionMounts.Mapper.class);
        private final SqlSession sql = mock(SqlSession.class);
        private final AgentRuntimeExecutionMounts mounts = new AgentRuntimeExecutionMounts(
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
        private final TenantId tenant = new TenantId("7");
        private final InputId input = new InputId("initial-request");
        private final SessionOwner owner = SessionOwner.user(new SessionRef(tenant, "test-session"), new AgentId("31"), new EndUserId(51));
        private final RunIdentity identity = new RunIdentity(owner, new AgentId("31"), input, new RunRef(tenant, "run:initial-request"),
                new TraceEnvelope("original-trace", "original-span", input, Optional.empty()), new ResultRef("verified-original"),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, "input:initial-request"));
        private final com.skillforge.core.capability.CapabilityRef reference = new com.skillforge.core.capability.CapabilityRef(
                com.skillforge.core.capability.CapabilityType.WORKFLOW, "111", "121");
        private final CapabilityMount mount = new CapabilityMount("13", 7L, 31L, reference,
                new CapabilityMount.WorkflowMountConfig("91", 101, 131), 1, 1, true);
        private final TeamService.FrozenMember member = new TeamService.FrozenMember("31", "原测试Agent", "原系统说明", "原负责人",
                AgentExecutionConfig.inherited(), "original-model", 1, new CapabilityMountSetResult(7L, 31L, 1, List.of(mount)),
                301L, 1, AgentDelegationConfig.disabled());
        private final FrozenWorkflowTarget target = new FrozenWorkflowTarget(new WorkflowHandle("workflow:13:initial-request"), "91", 101,
                new CapabilityRef(CapabilityType.WORKFLOW, "111", "121"), Duration.ofSeconds(37), 131);

        private Fixture(boolean modelInvocable, boolean userInvocable) {
            var row = row(modelInvocable, userInvocable);
            when(mapper.workflow("7", "111", "121")).thenReturn(row);
            when(mapper.currentWorkflow("7", "111", "121", "91", 101, 131)).thenReturn(row);
            when(sql.getMapper(AgentRuntimeExecutionMounts.Mapper.class)).thenReturn(mapper);
        }

        private AgentRuntimeExecutionMounts.WorkflowRow row(boolean modelInvocable, boolean userInvocable) {
            var row = new AgentRuntimeExecutionMounts.WorkflowRow();
            row.versionTenant = 7L; row.name = "原工作流"; row.description = "精确已发布版本";
            row.trustLevel = "TENANT"; row.status = "PUBLISHED"; row.contentHash = "original-workflow-hash";
            row.descriptorJson = """
                    {"model_invocable":%s,"user_invocable":%s,"input_schema":{"type":"object"},
                     "output_schema":{"type":"object"},"supports_hitl":true,"export_fields":["answer"],
                     "timeout_ms":37000,"concurrency_safe":true}
                    """.formatted(modelInvocable, userInvocable);
            return row;
        }
    }
}
