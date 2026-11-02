package com.skillforge.core.shared.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.SaTokenException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** 两个宿主共用的 HTTP 异常映射；握手失败是 JSON，已提交的流绝不混写。 */
public abstract class HostExceptionHandler {
    private final JsonFieldPaths fieldPaths;

    protected HostExceptionHandler(ObjectMapper json) {
        fieldPaths = new JsonFieldPaths(json);
    }

    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求格式无效", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "身份未认证", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "无权访问", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "资源不存在", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "405", description = "方法不支持", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "406", description = "响应媒体类型不支持", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "状态或幂等冲突", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "请求体过大", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "请求媒体类型不支持", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "业务参数无效", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "限流", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "内部错误", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "服务暂不可用", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ApiResponse.class)))
    })
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handle(Exception failure, HttpServletResponse response) {
        if (response.isCommitted()) return null;
        resetDownloadResponse(response);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String code = "INTERNAL_ERROR", message = "内部错误";
        boolean retryable = false;
        HttpHeaders headers = new HttpHeaders();
        Map<String, String> fields = new LinkedHashMap<>();
        if (failure instanceof ApiException api) {
            status = api.getStatus(); code = api.getCode(); retryable = api.isRetryable();
            api.getRetryAfterSeconds().ifPresent(seconds ->
                    headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds)));
            if (api.getDetail() instanceof String text) message = text;
            else if (api.getDetail() instanceof Map<?, ?> detail) {
                Object text = detail.get("message");
                if (text == null) text = detail.get("detail");
                message = text instanceof String safe ? safe : "请求未完成";
            }
        } else if (failure instanceof MethodArgumentNotValidException validation) {
            status = HttpStatus.BAD_REQUEST; code = "VALIDATION_ERROR"; message = "参数校验失败";
            validation.getBindingResult().getFieldErrors().forEach(field -> fields.putIfAbsent(
                    fieldPaths.externalPath(validation.getBindingResult().getTarget(), field.getField()),
                    field.getDefaultMessage() == null ? "输入无效" : field.getDefaultMessage()));
        } else if (failure instanceof HttpMessageNotReadableException
                || failure instanceof MethodArgumentTypeMismatchException
                || failure instanceof ConstraintViolationException) {
            status = HttpStatus.BAD_REQUEST; code = "VALIDATION_ERROR"; message = "请求格式或参数无效";
        } else if (failure instanceof NotPermissionException || failure instanceof NotRoleException) {
            status = HttpStatus.FORBIDDEN; code = "FORBIDDEN"; message = "无权访问该资源";
        } else if (failure instanceof NotLoginException || failure instanceof SaTokenException) {
            status = HttpStatus.UNAUTHORIZED; code = "UNAUTHORIZED"; message = "未登录或登录已过期";
        } else if (failure instanceof ErrorResponse error) {
            status = HttpStatus.valueOf(error.getStatusCode().value());
            code = ApiErrors.code(status.value()); message = status.getReasonPhrase();
            headers.addAll(error.getHeaders());
        } else {
            // 不输出异常 message/cause 中可能包含的凭据或供应商正文。
            LoggerFactory.getLogger(getClass()).error("HTTP 请求异常 type={} location={}",
                    failure.getClass().getName(), failure.getStackTrace().length == 0 ? "unknown" : failure.getStackTrace()[0]);
        }
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(code, message, retryable, fields));
    }

    /**
     * 未提交的下载失败转为 JSON 前，清除文件正文及附件头，原样保留其他响应头的全部值。
     *
     * <p>Servlet 不保证 setHeader(name, null) 删除头，Tomcat 会保留旧附件名；必须 reset。
     * 旧 Content-Type 不恢复，由异常 ResponseEntity 明确设置 JSON；已提交响应由入口拒绝混写。
     */
    private static void resetDownloadResponse(HttpServletResponse response) {
        if (!response.containsHeader(HttpHeaders.CONTENT_DISPOSITION)
                && !response.containsHeader(HttpHeaders.CONTENT_LENGTH)) return;
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            headers.put(name, List.copyOf(response.getHeaders(name)));
        }
        response.reset();
        headers.forEach((name, values) -> {
            if (name.equalsIgnoreCase(HttpHeaders.CONTENT_DISPOSITION)
                    || name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)
                    || name.equalsIgnoreCase(HttpHeaders.CONTENT_TYPE)) return;
            values.forEach(value -> response.addHeader(name, value));
        });
    }
}
