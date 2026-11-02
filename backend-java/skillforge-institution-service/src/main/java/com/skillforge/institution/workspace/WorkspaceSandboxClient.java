package com.skillforge.institution.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.execution.WorkspaceCommand;
import com.skillforge.execution.WorkspaceStatus;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 工作区控制客户端；修改发送后连接失败只报告结果未知，不自动重放。 */
@Component
public final class WorkspaceSandboxClient {
    private static final Logger LOG=LoggerFactory.getLogger(WorkspaceSandboxClient.class);
    private final ObjectMapper json;
    private final String endpoint,token;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** 使用与代码执行相同的受信服务端点和Bearer认证，凭据从不进入结果或日志。 */
    public WorkspaceSandboxClient(ObjectMapper json,@Value("${skillforge.code-execution.endpoint:}") String endpoint,
            @Value("${skillforge.code-execution.service-token:}") String token) {
        LOG.debug("工作区控制客户端装配进入");
        this.json=json;this.endpoint=endpoint;this.token=token;
        LOG.debug("工作区控制客户端装配退出");
    }

    /** 文件修改与检查点请求带可信调用截止时间，取消时停止客户端等待并保持未知事实。 */
    public Map<String,Object> operate(WorkspaceCommand command,PlatformToolExecutionContext context)throws Exception {
        LOG.debug("工作区控制进入 operation={} invocationId={}",command.operation(),context.invocationId());
        var request=builder("/operate",command.deadlineAt()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
        if(context.cancellationSignal().isCancellationRequested())throw new InterruptedException("workspace_cancelled");
        var future=http.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
        try {
            while(true) {
                if(context.cancellationSignal().isCancellationRequested()||!Instant.now().isBefore(command.deadlineAt()))
                    throw new InterruptedException("workspace_cancelled");
                try {
                    var response=future.get(100,TimeUnit.MILLISECONDS);
                    try(var input=response.body()) {
                        byte[] bytes=read(input,command.deadlineAt());
                        if(response.statusCode()>=400&&response.statusCode()<500) {
                            // 完整4xx回执证明本次业务拒绝，修订冲突必须可供模型纠正而非unknown。
                            var rejection=json.readTree(bytes);
                            String reason=java.util.stream.Stream.of("error","code","message")
                                    .map(field->rejection.path(field).asText(""))
                                    .filter(value->value.matches("(?:workspace_[a-z0-9_]{1,80}|WORKSPACE_[A-Z0-9_]{1,80})"))
                                    .findFirst().orElse("workspace_request_rejected");
                            return Map.of("success",false,"error",reason);
                        }
                        if(response.statusCode()<200||response.statusCode()>=300)throw new IOException("workspace_control_rejected");
                        return json.readValue(bytes,new TypeReference<Map<String,Object>>(){});
                    }
                }catch(TimeoutException pending){/* 等待阶段持续检查调用取消与deadline。 */}
            }
        }catch(Exception unknown) {
            if(unknown instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new ExternalInvocationGuard.OutcomeUnknownException("workspace_outcome_unknown",null);
        }finally {
            if(!future.isDone())future.cancel(true);
            else if(!future.isCompletedExceptionally()) {
                var response=future.getNow(null);if(response!=null)try{response.body().close();}catch(IOException ignored){/* 已保留未知结果。 */}
            }
            LOG.debug("工作区控制退出 operation={} invocationId={}",command.operation(),context.invocationId());
        }
    }

    /** 状态只读不创建工作区；未创建目录用明确未初始化投影表示。 */
    public WorkspaceStatus status(String workspace)throws Exception {
        LOG.debug("工作区状态读取进入");
        try {
            var response=http.send(builder("/status?workspaceId="+workspace,Instant.now().plusSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var input=response.body()) {
                if(response.statusCode()==404)return new WorkspaceStatus(workspace,0,0,false,"uninitialized",null,null,java.util.List.of());
                byte[] bytes=read(input,Instant.now().plusSeconds(10));
                if(response.statusCode()!=200||bytes.length>ExecutionLimits.CONTROL_RESPONSE_BYTES)throw new IOException("workspace_status_unavailable");
                return json.readValue(bytes,WorkspaceStatus.class);
            }
        }finally{LOG.debug("工作区状态读取退出");}
    }

    /** 清理请求由持久意图驱动；Sandbox先撤销写权、确认进程静止再执行幂等删除。 */
    public boolean delete(String workspace)throws Exception {
        LOG.debug("工作区清理控制进入");
        try {
            var response=http.send(builder("/delete",Instant.now().plusSeconds(30))
                    .method("DELETE",HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("workspaceId",workspace)))).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try(var input=response.body()) {
                byte[] bytes=read(input,Instant.now().plusSeconds(30));
                if(response.statusCode()==204)return true;
                if(response.statusCode()!=200||bytes.length>ExecutionLimits.CONTROL_RESPONSE_BYTES)return false;
                return Boolean.TRUE.equals(json.readValue(bytes,new TypeReference<Map<String,Object>>(){}).get("success"));
            }
        }finally{LOG.debug("工作区清理控制退出");}
    }

    /** 固定内部路径；模型从不参与端点、认证或身份构造。 */
    private HttpRequest.Builder builder(String suffix,Instant deadline) {
        LOG.debug("工作区控制请求构造进入");
        try {
        var uri=URI.create(endpoint);
        if(token.length()<32||!java.util.Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null
                ||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new IllegalStateException("workspace_deployment_unavailable");
        var duration=Duration.between(Instant.now(),deadline);
        if(duration.isNegative()||duration.isZero())throw new IllegalStateException("workspace_deadline_elapsed");
        return HttpRequest.newBuilder(URI.create(endpoint+"/internal/workspaces"+suffix)).timeout(duration)
                .header("Authorization","Bearer "+token).header("Content-Type","application/json");
        }finally{LOG.debug("工作区控制请求构造退出");}
    }

    /** response headers并不保证body已到齐；deadline关闭流防止无限等待占用调用线程。 */
    private static byte[] read(java.io.InputStream input,Instant deadline)throws IOException {
        LOG.debug("工作区响应读取进入");
        try(var timer=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().name("workspace-response-deadline").daemon().factory())) {
            var timeout=timer.schedule(()->{try{input.close();}catch(IOException ignored){/* 读取会保留超时事实。 */}},
                    Math.max(1,Duration.between(Instant.now(),deadline).toMillis()),TimeUnit.MILLISECONDS);
            try {
                byte[] bytes=input.readNBytes(ExecutionLimits.CONTROL_RESPONSE_BYTES+1);
                if(bytes.length>ExecutionLimits.CONTROL_RESPONSE_BYTES)throw new IOException("workspace_response_too_large");
                return bytes;
            }finally{timeout.cancel(false);}
        }finally{LOG.debug("工作区响应读取退出");}
    }
}
