package com.skillforge.browser;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** 仅转发官方扩展协议；凭据与授权决定由每用户 daemon 处理。 */
@Configuration
@EnableWebSocket
public class ExtensionProxy implements WebSocketConfigurer {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ExtensionProxy.class);
    // 固定上游 tungstenite 0.24.0 默认接受 64MiB 消息、16MiB 帧；截图 JSON 不受 Tomcat 8KiB 默认限制。
    private static final int MAX_MESSAGE = 64 * 1024 * 1024;
    private final BrowserEngine engine;
    private final HttpClient http=HttpClient.newHttpClient();
    public ExtensionProxy(BrowserEngine engine){this.engine=engine;}
    @Bean public ServletServerContainerFactoryBean extensionWebSocketContainer(){
        var container=new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_MESSAGE);
        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE);
        return container;
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(new Relay(),"/extension/*").setHandshakeHandler(new org.springframework.web.socket.server.support.DefaultHandshakeHandler(){
        @Override protected String selectProtocol(List<String> requested,WebSocketHandler handler){return requested.size()==1&&requested.getFirst().startsWith("bsk-auth.")?requested.getFirst():null;}
    }).setAllowedOrigins("*");}
    final class Relay extends BinaryWebSocketHandler {
        private final Map<String,java.net.http.WebSocket> upstream=new ConcurrentHashMap<>();
        private final java.util.function.Supplier<java.net.http.WebSocket.Builder> connectors;
        Relay(){this(http::newWebSocketBuilder);}
        Relay(java.util.function.Supplier<java.net.http.WebSocket.Builder> connectors){this.connectors=connectors;}
        @Override public void afterConnectionEstablished(WebSocketSession downstream)throws Exception{
            CompletableFuture<java.net.http.WebSocket> handshake=null;
            try{
            String path=Objects.requireNonNull(downstream.getUri()).getPath(),key=path.substring(path.lastIndexOf('/')+1);
            var builder=connectors.get();String protocol=downstream.getHandshakeHeaders().getFirst("Sec-WebSocket-Protocol");
            if(protocol!=null){String[] parts=protocol.split(",\\s*");builder.subprotocols(parts[0],Arrays.copyOfRange(parts,1,parts.length));}
            for(String header:List.of("Origin","Authorization")){String value=downstream.getHandshakeHeaders().getFirst(header);if(value!=null)builder.header(header,value);}
            handshake=builder.buildAsync(URI.create("ws://127.0.0.1:"+engine.proxyPort(key)+path),new java.net.http.WebSocket.Listener(){
                private final StringBuilder text=new StringBuilder();
                @Override public void onOpen(java.net.http.WebSocket socket){socket.request(1);}
                @Override public CompletionStage<?> onText(java.net.http.WebSocket socket,CharSequence data,boolean last){try{synchronized(downstream){if((long)text.length()+data.length()>MAX_MESSAGE)throw new IllegalArgumentException("browser_extension_message_too_large");text.append(data);if(last){downstream.sendMessage(new TextMessage(text.toString()));text.setLength(0);}}socket.request(1);return null;}catch(Exception e){socket.abort();return CompletableFuture.failedFuture(e);}}
                @Override public CompletionStage<?> onBinary(java.net.http.WebSocket socket,ByteBuffer data,boolean last){try{synchronized(downstream){downstream.sendMessage(new BinaryMessage(data,last));}socket.request(1);return null;}catch(Exception e){socket.abort();return CompletableFuture.failedFuture(e);}}
                @Override public CompletionStage<?> onClose(java.net.http.WebSocket socket,int code,String reason){try{downstream.close(new CloseStatus(code));}catch(Exception ignored){}return null;}
                @Override public void onError(java.net.http.WebSocket socket,Throwable error){try{downstream.close(CloseStatus.SERVER_ERROR);}catch(Exception ignored){}}
            });
            java.net.http.WebSocket ws=handshake.get(15,TimeUnit.SECONDS);
            if(!downstream.isOpen()){ws.abort();return;}
            upstream.put(downstream.getId(),ws);
            if(!downstream.isOpen()&&upstream.remove(downstream.getId(),ws))ws.abort();
            }catch(Exception failed){
                if(handshake!=null)handshake.cancel(true);
                if(failed instanceof InterruptedException)Thread.currentThread().interrupt();
                Throwable cause=failed;while(cause.getCause()!=null&&(cause instanceof ExecutionException||cause instanceof CompletionException))cause=cause.getCause();
                CloseStatus status=cause instanceof java.net.http.WebSocketHandshakeException rejected&&rejected.getResponse().statusCode()==401
                        ?CloseStatus.POLICY_VIOLATION:CloseStatus.SERVER_ERROR;
                // 下游已握手不代表 daemon 接受授权；关闭连接，不回显上游异常/凭据，也不重连。
                closeDownstream(downstream,status);
            }
        }
        private void closeDownstream(WebSocketSession downstream,CloseStatus status){try{downstream.close(status);}catch(java.io.IOException ignored){}}
        @Override protected void handleTextMessage(WebSocketSession session,TextMessage message){var socket=upstream.get(session.getId());if(socket==null){closeDownstream(session,CloseStatus.SERVER_ERROR);return;}socket.sendText(message.getPayload(),message.isLast()).join();}
        @Override protected void handleBinaryMessage(WebSocketSession session,BinaryMessage message){var socket=upstream.get(session.getId());if(socket==null){closeDownstream(session,CloseStatus.SERVER_ERROR);return;}socket.sendBinary(message.getPayload(),message.isLast()).join();}
        @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){var ws=upstream.remove(session.getId());if(ws!=null)ws.sendClose(1000,"");}
    }
    @RestController
    public static class AuthorizationRelay {
        private final BrowserEngine engine;private final HttpClient http=HttpClient.newHttpClient();
        public AuthorizationRelay(BrowserEngine engine){this.engine=engine;}
        @jakarta.annotation.PreDestroy public void close(){http.close();}
        @PostMapping("/extension/{key}/authorize") public void authorize(@PathVariable String key,@RequestBody byte[] body,HttpServletRequest source,HttpServletResponse target)throws Exception{
            int port=engine.proxyPort(key);String action="invalid";
            try{var parsed=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);String value=parsed==null?"":parsed.path("action").asText();if(Set.of("pair","renew").contains(value))action=value;}catch(java.io.IOException invalid){/* 协议拒绝仍由官方端点返回。 */}
            String requestId=engine.authorizationExchangeStarted(key,action);
            String connectionRequestId=engine.connectionExchangeStarted(key,action,source.getHeader("Authorization"));
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/extension/"+key+"/authorize")).timeout(java.time.Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofByteArray(body));
            for(String header:List.of("Origin","Authorization","Content-Type")){String value=source.getHeader(header);if(value!=null)request.header(header,value);}
            HttpResponse<byte[]> response;
            try{response=http.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());}
            catch(Exception failure){try{engine.authorizationExchangeFinished(key,requestId,action,null,null);}catch(java.io.IOException auditFailure){failure.addSuppressed(auditFailure);}throw failure;}
            // UNKNOWN 已在转发前落盘；关联失败不能丢弃官方成功响应，扩展仍须保存其凭据。
            try{engine.connectionExchangeFinished(key,connectionRequestId,response.statusCode(),response.body());}
            catch(Exception associationFailure){log.warn("浏览器连接关联未完成 ownerTag={} requestId={} errorCode=browser_connection_grant_unknown",key,connectionRequestId);}
            // 官方成功后token仅在响应到达扩展时保存；结果审计失败不能丢弃该响应，started仍保留。
            try{engine.authorizationExchangeFinished(key,requestId,action,response.statusCode(),response.body());}
            catch(java.io.IOException auditFailure){log.warn("浏览器授权响应审计未完成 ownerTag={} requestId={} errorCode=authorization_audit_unknown",key,requestId);}
            target.setStatus(response.statusCode());
            for(String header:List.of("Content-Type","Retry-After")){response.headers().firstValue(header).ifPresent(v->target.setHeader(header,v));}
            target.setHeader("Cache-Control","no-store");target.getOutputStream().write(response.body());
        }
    }
}
