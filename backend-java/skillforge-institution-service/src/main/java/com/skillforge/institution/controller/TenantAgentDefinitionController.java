package com.skillforge.institution.controller;

import com.skillforge.core.agent.AgentDefinitionLifecycleService;
import com.skillforge.core.agent.dto.query.AgentTemplateQuery;
import com.skillforge.core.agent.dto.request.AgentDefinitionCommands.*;
import com.skillforge.core.agent.dto.request.CreateAgentTemplateRequest;
import com.skillforge.core.agent.dto.response.*;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.service.TenantAgentDefinitionImpactService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 当前机构共享源维护入口；接收人员JWT或精确机构管理scope，不接受正文所有权。 */
@RestController
@RequestMapping("/api/v1/tenant/agent-definitions")
public class TenantAgentDefinitionController {
    private final AgentDefinitionLifecycleService definitions;
    private final TenantAgentDefinitionImpactService impacts;
    /** 共享唯一领域服务，机构预览单独约束目标读取窗口。 */
    public TenantAgentDefinitionController(AgentDefinitionLifecycleService definitions,TenantAgentDefinitionImpactService impacts){this.definitions=definitions;this.impacts=impacts;}
    /** 当前机构自有共享源，不包含受让草稿。 */
    @GetMapping public ApiResponse<AdminAgentTemplateListResponse> list(@ModelAttribute AgentTemplateQuery query){scope();return ApiResponses.wrap(definitions.list(query));}
    /** 自有源工作草稿。 */
    @GetMapping("/detail") public ApiResponse<AdminAgentTemplateDetailResponse> detail(@RequestParam("definition_id")Long id){scope();return ApiResponses.wrap(definitions.detail(id));}
    /** 自有源不可变发布历史。 */
    @GetMapping("/history") public ApiResponse<List<AdminAgentTemplateDetailResponse>> history(@RequestParam("definition_id")Long id){scope();return ApiResponses.wrap(definitions.history(id));}
    /** 精确草稿的声明差量和明确可见实例结构。 */
    @GetMapping("/impact") public ApiResponse<AgentDefinitionImpactResponse> impact(@RequestParam("definition_id")Long id,@RequestParam("draft_revision")long revision){scope();return ApiResponses.wrap(impacts.impact(id,revision));}
    /** 当前编辑内容只读预览；不保存也不发布。 */
    @PostMapping("/preview-content") public ApiResponse<TenantAgentDefinitionImpactService.CandidatePreview> previewContent(@Valid @RequestBody PreviewContent request){scope();return ApiResponses.wrap(impacts.preview(request.definitionId(),request.expectedRevision(),request.definition()));}
    @PostMapping("/preview") public ApiResponse<TenantAgentDefinitionImpactService.CandidatePreview> preview(@Valid @RequestBody DefinitionCommand request){scope();return ApiResponses.wrap(impacts.previewSaved(request.definitionId(),request.expectedRevision()));}
    /** 仅创建独立共享定义，不从普通Agent发布自动复制。 */
    @PostMapping public ApiResponse<AdminAgentTemplateItemResponse> create(@Valid @RequestBody CreateAgentTemplateRequest request,@RequestHeader("Idempotency-Key")String key){scope();return ApiResponses.wrap(definitions.create(request,key));}
    /** 精确修订编辑。 */
    @PutMapping("/update") public ApiResponse<AdminAgentTemplateItemResponse> update(@Valid @RequestBody UpdateDefinition request,@RequestHeader("Idempotency-Key")String key){scope();return ApiResponses.wrap(definitions.update(request.definitionId(),request.expectedRevision(),request.payload(),key));}
    /** 只校验指定修订。 */
    @PostMapping("/validate") public ApiResponse<AgentTemplateValidationResponse> validate(@Valid @RequestBody DefinitionCommand request){scope();return ApiResponses.wrap(definitions.validate(request.definitionId(),request.expectedRevision()));}
    /** 共享不可变发布，使用权仍由独立grant产生。 */
    @PostMapping("/publish") public ApiResponse<AdminAgentTemplateItemResponse> publish(@Valid @RequestBody DefinitionCommand request,@RequestHeader("Idempotency-Key")String key){scope();return ApiResponses.wrap(definitions.publish(request.definitionId(),request.expectedRevision(),key));}
    /** 下架不删除历史和已发生费用。 */
    @PostMapping("/deprecate") public ApiResponse<AdminAgentTemplateItemResponse> deprecate(@Valid @RequestBody DefinitionCommand request,@RequestHeader("Idempotency-Key")String key){scope();return ApiResponses.wrap(definitions.deprecate(request.definitionId(),request.expectedRevision(),key));}
    /** 历史恢复为新草稿，保留当前发布指针。 */
    @PostMapping("/restore") public ApiResponse<AdminAgentTemplateItemResponse> restore(@Valid @RequestBody RestoreDefinition request,@RequestHeader("Idempotency-Key")String key){scope();return ApiResponses.wrap(definitions.restore(request.definitionId(),request.expectedRevision(),request.publishedRevision(),key));}
    /** 固定当前机构域；每个业务方法内部独立核验精确动作。 */
    private void scope(){definitions.requireTenantOwner();}
}
