package com.skillforge.core.capability;

import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Catalog 生命周期错误码集中定义，供 service/context/controller 复用。 */
public final class CapabilityCatalogErrors {

    public static final String INVALID_TYPE = "CAPABILITY_INVALID_TYPE";
    public static final String INVALID_ARGUMENT = "CAPABILITY_INVALID_ARGUMENT";
    public static final String NOT_FOUND = "CAPABILITY_NOT_FOUND";
    public static final String REVISION_CONFLICT = "CAPABILITY_REVISION_CONFLICT";
    public static final String CONFLICT = "CAPABILITY_CONFLICT";
    public static final String DUPLICATE = "CAPABILITY_DUPLICATE";
    public static final String DESCRIPTOR_INVALID = "CAPABILITY_DESCRIPTOR_INVALID";
    public static final String DEPLOYMENT_INVALID = "CAPABILITY_DEPLOYMENT_INVALID";
    public static final String VERSION_NOT_PUBLISHED = "CAPABILITY_VERSION_NOT_PUBLISHED";
    public static final String SCOPE_FORBIDDEN = "CAPABILITY_SCOPE_FORBIDDEN";
    public static final String TENANT_REQUIRED = "CAPABILITY_TENANT_REQUIRED";
    public static final String IF_MATCH_REQUIRED = "CAPABILITY_IF_MATCH_REQUIRED";
    public static final String IF_MATCH_INVALID = "CAPABILITY_IF_MATCH_INVALID";

    private CapabilityCatalogErrors() {
    }

    public static ApiException exception(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message, false);
    }

    public static ApiException exception(
            HttpStatus status, String code, String message, Throwable cause) {
        ApiException exception = exception(status, code, message);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }
}
