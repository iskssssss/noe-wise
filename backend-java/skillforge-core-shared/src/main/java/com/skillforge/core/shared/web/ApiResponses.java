package com.skillforge.core.shared.web;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import com.skillforge.core.shared.exception.ApiException;

/** Controller 显式投影响应，保留原状态和响应头，不使用全局自动包装。 */
public final class ApiResponses {
    private ApiResponses() { }

    public static <T> ApiResponse<T> wrap(T value) { return ApiResponse.success(value); }

    public static <T> ResponseEntity<ApiResponse<T>> wrap(ResponseEntity<T> response) {
        int status = response.getStatusCode().value();
        if (response.getStatusCode().isError()) {
            HttpStatus failure = HttpStatus.valueOf(status);
            throw new ApiException(failure, failure.getReasonPhrase());
        }
        ApiResponse<T> body = status == 204 || status == 304 ? null : ApiResponse.success(response.getBody());
        return new ResponseEntity<>(body, response.getHeaders(), response.getStatusCode());
    }
}
