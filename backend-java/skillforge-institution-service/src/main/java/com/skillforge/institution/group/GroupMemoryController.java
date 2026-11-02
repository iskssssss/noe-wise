package com.skillforge.institution.group;

import static com.skillforge.institution.group.GroupMemoryContracts.*;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 固定集成身份代办当前真实成员的群 Memory 读写入口。 */
@Validated @RestController
@RequestMapping(value="/api/v1/open-platform/groups/memories",produces="application/json")
public class GroupMemoryController {
    private final GroupMemoryService service;
    public GroupMemoryController(GroupMemoryService service) {this.service=service;}
    @GetMapping
    public ApiResponse<List<FactView>> list(@RequestParam("group_binding_id") @NotBlank String group,
            @RequestParam("reader_end_user_id") @NotBlank String reader,
            @RequestParam("agent_id") @NotBlank String agent,
            @RequestParam(value="subject_end_user_id",required=false) String subject,
            @RequestParam(required=false) String scope,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="100") @Min(1) @Max(200) int limit) {
        return ApiResponses.wrap(service.list(group,reader,agent,subject,scope,status,limit).stream().map(FactView::from).toList());
    }
    @PostMapping("/create") public ApiResponse<FactView> create(@Valid @RequestBody Create command,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponses.wrap(FactView.from(service.create(command,key)));
    }
    @PostMapping("/review") public ApiResponse<FactView> review(@Valid @RequestBody Review command,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponses.wrap(FactView.from(service.review(command,key)));
    }
    @PostMapping("/correct") public ApiResponse<FactView> correct(@Valid @RequestBody Correct command,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponses.wrap(FactView.from(service.correct(command,key)));
    }
    @PostMapping("/forget") public ApiResponse<FactView> forget(@Valid @RequestBody Revision command,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponses.wrap(FactView.from(service.forget(command,key)));
    }
    @PostMapping("/restore") public ApiResponse<FactView> restore(@Valid @RequestBody Revision command,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponses.wrap(FactView.from(service.restore(command,key)));
    }
}
