package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.institution.team.TeamService;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.KnowledgeCapacity;
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
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** 初载精确pin与派发当前权限分离；不以Mock代表真实PG或完整知识检索验收。 */
class AgentRuntimeExecutionKnowledgeMountsTest {
    /** 初载只读冻结revision，descriptor citation/来源版本原样保留，绝不查询active指针。 */
    @Test
    void loadsExactPinnedKnowledgeWithoutCurrentPublicationQuery() {
        var fixture = new Fixture();
        var target = fixture.mounts.knowledge(fixture.mapper, fixture.identity, fixture.member).getFirst();
        assertThat(target.target().descriptorId()).isEqualTo("71");
        assertThat(target.target().descriptorRevision()).isEqualTo(4);
        assertThat(target.target().timeout()).isEqualTo(Duration.ofSeconds(KnowledgeCapacity.DEFAULT_TIMEOUT_SECONDS));
        assertThat(target.target().sourceVersions()).extracting(value -> value.sourceVersion()).containsExactly("v1");
        assertThat(target.citationRequired()).isTrue(); assertThat(target.modelInvocable()).isTrue();
        verify(fixture.mapper).frozenKnowledge("1", "31", "41", "51");
        verify(fixture.mapper, never()).knowledge(anyString(), anyString(), anyString(), anyString());
    }

    /** 当前停用不抹原冻结材料，但joined dispatch查询缺失时拒绝继续调用。 */
    @Test
    void existingFrozenTargetRemainsReadableWhileDisabledDispatchIsRejected() {
        var fixture = new Fixture();
        var target = fixture.mounts.knowledge(fixture.mapper, fixture.identity, fixture.member).getFirst().target();
        var session = mock(SqlSession.class); when(session.getMapper(AgentRuntimeExecutionMounts.Mapper.class)).thenReturn(fixture.mapper);
        when(fixture.mapper.knowledge("1", "31", "41", "51")).thenReturn(null);
        assertThatThrownBy(() -> fixture.mounts.current(session, fixture.identity, target, fixture.member)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.mounts.knowledge(fixture.mapper, fixture.identity, fixture.member).getFirst().target()).isEqualTo(target);
    }

    /** 尚未解析FOLLOW_ACTIVE的材料拒绝，不在恢复时悄悄选择另一个revision。 */
    @Test
    void rejectsUnpinnedKnowledgeBeforeAnySourceRead() {
        var fixture = new Fixture();
        var unresolved = new CapabilityMount("11", 1L, 2L, fixture.mount.capability(),
                new CapabilityMount.KnowledgeMountConfig(CapabilityMount.KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE, null), 1, 1, true);
        when(fixture.member.mounts()).thenReturn(new CapabilityMountSetResult(1L, 2L, 1, List.of(unresolved)));
        clearInvocations(fixture.mapper);
        assertThatThrownBy(() -> fixture.mounts.knowledge(fixture.mapper, fixture.identity, fixture.member)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(fixture.mapper);
    }

    /** 只建立一个合法精确pin及正式descriptor；所有目标漂移需来自明确改变的源事实。 */
    private static final class Fixture {
        private final AgentRuntimeExecutionMounts mounts = new AgentRuntimeExecutionMounts(new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE));
        private final AgentRuntimeExecutionMounts.Mapper mapper = mock(AgentRuntimeExecutionMounts.Mapper.class);
        private final TeamService.FrozenMember member = mock(TeamService.FrozenMember.class);
        private final CapabilityMount mount = new CapabilityMount("11", 1L, 2L,
                new com.skillforge.core.capability.CapabilityRef(com.skillforge.core.capability.CapabilityType.KNOWLEDGE_SOURCE, "31", "41"),
                new CapabilityMount.KnowledgeMountConfig(CapabilityMount.KnowledgeMountConfig.BindingMode.PINNED_REVISION, "51"), 1, 1, true);
        private final RunIdentity identity;

        /** 冻结读和当前读拥有独立预期，防止把当前状态当历史配置权威。 */
        private Fixture() {
            var tenant = new TenantId("1"); var owner = SessionOwner.user(new SessionRef(tenant, "knowledge-session"), new AgentId("2"), new EndUserId(3));
            var input = new InputId("knowledge-input");
            identity = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "knowledge-run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
            when(member.agentId()).thenReturn("2"); when(member.mounts()).thenReturn(new CapabilityMountSetResult(1L, 2L, 1, List.of(mount)));
            var row = new AgentRuntimeExecutionMounts.KnowledgeRow();
            row.versionTenant = 1L; row.name = "Knowledge"; row.description = "Pinned source";
            row.trustLevel = "TENANT"; row.status = "PUBLISHED"; row.contentHash = "source-hash";
            row.sourceId = "71"; row.revision = 4; row.sourceVersions = "[\"v1\"]";
            row.descriptorJson = "{\"model_invocable\":true,\"user_invocable\":true,\"source_id\":\"71\",\"source_versions\":[\"v1\"],\"allowed_operators\":[\"KEYWORD\"],\"freshness_policy\":\"PINNED\",\"citation_required\":true}";
            when(mapper.frozenKnowledge("1", "31", "41", "51")).thenReturn(row);
            when(mapper.knowledge("1", "31", "41", "51")).thenReturn(row);
        }
    }
}
