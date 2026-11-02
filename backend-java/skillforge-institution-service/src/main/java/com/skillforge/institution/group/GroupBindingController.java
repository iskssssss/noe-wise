package com.skillforge.institution.group;

import static com.skillforge.institution.group.GroupContracts.*;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 固定机构ServicePrincipal的群治理入口；本领域不接收聊天或触发执行。 */
@Validated
@RestController
@RequestMapping(value="/api/v1/open-platform/groups",produces="application/json")
public class GroupBindingController {
    private final GroupBindingService groups;
    public GroupBindingController(GroupBindingService groups){this.groups=groups;}
    @PostMapping("/ensure")
    public ApiResponse<GroupView> ensure(@Valid @RequestBody Ensure command,@RequestHeader("Idempotency-Key")String key){return ApiResponses.wrap(groups.ensure(command,key));}
    @PostMapping("/close")
    public ApiResponse<GroupView> close(@Valid @RequestBody Close command,@RequestHeader("Idempotency-Key")String key){return ApiResponses.wrap(groups.close(command,key));}
    @GetMapping("/detail")
    public ApiResponse<GroupView> detail(@RequestParam("group_binding_id") @NotBlank @Size(min=1,max=64) String id){return ApiResponses.wrap(groups.detail(id));}
    @GetMapping("/members")
    public ApiResponse<MembersPage> members(
            @RequestParam("group_binding_id") @NotBlank @Size(min=1,max=64) String group,
            @RequestParam(value="after_id",required=false) @Size(max=19) String after,
            @RequestParam(value="limit",defaultValue="100") @Min(1) @Max(MAX_DIRECTORY_PAGE_SIZE) int limit) {
        return ApiResponses.wrap(groups.members(group,after,limit));
    }
    @GetMapping("/agents")
    public ApiResponse<AgentsPage> agents(
            @RequestParam("group_binding_id") @NotBlank @Size(min=1,max=64) String group,
            @RequestParam(value="after_id",required=false) @Size(max=19) String after,
            @RequestParam(value="limit",defaultValue="100") @Min(1) @Max(MAX_DIRECTORY_PAGE_SIZE) int limit) {
        return ApiResponses.wrap(groups.agents(group,after,limit));
    }
    @PostMapping("/members/update")
    public ApiResponse<MemberView> member(@Valid @RequestBody UpdateMember command,@RequestHeader("Idempotency-Key")String key){return ApiResponses.wrap(groups.updateMember(command,key));}
    @PostMapping("/agents/update")
    public ApiResponse<AgentView> agent(@Valid @RequestBody UpdateAgent command,@RequestHeader("Idempotency-Key")String key){return ApiResponses.wrap(groups.updateAgent(command,key));}
}
