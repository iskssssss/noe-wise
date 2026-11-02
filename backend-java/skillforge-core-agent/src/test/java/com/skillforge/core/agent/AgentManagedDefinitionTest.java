package com.skillforge.core.agent;

import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 普通草稿编辑不能成为覆盖平台公共定义的旁路。 */
class AgentManagedDefinitionTest {
    private final AgentDeploymentMapper deployments=mock(AgentDeploymentMapper.class);
    private final AgentService service=new AgentService(deployments);
    /** 公共部署只能通过本地槽位接口修改，源定义由平台发布。 */
    @Test void managedDefinitionRejectsIndependentEditing() {
        var agent=agent();
        when(deployments.findAgent(7,11)).thenReturn(new AgentDeploymentMapper.Row(51L,"integration",7L,11L,31L,41L,1L,"doctor","fingerprint","{}",1,Instant.EPOCH,"{}"));
        var rejected=assertThrows(ApiException.class,()->service.requireEditable(agent));
        assertEquals("AGENT_DEFINITION_MANAGED",rejected.getCode());
    }
    /** 独立安装的模板Agent仍拥有自己的发布周期。 */
    @Test void independentDefinitionRemainsEditable() {assertDoesNotThrow(()->service.requireEditable(agent()));}
    /** 归档状态在部署检查之前阻止修改。 */
    @Test void archivedIdentityCannotBeEdited() {var agent=agent();agent.setStatus("archived");
        assertThrows(ApiException.class,()->service.requireEditable(agent));verifyNoInteractions(deployments);}
    private AgentDO agent(){var agent=new AgentDO();agent.setTenantId(7L);agent.setId(11L);agent.setStatus("published");return agent;}
}
