package com.skillforge.platform.service;

import com.skillforge.core.agent.AgentDefinitionLifecycleService;
import com.skillforge.core.agent.dto.query.AgentTemplateQuery;
import com.skillforge.core.agent.dto.request.CreateAgentTemplateRequest;
import com.skillforge.core.agent.dto.request.UpdateAgentTemplateRequest;
import com.skillforge.core.agent.dto.response.*;
import java.util.List;
import org.springframework.stereotype.Service;

/** 平台账号域适配；生命周期、事务、幂等和发布规则由共享服务唯一持有。 */
@Service
public class AgentTemplateAdminService {
    private final AgentDefinitionLifecycleService definitions;
    /** 注入唯一领域服务，不在宿主复制状态机。 */
    public AgentTemplateAdminService(AgentDefinitionLifecycleService definitions){this.definitions=definitions;}
    /** 平台当前对象范围内的源列表。 */
    public AdminAgentTemplateListResponse list(AgentTemplateQuery query){scope();return definitions.list(query);}
    /** 平台所有者草稿详情。 */
    public AdminAgentTemplateDetailResponse detail(Long id){scope();return definitions.detail(id);}
    /** 平台维护者历史快照。 */
    public List<AdminAgentTemplateDetailResponse> history(Long id){scope();return definitions.history(id);}
    /** 精确创建请求的原安全回执。 */
    public AdminAgentTemplateItemResponse create(CreateAgentTemplateRequest request,String key){scope();return definitions.create(request,key);}
    /** 精确修订编辑。 */
    public AdminAgentTemplateItemResponse update(Long id,long expected,UpdateAgentTemplateRequest request,String key){scope();return definitions.update(id,expected,request,key);}
    /** 编辑器当前定义校验。 */
    public AgentTemplateValidationResponse validate(Long id){scope();return definitions.validate(id);}
    /** 校验指定草稿修订。 */
    public AgentTemplateValidationResponse validate(Long id,long expected){scope();return definitions.validate(id,expected);}
    /** 发布当前草稿并返回原回执。 */
    public AdminAgentTemplateItemResponse publish(Long id,long expected,String key){scope();return definitions.publish(id,expected,key);}
    /** 下架当前源，历史保留。 */
    public AdminAgentTemplateItemResponse deprecate(Long id,long expected,String key){scope();return definitions.deprecate(id,expected,key);}
    /** 历史内容恢复为新草稿，不移动发布指针。 */
    public AdminAgentTemplateItemResponse restore(Long id,long expected,String version,String key){scope();return definitions.restore(id,expected,version,key);}
    /** 保留真实平台账号边界。 */
    private void scope(){definitions.requirePlatformOwner();}
}
