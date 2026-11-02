package com.skillforge.platform.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.web.HostExceptionHandler;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.platform.controller.PlatformAgentToolController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 宿主注册共享 JSON 错误映射，SSE/二进制已提交后不追加错误正文。 */
@RestControllerAdvice
public class GlobalExceptionHandler extends HostExceptionHandler {
    public GlobalExceptionHandler(ObjectMapper json) { super(json); }

    @Override
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handle(Exception failure, HttpServletResponse response) {
        var mapped = super.handle(failure, response);
        if (!(failure instanceof MethodArgumentNotValidException validation)
                || validation.getParameter().getContainingClass() != PlatformAgentToolController.class
                || validation.getParameter().getMethod() == null
                || !Set.of("create", "update", "publish").contains(validation.getParameter().getMethod().getName()))
            return mapped;
        return rejected(mapped);
    }

    /** 消息体解析发生在控制器方法执行前；仅映射精确的工具部署写入入口。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException failure,
            HttpServletRequest request, HttpServletResponse response) {
        var mapped = super.handle(failure, response);
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (!(handler instanceof HandlerMethod method) || method.getBeanType() != PlatformAgentToolController.class
                || !Set.of("create", "update", "publish").contains(method.getMethod().getName())) return mapped;
        return rejected(mapped);
    }

    private ResponseEntity<ApiResponse<Void>> rejected(ResponseEntity<ApiResponse<Void>> mapped) {
        if (mapped == null || mapped.getBody() == null) return mapped;
        var body = mapped.getBody();
        return ResponseEntity.status(mapped.getStatusCode()).headers(mapped.getHeaders()).body(
                new ApiResponse<>(PlatformAgentToolController.WRITE_REJECTED, body.message(), null,
                        body.traceId(), false, body.fieldErrors()));
    }
}
