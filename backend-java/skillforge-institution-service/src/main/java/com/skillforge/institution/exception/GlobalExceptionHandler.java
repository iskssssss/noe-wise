package com.skillforge.institution.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.web.HostExceptionHandler;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.institution.controller.AgentController;
import com.skillforge.institution.controller.AgentCreationController;
import com.skillforge.institution.controller.KnowledgeController;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** 宿主注册共享 JSON 错误映射，SSE/二进制已提交后不追加错误正文。 */
@RestControllerAdvice
public class GlobalExceptionHandler extends HostExceptionHandler {
    public GlobalExceptionHandler(ObjectMapper json) { super(json); }

    /** multipart 大小拒绝发生在控制器调用之前，不代表文档写入结果未知。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> uploadTooLarge(MaxUploadSizeExceededException failure,
            HttpServletRequest request, HttpServletResponse response) {
        if (!"POST".equals(request.getMethod())
                || !"/api/v1/knowledge/bases/documents/upload"
                        .equals(request.getRequestURI().substring(request.getContextPath().length())))
            return super.handle(failure, response);
        if (response.isCommitted()) return null;
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure("KNOWLEDGE_UPLOAD_REJECTED", "文件超过服务器上传大小限制，请缩小文件后重试", false, java.util.Map.of()));
    }

    @Override
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handle(Exception failure, HttpServletResponse response) {
        var mapped = super.handle(failure, response);
        if (mapped == null || !(failure instanceof MethodArgumentNotValidException validation)
                || validation.getParameter().getMethod() == null)
            return mapped;
        var controller = validation.getParameter().getContainingClass();
        var method = validation.getParameter().getMethod().getName();
        String code;
        if (controller == KnowledgeController.class && Set.of("createBase", "updateBaseEndpoint").contains(method))
            code = "KNOWLEDGE_WRITE_REJECTED";
        else if (controller == AgentCreationController.class && "create".equals(method))
            code = "AGENT_CREATE_REJECTED";
        else if (controller == AgentController.class && "install".equals(method))
            code = "AGENT_INSTALL_REJECTED";
        else return mapped;
        var body = mapped.getBody();
        if (body == null) return mapped;
        return ResponseEntity.status(mapped.getStatusCode()).headers(mapped.getHeaders()).body(
                new ApiResponse<>(code, body.message(), null,
                        body.traceId(), body.retryable(), body.fieldErrors()));
    }
}
