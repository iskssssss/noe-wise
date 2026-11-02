package com.skillforge.institution.browser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.browser.BrowserRequest;
import com.skillforge.execution.browser.BrowserReceipt;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Browser service 的内部认证客户端；文件正文使用独立二进制请求。 */
@Component
public class BrowserClient {
    private final String base,token;private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public BrowserClient(@Value("${BROWSER_SERVICE_URL:http://127.0.0.1:8300}")String base,
            @Value("${BROWSER_SERVICE_TOKEN:}")String token,ObjectMapper json){this.base=base.replaceAll("/$","");this.token=token;this.json=json;}
    private byte[] request(String method,String path,Object body)throws Exception {
        if(token.isBlank())throw new com.skillforge.core.shared.exception.ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"浏览器服务尚未配置，请联系管理员");
        var req=HttpRequest.newBuilder(URI.create(base+"/internal/browser"+path)).timeout(body instanceof BrowserRequest browser?Duration.between(java.time.Instant.now(),browser.deadlineAt()).isNegative()?Duration.ofMillis(1):Duration.between(java.time.Instant.now(),browser.deadlineAt()).plusSeconds(2):Duration.ofSeconds(180))
                .header("X-Browser-Service-Token",token).header("Content-Type","application/json");
        req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        var reply=http.send(req.build(),HttpResponse.BodyHandlers.ofByteArray());
        // 仅设备偏好入口的稳定归属拒绝是业务403；服务认证与未知故障不改写。
        if(reply.statusCode()==403&&Set.of("/select","/rename").contains(path))
            throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"浏览器设备不属于当前用户");
        if(reply.statusCode()/100!=2)throw new java.io.IOException("browser_service_http_"+reply.statusCode());return reply.body();
    }
    public BrowserReceipt execute(BrowserRequest req)throws Exception{return json.readValue(request("POST","/execute",req),BrowserReceipt.class);}
    private static String query(String tenant,String user){return "?tenant="+encode(tenant)+"&user="+encode(user);}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    public com.skillforge.execution.browser.BrowserDevices devices(String tenant,String user)throws Exception{return json.readValue(request("GET","/devices"+query(tenant,user),null),com.skillforge.execution.browser.BrowserDevices.class);}
    public JsonNode pair(String tenant,String user,String requestId)throws Exception{return pair(tenant,user,requestId,null);}
    /** 连接等待的关联身份与审计 request_id 分开，配对串不持久化于机构服务。 */
    public JsonNode pair(String tenant,String user,String requestId,String connectionRequestId)throws Exception{return json.readTree(request("POST","/pair",new DeviceRequest(tenant,user,null,null,requestId,connectionRequestId)));}
    /** 只读取当前个人的准确配对结果，不以设备列表差量推断。 */
    public com.skillforge.execution.browser.BrowserConnectionGrant connectionGrant(String tenant,String user,String requestId)throws Exception{
        return json.readValue(request("GET","/connection-grants/detail"+query(tenant,user)+"&request_id="+encode(requestId),null),com.skillforge.execution.browser.BrowserConnectionGrant.class);
    }
    public JsonNode revoke(String tenant,String user,String device,String requestId)throws Exception{return json.readTree(request("POST","/revoke",new DeviceRequest(tenant,user,device,null,requestId)));}
    public com.skillforge.execution.browser.BrowserDevices preference(String tenant,String user,String device,String label,boolean select)throws Exception{return json.readValue(request("POST",select?"/select":"/rename",new DeviceRequest(tenant,user,device,label,null)),com.skillforge.execution.browser.BrowserDevices.class);}
    public List<BrowserReceipt> tasks(String tenant,String user)throws Exception{return json.readValue(request("GET","/tasks"+query(tenant,user),null),new TypeReference<List<BrowserReceipt>>(){});}
    public List<BrowserReceipt> taskStates(String tenant,String user)throws Exception{return json.readValue(request("GET","/task-states"+query(tenant,user),null),new TypeReference<List<BrowserReceipt>>(){});}
    public byte[] screenshot(String tenant,String user,String invocation)throws Exception{return request("GET","/screenshot"+query(tenant,user)+"&invocation="+encode(invocation),null);}
    public BrowserReceipt cancel(String tenant,String user,String invocation)throws Exception{return json.readValue(request("POST","/cancel",new CancelRequest(tenant,user,invocation)),BrowserReceipt.class);}
    private record CancelRequest(String tenant,String user,String invocation){}
    private record DeviceRequest(String tenant,String user,String device,String label,@com.fasterxml.jackson.annotation.JsonProperty("request_id")String requestId,
            @com.fasterxml.jackson.annotation.JsonProperty("connection_request_id")String connectionRequestId){
        DeviceRequest(String tenant,String user,String device,String label,String requestId){this(tenant,user,device,label,requestId,null);}
    }
}
