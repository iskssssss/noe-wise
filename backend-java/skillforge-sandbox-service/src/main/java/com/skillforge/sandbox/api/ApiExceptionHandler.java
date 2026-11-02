package com.skillforge.sandbox.api;

import com.skillforge.execution.ExecutionAdmissionRejection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ExecutionAdmissionException.class)
    ResponseEntity<ExecutionAdmissionRejection> admissionRejected(ExecutionAdmissionException failure) {
        return ResponseEntity.badRequest().body(failure.rejection());
    }

    @ExceptionHandler(SandboxRequestException.class)
    ResponseEntity<ApiError> invalidRequest(SandboxRequestException failure) {
        return ResponseEntity.badRequest().body(new ApiError(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler(SandboxConflictException.class)
    ResponseEntity<ApiError> conflict(SandboxConflictException failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    ResponseEntity<ApiError> notFound(TaskNotFoundException failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("TASK_NOT_FOUND", failure.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformedRequest(Exception failure) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "请求字段缺失或格式非法"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception failure) {
        log.error("沙箱内部接口处理失败", failure);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "沙箱服务处理失败"));
    }
}
