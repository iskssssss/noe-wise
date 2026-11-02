package com.skillforge.institution.artifact;

import com.sun.net.httpserver.HttpExchange;
import com.skillforge.common.context.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 独立监听器仅提供票据约束的静态资源，不注册MVC路由、登录Cookie或平台CORS。 */
@Component
public class ArtifactPreviewServer {
    private final ArtifactAccess access; private final ArtifactStorage storage;
    private final DeliverableAccess deliverables;
    private final int port;private final String origin;private final String platform;
    private HttpServer server;private ExecutorService workers;
    public ArtifactPreviewServer(ArtifactAccess access,ArtifactStorage storage,DeliverableAccess deliverables,
            @Value("${skillforge.code-execution.preview-port:0}") int port,
            @Value("${skillforge.code-execution.preview-origin:}") String origin,
            @Value("${skillforge.code-execution.platform-origin:}") String platform,
            @Value("${skillforge.code-execution.preview-frame-origins:}") String frameOrigins) {
        this.access=access;this.storage=storage;this.deliverables=deliverables;this.port=port;this.origin=origin;this.platform=frameAncestors(platform,frameOrigins);
    }
    /** 只接受明确 HTTP(S) origin，禁止通配符和 CSP 片段；生产不隐式信任开发端口。 */
    static String frameAncestors(String platform,String configured) {
        var origins=new java.util.LinkedHashSet<String>();
        if(platform!=null&&!platform.isBlank())origins.add(ArtifactAccess.origin(platform).toString());
        if(configured!=null&&!configured.isBlank()) {
            for(String item:configured.split(","))origins.add(ArtifactAccess.origin(item.trim()).toString());
        }
        return origins.isEmpty()?"'none'":String.join(" ",origins);
    }
    @PostConstruct public void start()throws IOException {
        if(port==0)return;access.requireConfiguration();
        server=HttpServer.create(new InetSocketAddress("0.0.0.0",port),0);
        workers=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(workers);
        server.createContext("/p/",this::serve);server.createContext("/d/",this::serveDeliverable);server.start();
    }
    void serve(HttpExchange exchange)throws IOException { serveResource(exchange,false); }
    void serveDeliverable(HttpExchange exchange)throws IOException { serveResource(exchange,true); }
    private void serveResource(HttpExchange exchange,boolean published)throws IOException {
        String previous=TenantContext.getTenantId();
        try {
            if(!"GET".equals(exchange.getRequestMethod())||exchange.getRequestHeaders().containsKey("Cookie")
                    ||exchange.getRequestHeaders().containsKey("Authorization"))throw ArtifactStorage.unavailable();
            String path=exchange.getRequestURI().getPath().substring(3);int slash=path.indexOf('/');
            if(slash<1)throw ArtifactStorage.unavailable();
            String ticket=path.substring(0,slash);String file=ArtifactStorage.safePath(path.substring(slash+1));
            ArtifactRow row;String storedPath;boolean single;
            if(published) {
                var granted=deliverables.verify(ticket,file,exchange.getRemoteAddress().getAddress().getHostAddress());
                TenantContext.setTenantId(granted.claim().tenant());
                row=granted.file().artifact();storedPath=granted.file().path();single=deliverables.fileCount(granted)==1;
            } else {
                var granted=access.verify(ticket,"preview");
                TenantContext.setTenantId(granted.claim().tenant());row=granted.row();storedPath=file;
                if(!"website".equals(row.type()))throw ArtifactStorage.unavailable();
                single=storage.metadata(row).files().size()==1;
            }
            String local=origin+(published?"/d/":"/p/")+ticket+"/";
            var frames="'none'".equals(platform)?List.<String>of():List.of(platform.split(" "));
            String html="";
            if(mime(file).startsWith("text/html")) {
                byte[] content=storage.verifiedFile(row,storedPath,12*1024*1024).content();
                if(content==null)throw ArtifactStorage.unavailable();
                html=new String(content,StandardCharsets.UTF_8);
            }
            var policy=single?DeliverablePreviewPolicy.validateSingleFileHtml(html,frames,List.of(local))
                    :DeliverablePreviewPolicy.validateMultiFileHtml(html,frames,List.of(local));
            if(!policy.accepted()) {
                io.micrometer.core.instrument.Metrics.counter("skillforge.deliverable.preview.rejected").increment();
                throw ArtifactStorage.unavailable();
            }
            var headers=exchange.getResponseHeaders();
            headers.set("Content-Security-Policy",policy.contentSecurityPolicy());
            headers.set("Referrer-Policy","no-referrer");headers.set("X-Content-Type-Options","nosniff");
            headers.set("Cache-Control","no-store");headers.set("Cross-Origin-Resource-Policy","cross-origin");
            headers.set("Access-Control-Allow-Origin","null");
            headers.set("Content-Type",mime(file));
            // file() 在首次输出前完成私有快照校验；提前提交 200 会把确定拒绝变成成功空体。
            var body=new PreviewBody(exchange);
            storage.file(row,storedPath,body);
            body.completeEmpty();
        }catch(Exception rejected){
            if(exchange.getResponseCode()<0){byte[] body="预览不可用、已过期或资源未打包".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");exchange.sendResponseHeaders(404,body.length);exchange.getResponseBody().write(body);}
        }finally{
            if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);
            exchange.close();
        }
    }

    /**
     * 仅供先验证私有快照的存储传输使用，第一次实际写入才提交成功头。
     * 不缓存媒体正文，也不拥有 exchange 的关闭责任；未开始输出的失败仍由入口发送现行拒绝。
     */
    private static final class PreviewBody extends OutputStream {
        private final HttpExchange exchange;

        /** 保留本次请求，成功头由经过存储校验后的首次写入触发。 */
        private PreviewBody(HttpExchange exchange) { this.exchange=exchange; }

        /** 单字节写入同样只能在存储已核验后提交成功响应。 */
        @Override public void write(int value) throws IOException { committedBody().write(value); }

        /** 空写入不代表正文已经就绪；非空写入保持原有 chunked 流式传输。 */
        @Override public void write(byte[] bytes,int offset,int length) throws IOException {
            Objects.checkFromIndexSize(offset,length,bytes.length);
            if(length!=0)committedBody().write(bytes,offset,length);
        }

        /** 首次输出时才提交 200，调用方保证完整性与当前可读性已在私有快照上通过。 */
        private OutputStream committedBody() throws IOException {
            if(exchange.getResponseCode()<0)exchange.sendResponseHeaders(200,0);
            return exchange.getResponseBody();
        }

        /** 合法零长度文件没有首次写入；仅在存储传输完整成功后显式提交无正文的 200。 */
        private void completeEmpty() throws IOException {
            if(exchange.getResponseCode()<0)exchange.sendResponseHeaders(200,-1);
        }
    }

    static String mime(String path) {
        String extension=path.substring(path.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        return switch(extension){case "html","htm"->"text/html; charset=utf-8";case "js","mjs"->"text/javascript; charset=utf-8";
            case "css"->"text/css; charset=utf-8";case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";
            case "svg"->"image/svg+xml";case "webp"->"image/webp";case "gif"->"image/gif";
            case "woff"->"font/woff";case "woff2"->"font/woff2";case "ico"->"image/x-icon";default->"application/octet-stream";};
    }
    @PreDestroy public void stop(){if(server!=null)server.stop(0);if(workers!=null)workers.close();}
}
