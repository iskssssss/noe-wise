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
        if (failure instanceof com.skillforge.runtime.session.SessionGoal.Rejected rejected) {
            String message = switch (rejected.reason()) {
                case REVISION_CONFLICT -> "目标状态已更新，请核对最新状态后重新操作。";
                case STATE_CONFLICT -> "当前目标状态不支持此操作，请核对最新状态。";
                case ALREADY_ACTIVE -> "当前会话已有未结束目标，请先查看当前目标。";
                case NOT_FOUND -> "当前会话没有持续目标。";
                case OPERATION_CONFLICT -> "此操作与原请求不一致，请先核对原操作结果。";
                default -> null;
            };
            if (message != null) return super.handle(new com.skillforge.core.shared.exception.ApiException(
                    rejected.reason() == com.skillforge.runtime.session.SessionGoal.Reason.NOT_FOUND
                            ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT,
                    rejected.code(), message, false), response);
        }
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
