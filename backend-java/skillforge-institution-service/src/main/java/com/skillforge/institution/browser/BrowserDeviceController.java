package com.skillforge.institution.browser;
import com.skillforge.common.context.*;
import com.skillforge.institution.chat.ChatClientProfileMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.execution.browser.BrowserDevices;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

/** 复用渠道认证与已登记个人身份，设备管理不授予工具挂载。 */
@RestController
@RequestMapping("/api/v1/chat/browser")
public class BrowserDeviceController {
    private final ChatClientProfileMapper profiles;private final BrowserClient client;private final com.skillforge.institution.media.SessionMediaService media;
    private final BrowserDeviceAuditService authorizationAudit;
    private final BrowserConnectionService connections;
    private final BrowserTaskAccess taskAccess;
    private final ArtifactAccess access;private final SessionService sessions;private final com.skillforge.institution.runtime.AgentRunBindings bindings;
    public BrowserDeviceController(ChatClientProfileMapper profiles,BrowserClient client,com.skillforge.institution.media.SessionMediaService media,ArtifactAccess access,SessionService sessions,com.skillforge.institution.runtime.AgentRunBindings bindings,BrowserDeviceAuditService authorizationAudit,BrowserConnectionService connections,BrowserTaskAccess taskAccess){this.taskAccess=taskAccess;this.profiles=profiles;this.client=client;this.media=media;this.access=access;this.sessions=sessions;this.bindings=bindings;this.authorizationAudit=authorizationAudit;this.connections=connections;}
    private record Owner(String tenant,String user){}
    private Owner owner(String ref,String session){String tenant=TenantContext.getTenantId(),channel=ChannelContext.getChannelId();
        if((ref==null)==(session==null))throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"client_ref 与 session_id 必须提供且只能提供一项");
        if(tenant==null)throw new ApiException(HttpStatus.UNAUTHORIZED,"请先完成登录认证");
        if(channel==null){
            if(session==null||session.isBlank())throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"机构端须选择已授权会话");
            access.authorizeCurrent(tenant,session);var row=sessions.require(tenant,session);return new Owner(tenant,row.getEndUserId().toString());
        }
        if(ref==null)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"渠道端须提供已登记个人身份");
        String id=UUID.fromString(ref).toString();
        if(!profiles.channelExists(Long.parseLong(tenant),Long.parseLong(channel)))throw new ApiException(HttpStatus.FORBIDDEN,"渠道已不可用");
        Long user=profiles.find(Long.parseLong(tenant),Long.parseLong(channel),id);
        if(user==null)throw new ApiException(HttpStatus.FORBIDDEN,"请先登记当前个人身份");return new Owner(tenant,user.toString());}
    @Operation(summary="查询个人浏览器设备") @GetMapping("/devices") public ApiResponse<BrowserDevices> devices(@RequestParam(value="client_ref",required=false)String ref,@RequestParam(value="session_id",required=false)String session)throws Exception{var o=owner(ref,session);return ApiResponses.wrap(client.devices(o.tenant(),o.user()));}
    @Operation(summary="生成个人浏览器配对链接") @PostMapping("/pair") public ApiResponse<JsonNode> pair(@RequestBody DeviceCommand req)throws Exception{var o=owner(req.clientRef(),req.sessionId());return ApiResponses.wrap(req.connectionRequestId()==null?authorizationAudit.pair(o.tenant(),o.user()):connections.pair(o.tenant(),o.user(),req.connectionRequestId(),req.sessionId(),Boolean.TRUE.equals(req.makeDefault())));}
    @Operation(summary="撤销指定个人设备授权") @PostMapping("/revoke") public ApiResponse<JsonNode> revoke(@RequestBody DeviceCommand req)throws Exception{var o=owner(req.clientRef(),req.sessionId());return ApiResponses.wrap(authorizationAudit.revoke(o.tenant(),o.user(),Objects.requireNonNull(req.deviceId())));}
    @Operation(summary="修改个人设备显示名") @PostMapping("/rename") public ApiResponse<BrowserDevices> rename(@RequestBody DeviceCommand req)throws Exception{var o=owner(req.clientRef(),req.sessionId());return ApiResponses.wrap(client.preference(o.tenant(),o.user(),req.deviceId(),req.label(),false));}
    @Operation(summary="选择本次连接或默认个人浏览器") @PostMapping("/select") public ApiResponse<BrowserDevices> select(@RequestBody DeviceCommand req)throws Exception{var o=owner(req.clientRef(),req.sessionId());return ApiResponses.wrap(req.connectionRequestId()==null?client.preference(o.tenant(),o.user(),req.deviceId(),null,true):connections.select(o.tenant(),o.user(),req.connectionRequestId(),req.sessionId(),req.deviceId(),Boolean.TRUE.equals(req.makeDefault())));}
    /** 连接卡只读查询不签发凭据，也不以查询代替恢复输入。 */
    @Operation(summary="查询个人浏览器连接请求") @GetMapping("/connection-requests/detail") public ApiResponse<BrowserConnectionView> connection(
            @RequestParam("request_id")String request,@RequestParam(value="client_ref",required=false)String ref,
            @RequestParam(value="session_id",required=false)String session)throws Exception{
        var o=owner(ref,session);return ApiResponses.wrap(connections.view(o.tenant(),o.user(),request,session));
    }
    /** 取消只作用于准确连接等待；已建立的独立设备授权保留。 */
    @Operation(summary="取消浏览器连接等待") @PostMapping("/connection-requests/cancel") public ApiResponse<Void> cancelConnection(@RequestBody ConnectionCommand req){
        var o=owner(req.clientRef(),req.sessionId());connections.cancel(o.tenant(),o.user(),req.connectionRequestId(),req.sessionId());return ApiResponses.wrap((Void)null);
    }
    @Operation(summary="查询个人浏览器任务及回执") @GetMapping("/tasks") public ApiResponse<List<BrowserTaskView>> tasks(@RequestParam(value="client_ref",required=false)String ref,@RequestParam(value="session_id",required=false)String session)throws Exception{
        var o=owner(ref,session);var items=new ArrayList<BrowserTaskView>();
        for(var receipt:client.tasks(o.tenant(),o.user())){
            if(!taskAccess.readableCurrent(o.tenant(),o.user(),receipt,session))continue;
            List<MediaView> content=List.of();
            if("screenshot".equals(receipt.operation())&&"succeeded".equals(receipt.status())){
                var owner=new com.skillforge.runtime.api.SessionRef(new com.skillforge.runtime.api.TenantId(o.tenant()),receipt.session());
                var run=new com.skillforge.runtime.api.RunRef(owner.tenantId(),receipt.run());
                var binding=bindings.find(run).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"浏览器调用来源已不可用"));
                if(!binding.owner().sessionRef().equals(owner)||!Long.toString(binding.owner().requireUser().value()).equals(o.user()))throw new ApiException(HttpStatus.FORBIDDEN,"浏览器调用归属不一致");
                var existing=media.findRegistered(owner,"browser:"+receipt.invocation());
                var image=existing.isPresent()?existing.get():media.registerForRun(owner,run,"browser:"+receipt.invocation(),"浏览器截图",client.screenshot(o.tenant(),o.user(),receipt.invocation()));
                content=List.of(new MediaView(binding.scope().origin().sessionRef().sessionId(),image.id(),image.mimeType(),image.byteLength(),image.width(),image.height()));
            }
            items.add(new BrowserTaskView(receipt,content));
        }
        return ApiResponses.wrap(List.copyOf(items));
    }
    @Operation(summary="取消本人原始浏览器任务并返回清理回执") @PostMapping("/cancel") public ApiResponse<com.skillforge.execution.browser.BrowserReceipt> cancel(@RequestBody CancelCommand req)throws Exception{var o=owner(req.clientRef(),req.sessionId());var receipt=client.tasks(o.tenant(),o.user()).stream().filter(r->r.invocation().equals(req.invocation())).findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"浏览器任务不存在"));if(!taskAccess.readableCurrent(o.tenant(),o.user(),receipt,req.sessionId()))throw new ApiException(HttpStatus.FORBIDDEN,"浏览器任务来源不可读");return ApiResponses.wrap(client.cancel(o.tenant(),o.user(),req.invocation()));}
    public record CancelCommand(@com.fasterxml.jackson.annotation.JsonProperty("client_ref")String clientRef,@com.fasterxml.jackson.annotation.JsonProperty("session_id")String sessionId,String invocation){}
    public record BrowserTaskView(com.skillforge.execution.browser.BrowserReceipt receipt,List<MediaView> content){}
    public record MediaView(@com.fasterxml.jackson.annotation.JsonProperty("session_id")String sessionId,
        @com.fasterxml.jackson.annotation.JsonProperty("media_id")String mediaId,@com.fasterxml.jackson.annotation.JsonProperty("mime_type")String mimeType,
        @com.fasterxml.jackson.annotation.JsonProperty("byte_length")long byteLength,int width,int height){}
    public record DeviceCommand(@com.fasterxml.jackson.annotation.JsonProperty("client_ref")String clientRef,
        @com.fasterxml.jackson.annotation.JsonProperty("session_id")String sessionId,
        @com.fasterxml.jackson.annotation.JsonProperty("device_id")String deviceId,String label,
        @com.fasterxml.jackson.annotation.JsonProperty("connection_request_id")String connectionRequestId,
        @com.fasterxml.jackson.annotation.JsonProperty("make_default")Boolean makeDefault){}
    public record ConnectionCommand(@com.fasterxml.jackson.annotation.JsonProperty("client_ref")String clientRef,
        @com.fasterxml.jackson.annotation.JsonProperty("session_id")String sessionId,
        @com.fasterxml.jackson.annotation.JsonProperty("connection_request_id")String connectionRequestId){}
}
