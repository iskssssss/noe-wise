package com.skillforge.browser;
import com.skillforge.execution.browser.BrowserRequest;
import com.skillforge.execution.browser.BrowserReceipt;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 命令入口只接受宿主服务凭据；扩展授权与 WSS 另由上游验证。 */
@RestController
@RequestMapping("/internal/browser")
public class BrowserController {
    private final BrowserEngine engine;private final String token;
    public BrowserController(BrowserEngine engine,@Value("${skillforge.browser.service-token}") String token){this.engine=engine;this.token=token;}
    private void authorize(String provided){if(token.isBlank()||!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),Objects.requireNonNullElse(provided,"").getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);}
    @PostMapping("/execute") public BrowserReceipt execute(@RequestHeader(value="X-Browser-Service-Token",required=false)String auth,@RequestBody BrowserRequest request)throws Exception{authorize(auth);return engine.execute(request).publicView();}
    @GetMapping("/devices") public com.skillforge.execution.browser.BrowserDevices devices(@RequestHeader(value="X-Browser-Service-Token",required=false)String auth,@RequestParam String tenant,@RequestParam String user)throws Exception{authorize(auth);return engine.devices(tenant,user);}
    @PostMapping("/pair") public JsonNode pair(@RequestHeader(value="X-Browser-Service-Token",required=false)String auth,@RequestBody DeviceRequest owner)throws Exception{authorize(auth);return engine.device(owner.tenant(),owner.user(),"pair",null,owner.requestId(),owner.connectionRequestId());}
    @GetMapping("/connection-grants/detail") public com.skillforge.execution.browser.BrowserConnectionGrant connectionGrant(@RequestHeader(value="X-Browser-Service-Token",required=false)String auth,@RequestParam String tenant,@RequestParam String user,@RequestParam("request_id")String requestId)throws Exception{authorize(auth);return engine.connectionGrant(tenant,user,requestId);}
    @PostMapping("/revoke") public JsonNode revoke(@RequestHeader(value="X-Browser-Service-Token",required=false)String auth,@RequestBody DeviceRequest owner)throws Exception{authorize(auth);return engine.device(owner.tenant(),owner.user(),"revoke",owner.device(),owner.requestId());}
    @GetMapping("/tasks") public List<BrowserReceipt> tasks(@RequestHeader(value="X-Browser-Service-Token",required=false)String auth,@RequestParam String tenant,@RequestParam String user)throws Exception{authorize(auth);return engine.receipts(tenant,user);}
    @GetMapping("/task-states") public List<BrowserReceipt> taskStates(@RequestHeader(value="X-Browser-Service-Token",required=false)String auth,@RequestParam String tenant,@RequestParam String user)throws Exception{authorize(auth);return engine.taskStates(tenant,user);}
    @PostMapping("/rename") public com.skillforge.execution.browser.BrowserDevices rename(@RequestHeader(value="X-Browser-Service-Token",required=false)String auth,@RequestBody DeviceRequest owner)throws Exception{authorize(auth);return preference(owner,false);}
    @PostMapping("/select") public com.skillforge.execution.browser.BrowserDevices select(@RequestHeader(value="X-Browser-Service-Token",required=false)String auth,@RequestBody DeviceRequest owner)throws Exception{authorize(auth);return preference(owner,true);}
    private com.skillforge.execution.browser.BrowserDevices preference(DeviceRequest owner,boolean select)throws Exception{
        try{return engine.preference(owner.tenant(),owner.user(),owner.device(),select?null:owner.label(),select);}
        catch(IllegalArgumentException rejected){
            if("browser_device_not_owned".equals(rejected.getMessage()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"browser_device_not_owned");
            throw rejected;
        }
    }
    @GetMapping(value="/screenshot",produces="image/png") public byte[] screenshot(@RequestHeader(value="X-Browser-Service-Token",required=false)String auth,@RequestParam String tenant,@RequestParam String user,@RequestParam String invocation)throws Exception{authorize(auth);return engine.screenshot(tenant,user,invocation);}
    @PostMapping("/cancel") public BrowserReceipt cancel(@RequestHeader(value="X-Browser-Service-Token",required=false)String auth,@RequestBody CancelRequest req)throws Exception{authorize(auth);return engine.cancel(req.tenant(),req.user(),req.invocation()).publicView();}
    public record CancelRequest(String tenant,String user,String invocation){}
    public record DeviceRequest(String tenant,String user,String device,String label,@com.fasterxml.jackson.annotation.JsonProperty("request_id")String requestId,@com.fasterxml.jackson.annotation.JsonProperty("connection_request_id")String connectionRequestId){}
}
