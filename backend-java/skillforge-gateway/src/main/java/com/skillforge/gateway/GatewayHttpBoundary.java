package com.skillforge.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 网关只投影自身代理错误；宿主信封、SSE 与二进制正文原样转发，不引入 Servlet 依赖。 */
@Component
@Order(-2)
public final class GatewayHttpBoundary implements WebFilter, ErrorWebExceptionHandler {
    private static final String TRACE = GatewayHttpBoundary.class.getName() + ".trace";
    private static final String HEADER = "X-Trace-Id";
    private final ObjectMapper mapper;

    public GatewayHttpBoundary(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String supplied = exchange.getRequest().getHeaders().getFirst(HEADER);
        String trace = supplied != null && supplied.matches("[A-Za-z0-9._:-]{1,128}")
                ? supplied : UUID.randomUUID().toString();
        exchange.getAttributes().put(TRACE, trace);
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, trace);
            return Mono.empty();
        });
        var forwarded = exchange.mutate().request(request -> request.headers(headers -> headers.set(HEADER, trace))).build();
        return chain.filter(forwarded).contextWrite(context -> context.put(TRACE, trace));
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/v1/")
                || exchange.getResponse().isCommitted()) return Mono.error(failure);
        int status = failure instanceof ErrorResponse error ? error.getStatusCode().value() : 500;
        String code = "GATEWAY_ERROR", message = "网关请求未完成";
        // 只声明传输失败，不推断上游是否已受理；客户端不得据此自动重发 POST。
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) { status = 502; code = "UPSTREAM_UNAVAILABLE"; message = "上游暂不可用"; break; }
            if (cause instanceof TimeoutException) { status = 504; code = "UPSTREAM_TIMEOUT"; message = "上游响应超时"; break; }
        }
        org.slf4j.LoggerFactory.getLogger(GatewayHttpBoundary.class).warn(
                "Gateway failure type={} cause={} status={}", failure.getClass().getName(),
                failure.getCause() == null ? "none" : failure.getCause().getClass().getName(), status);
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
        response.getHeaders().remove(HttpHeaders.CONTENT_DISPOSITION);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String trace = exchange.getAttribute(TRACE);
        if (trace == null) return Mono.error(failure);
        var body = new GatewayErrorResponse(code, message, null, trace, false);
        try {
            return response.writeWith(Mono.just(response.bufferFactory().wrap(mapper.writeValueAsBytes(body))));
        } catch (JsonProcessingException encoding) {
            return Mono.error(encoding);
        }
    }

    /** 固定代理失败信封；显式保留空 data，不携带上游内部正文。 */
    private record GatewayErrorResponse(
            String code, String message,
            @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode data,
            @JsonProperty("trace_id") String traceId, boolean retryable) {}
}
