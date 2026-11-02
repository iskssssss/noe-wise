package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.institution.team.TeamService;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.session.SqlSession;

/** 精确原挂载与正式descriptor组成Workflow目标，不从实例当前发布指针选择版本。 */
class AgentRuntimeExecutionWorkflowMountsTest {
    @Test
    void targetUsesOriginalInstanceDefinitionInstallationAndDescriptorTimeout() {
        var fixture = new Fixture();
        var target = fixture.mounts.workflows(fixture.mapper, fixture.identity, fixture.member).getFirst();
        assertThat(target.handle().value()).isEqualTo("workflow:11:workflow-input");
        assertThat(target.instanceId()).isEqualTo("51"); assertThat(target.instanceRevision()).isEqualTo(61);
        assertThat(target.installationRevision()).isEqualTo(71); assertThat(target.timeout()).isEqualTo(Duration.ofSeconds(37));
        assertThat(target.capability().capabilityId()).isEqualTo("31"); assertThat(target.capability().versionId()).isEqualTo("41");
        verify(fixture.mapper).workflow("1", "31", "41"); verifyNoMoreInteractions(fixture.mapper);
    }

    @Test
    void nonModelInvocableWorkflowDoesNotEnterNativeModelDirectory() {
        var fixture = new Fixture(); fixture.row.descriptorJson = fixture.row.descriptorJson.replace("\"model_invocable\":true", "\"model_invocable\":false");
        assertThat(fixture.mounts.workflows(fixture.mapper, fixture.identity, fixture.member)).isEmpty();
    }

    @Test
    void currentDispatchRejectionDoesNotReplaceOriginalFrozenTarget() {
        var fixture = new Fixture(); var target = fixture.mounts.workflows(fixture.mapper, fixture.identity, fixture.member).getFirst();
        var session = mock(SqlSession.class); when(session.getMapper(AgentRuntimeExecutionMounts.Mapper.class)).thenReturn(fixture.mapper);
        assertThatThrownBy(() -> fixture.mounts.current(session, fixture.identity, target, fixture.member)).isInstanceOf(IllegalArgumentException.class);
        verify(fixture.mapper).currentWorkflow("1", "31", "41", "51", 61, 71);
        assertThat(fixture.mounts.workflows(fixture.mapper, fixture.identity, fixture.member)).containsExactly(target);
    }

    @Test void explicitInvocationUsesUserInvocationPolicyAndRejectsNonPublishedVersions() {
        var f=new Fixture();
        f.row.descriptorJson=f.row.descriptorJson.replace("\"model_invocable\":true","\"model_invocable\":false");
        assertThat(f.mounts.authorizedWorkflows(f.mapper,f.identity,f.member)).hasSize(1);
        f.row.descriptorJson=f.row.descriptorJson.replace("\"user_invocable\":true","\"user_invocable\":false");
        assertThat(f.mounts.authorizedWorkflows(f.mapper,f.identity,f.member)).isEmpty();
        f.row.descriptorJson=f.row.descriptorJson.replace("\"user_invocable\":false","\"user_invocable\":true");
        // 正式查询在SQL层过滤非PUBLISHED版本，缺失结果必须拒绝。
        when(f.mapper.workflow("1","31","41")).thenReturn(null);
        assertThatThrownBy(()->f.mounts.authorizedWorkflows(f.mapper,f.identity,f.member)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void explicitInvocationRequiresOriginalMountAndCurrentPublishedTarget() {
        var f=new Fixture();var target=f.mounts.authorizedWorkflows(f.mapper,f.identity,f.member).getFirst();
        var sql=mock(SqlSession.class);when(sql.getMapper(AgentRuntimeExecutionMounts.Mapper.class)).thenReturn(f.mapper);
        assertThatThrownBy(()->f.mounts.currentAuthorized(sql,f.identity,target,f.member)).isInstanceOf(IllegalArgumentException.class);
        verify(f.mapper).currentWorkflow("1","31","41","51",61,71);
        when(f.member.mounts()).thenReturn(new CapabilityMountSetResult(1L,2L,1,List.of()));
        assertThat(f.mounts.authorizedWorkflows(f.mapper,f.identity,f.member)).isEmpty();
        assertThatThrownBy(()->f.mounts.currentAuthorized(sql,f.identity,target,f.member)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class Fixture {
        private final AgentRuntimeExecutionMounts mounts = new AgentRuntimeExecutionMounts(new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
        private final AgentRuntimeExecutionMounts.Mapper mapper = mock(AgentRuntimeExecutionMounts.Mapper.class);
        private final TeamService.FrozenMember member = mock(TeamService.FrozenMember.class);
        private final AgentRuntimeExecutionMounts.WorkflowRow row = new AgentRuntimeExecutionMounts.WorkflowRow();
        private final RunIdentity identity;

        private Fixture() {
            var tenant = new TenantId("1"); var owner = SessionOwner.user(new SessionRef(tenant, "workflow-session"), new AgentId("2"), new EndUserId(3));
            var input = new InputId("workflow-input"); identity = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "workflow-run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
            var mount = new CapabilityMount("11", 1L, 2L,
                    new com.skillforge.core.capability.CapabilityRef(com.skillforge.core.capability.CapabilityType.WORKFLOW, "31", "41"),
                    new CapabilityMount.WorkflowMountConfig("51", 61, 71), 1, 1, true);
            when(member.agentId()).thenReturn("2"); when(member.mounts()).thenReturn(new CapabilityMountSetResult(1L, 2L, 1, List.of(mount)));
            row.versionTenant = 1L; row.name = "Workflow"; row.description = "Frozen workflow"; row.trustLevel = "TENANT";
            row.status = "PUBLISHED"; row.contentHash = "workflow-hash";
            row.descriptorJson = "{\"model_invocable\":true,\"user_invocable\":true,\"input_schema\":{},\"output_schema\":{},\"supports_hitl\":true,\"export_fields\":[],\"timeout_ms\":37000,\"concurrency_safe\":false}";
            when(mapper.workflow("1", "31", "41")).thenReturn(row);
        }
    }
}
