package com.skillforge.institution.group;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 群成员公开读取协议；个人Session读入口不因群SP而放宽。 */
@RestController
@Validated
@RequestMapping(value="/api/v1/open-platform/groups",produces="application/json")
public class GroupHistoryController {
    private static final Set<String> PAGE_PARAMETERS=Set.of("group_binding_id","reader_end_user_id","cursor","page_size");
    private static final Set<String> STREAM_PARAMETERS=Set.of("group_binding_id","reader_end_user_id","cursor");
    private final GroupHistoryService history;

    public GroupHistoryController(GroupHistoryService history){this.history=history;}

    @GetMapping("/history")
    public ApiResponse<GroupHistoryContracts.HistoryPage> history(
            @RequestParam("group_binding_id") @NotBlank @Size(min=1,max=64) String group,
            @Parameter(schema=@Schema(pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19))
            @RequestParam("reader_end_user_id") @NotBlank @Size(min=1,max=19) String reader,
            @RequestParam(value="cursor",required=false) @Size(min=1,max=4096) String cursor,
            @Parameter(schema=@Schema(implementation=Integer.class,types={"integer"},minimum="1",maximum="200",defaultValue="20"))
            @RequestParam(value="page_size",required=false) String pageSize,HttpServletRequest request) {
        strictParameters(request,PAGE_PARAMETERS);
        return ApiResponses.wrap(history.history(group,reader,cursor,GroupHistoryService.parsePageSize(pageSize)));
    }

    @GetMapping("/events")
    public ApiResponse<GroupHistoryContracts.EventsPage> events(
            @RequestParam("group_binding_id") @NotBlank @Size(min=1,max=64) String group,
            @Parameter(schema=@Schema(pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19))
            @RequestParam("reader_end_user_id") @NotBlank @Size(min=1,max=19) String reader,
            @RequestParam(value="cursor",required=false) @Size(min=1,max=4096) String cursor,
            @Parameter(schema=@Schema(implementation=Integer.class,types={"integer"},minimum="1",maximum="200",defaultValue="20"))
            @RequestParam(value="page_size",required=false) String pageSize,HttpServletRequest request) {
        strictParameters(request,PAGE_PARAMETERS);
        return ApiResponses.wrap(history.events(group,reader,cursor,GroupHistoryService.parsePageSize(pageSize)));
    }

    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",useReturnTypeSchema=false,
            content=@io.swagger.v3.oas.annotations.media.Content(mediaType="text/event-stream",schema=@Schema(type="string")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="204",description="下一事件身份不可公开，保留已确认游标并通过 events 读取原因")
    @GetMapping(value="/stream",produces="text/event-stream")
    public ResponseEntity<SseEmitter> stream(
            @RequestParam("group_binding_id") @NotBlank @Size(min=1,max=64) String group,
            @Parameter(schema=@Schema(pattern="[1-9][0-9]{0,18}",minLength=1,maxLength=19))
            @RequestParam("reader_end_user_id") @NotBlank @Size(min=1,max=19) String reader,
            @RequestParam(value="cursor",required=false) @Size(min=1,max=4096) String cursor,
            @RequestHeader(value="Last-Event-ID",required=false) @Size(min=1,max=4096) String lastEventId,
            HttpServletRequest request) {
        strictParameters(request,STREAM_PARAMETERS);
        return history.stream(group,reader,cursor,lastEventId);
    }

    /** 未声明、重复和空值不静默并入默认；page_size只在真正缺失时取默认。 */
    private static void strictParameters(HttpServletRequest request,Set<String> allowed) {
        for(var entry:request.getParameterMap().entrySet())
            if(!allowed.contains(entry.getKey())||entry.getValue()==null||entry.getValue().length!=1)
                throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","查询包含未声明字段或重复参数",false);
    }
}
