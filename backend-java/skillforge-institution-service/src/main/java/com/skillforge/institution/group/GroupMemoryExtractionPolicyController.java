package com.skillforge.institution.group;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 固定集成服务主体代办当前群管理员的显式开关；无策略时默认关闭。 */
@Validated
@RestController
@RequestMapping(value="/api/v1/open-platform/groups/memories/extraction-policy",produces="application/json")
public class GroupMemoryExtractionPolicyController {
    private final GroupMemoryExtractionPolicyService service;
    public GroupMemoryExtractionPolicyController(GroupMemoryExtractionPolicyService service) { this.service=service; }

    @GetMapping
    public ApiResponse<GroupMemoryExtractionPolicy.View> get(@RequestParam("group_binding_id") @NotBlank @Size(min=1,max=19) @Pattern(regexp="[1-9][0-9]{0,18}") String group,
            @RequestParam("operator_end_user_id") @NotBlank @Size(min=1,max=19) @Pattern(regexp="[1-9][0-9]{0,18}") String operator) {
        final long member;
        try { member=Long.parseLong(operator); Long.parseLong(group); }
        catch(NumberFormatException invalid) {
            throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"memory_group_id_invalid");
        }
        return ApiResponses.wrap(service.get(group,member));
    }

    @PutMapping
    public ApiResponse<GroupMemoryExtractionPolicy.View> put(@Valid @RequestBody GroupMemoryExtractionPolicy.Command command) {
        return ApiResponses.wrap(service.put(command));
    }
}
