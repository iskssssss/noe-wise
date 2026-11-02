package com.skillforge.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.mapper.AgentPublicationMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 所有发布消费者必须统一执行公共部署解析，不能因管理和Runtime入口不同而选择不同版本。 */
class AgentExecutionPublicationResolverTest {
    private final AgentService agents=mock(AgentService.class);
    private final AgentPublicationMapper mapper=mock(AgentPublicationMapper.class);
    private final CapabilityMountPersistenceService mounts=mock(CapabilityMountPersistenceService.class);
    private final AgentExecutionPublicationResolver resolver=mock(AgentExecutionPublicationResolver.class);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @SuppressWarnings("unchecked") private final ObjectProvider<AgentExecutionPublicationResolver> provider=mock(ObjectProvider.class);
    private final AgentPublicationService service=new AgentPublicationService(agents,mapper,mounts,json,provider);

    /** 普通直聊的新Run直接采用部署解析结果，旧机构发布行不能替代该结果。 */
    @Test void executionRequiresDeploymentPublicationWhenPresent() {
        when(agents.getOne(any())).thenReturn(agent());when(provider.orderedStream()).thenAnswer(call->Stream.of(resolver));
        var publication=new AgentPublicationService.Publication(101L,1,agent(),new CapabilityMountSetResult(7L,11L,1,List.of()),Instant.EPOCH,"1.1.0",31L);
        when(resolver.resolve(7,11)).thenReturn(Optional.of(publication));
        assertSame(publication,service.requirePublished(7,11));verifyNoInteractions(mapper);
    }

    /** 依赖或授权失败必须传播，不回退仍存在的机构旧发布快照。 */
    @Test void notReadyCannotFallBackToIndependentSnapshot() {
        when(agents.getOne(any())).thenReturn(agent());when(provider.orderedStream()).thenAnswer(call->Stream.of(resolver));
        when(resolver.resolve(7,11)).thenThrow(new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "AGENT_DEPLOYMENT_NOT_READY","缺绑定",false));
        var rejected=assertThrows(ApiException.class,()->service.requirePublished(7,11));
        assertEquals("AGENT_DEPLOYMENT_NOT_READY",rejected.getCode());verifyNoInteractions(mapper);
    }

    /** 渠道和团队成员目录也采用相同当前公共定义，不投影过时的创建副本。 */
    @Test void batchCatalogUsesTheSameDeploymentResolver() throws Exception {
        when(provider.orderedStream()).thenAnswer(call->Stream.of(resolver));
        var row=new AgentPublicationMapper.Row(101L,11L,1,json.writeValueAsString(new AgentPublicationService.Snapshot(agent(),1,List.of())),Instant.EPOCH);
        when(mapper.current(7,List.of(11L))).thenReturn(List.of(row));
        var publication=new AgentPublicationService.Publication(101L,1,agent(),new CapabilityMountSetResult(7L,11L,1,List.of()),Instant.EPOCH,"1.1.0",31L);
        when(resolver.resolve(7,11)).thenReturn(Optional.of(publication));
        assertSame(publication,service.currentPublishedAll(7,List.of(11L)).get(11L));
    }

    /** 独立Agent仍以自身不可变发布修订为权威，来源模板不变成跟随策略。 */
    @Test void independentInstallationKeepsItsOwnRevisionAuthority() throws Exception {
        when(agents.getOne(any())).thenReturn(agent());when(provider.orderedStream()).thenAnswer(call->Stream.of(resolver));
        when(resolver.resolve(7,11)).thenReturn(Optional.empty());
        var row=new AgentPublicationMapper.Row(101L,11L,1,json.writeValueAsString(new AgentPublicationService.Snapshot(agent(),1,List.of())),Instant.EPOCH);
        when(mapper.find(7,11,101)).thenReturn(row);
        var publication=service.requirePublished(7,11);
        assertEquals("agent:1",publication.definitionVersion());assertNull(publication.definitionTemplateId());
        assertEquals("机构自身提示词",publication.agent().getSystemPrompt());
    }

    /** 提供有独立发布快照的稳定机构身份。 */
    private AgentDO agent() {var a=new AgentDO();a.setId(11L);a.setTenantId(7L);a.setName("医生");a.setStatus("published");
        a.setRevision(1);a.setPublishedRevisionId(101L);a.setSystemPrompt("机构自身提示词");a.setConfig(Map.of());return a;}
}
