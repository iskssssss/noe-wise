package com.skillforge.institution.group;

import static com.skillforge.institution.group.GroupControlContracts.*;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/** 固定SP群控制入口；HTTP拒绝在服务事务结束后映射，保留未受理的持久证据。 */
@Validated @RestController @RequestMapping(value="/api/v1/open-platform/groups",produces="application/json")
public class GroupControlController {
    private final GroupControlService controls;
    private final GroupDeliveryService deliveries;
    private final GroupSenderService senders;
    public GroupControlController(GroupControlService controls,GroupDeliveryService deliveries,GroupSenderService senders){this.controls=controls;this.deliveries=deliveries;this.senders=senders;}
    @PostMapping("/controls") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Receipt> command(@Valid @RequestBody Command command,@RequestHeader("Idempotency-Key")String key) {
        var result=controls.command(command,key);
        if(!GroupControlTypes.Result.ACCEPTED.wire.equals(result.resultKind()))throw new ApiException(HttpStatus.CONFLICT,"CONFLICT","原命令已持久拒绝或终结为未受理，请读回控制回执后对账",false);
        return ApiResponses.wrap(result);
    }
    @GetMapping("/controls/detail")
    public ApiResponse<Receipt> detail(@RequestParam("group_binding_id") @NotBlank @Size(min=1,max=64)String group,@RequestParam("command_id") @NotBlank @Size(min=1,max=128)String command,HttpServletRequest request){query(request,Set.of("group_binding_id","command_id"));return ApiResponses.wrap(controls.detail(group,command));}
    @GetMapping("/controls/current")
    public ApiResponse<Current> current(@RequestParam("group_binding_id") @NotBlank @Size(min=1,max=64)String group,HttpServletRequest request){query(request,Set.of("group_binding_id"));return ApiResponses.wrap(controls.current(group));}
    @PostMapping("/controls/authorize")
    public ApiResponse<Authorization> authorize(@Valid @RequestBody Authorize command){return ApiResponses.wrap(controls.authorize(command));}
    @PostMapping("/controls/terminalize")
    public ApiResponse<Receipt> terminalize(@Valid @RequestBody Terminalize command,@RequestHeader("Idempotency-Key")String key){return ApiResponses.wrap(controls.terminalize(command,key));}
    @PostMapping("/controls/release")
    public ApiResponse<ReleaseReceipt> release(@Valid @RequestBody Release command,@RequestHeader("Idempotency-Key")String key){return ApiResponses.wrap(deliveries.release(command,key));}
    @PostMapping("/sender-endpoints/register")
    public ApiResponse<GroupSenderContracts.Registration> register(@Valid @RequestBody GroupSenderContracts.Register command,@RequestHeader("Idempotency-Key")String key){return ApiResponses.wrap(senders.register(command,key));}
    @PostMapping("/sender-endpoints/retire")
    public ApiResponse<GroupSenderContracts.Endpoint> retire(@Valid @RequestBody GroupSenderContracts.Retire command,@RequestHeader("Idempotency-Key")String key){return ApiResponses.wrap(senders.retire(command,key));}
    @PostMapping("/sender-endpoints/ack")
    public ApiResponse<DurableAck> ack(@Valid @RequestBody GroupSenderContracts.Ack command,@RequestHeader("Idempotency-Key")String key){return ApiResponses.wrap(senders.ack(command,key));}
    @PostMapping("/deliveries/qualify")
    public ApiResponse<GroupDeliveryContracts.Qualification> qualify(@Valid @RequestBody GroupDeliveryContracts.Qualify command){return ApiResponses.wrap(deliveries.qualify(command));}
    /** 新公开GET严格拒绝未声明或重复参数，不能借额外字段改变对象边界。 */
    private static void query(HttpServletRequest request,Set<String> allowed){if(request.getParameterMap().entrySet().stream().anyMatch(entry->!allowed.contains(entry.getKey())||entry.getValue()==null||entry.getValue().length!=1))throw GroupControlService.bad("查询包含未声明或重复参数");}
}
