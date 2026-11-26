package com.skillforge.institution.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.team.TeamPublishController;
import com.skillforge.institution.team.TeamDraftSaveController;
import com.skillforge.institution.team.TeamSavedPublishController;
import com.skillforge.institution.skill.SkillPublishController;
import com.skillforge.institution.controller.KnowledgeRevisionPublishController;
import com.skillforge.institution.controller.ConversationTaskController;
import com.skillforge.institution.controller.ConnectorController;
import com.skillforge.institution.controller.ConnectorDeleteReceiptController;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;

/** 只标识本次同步 HTTP 请求在进入回执命令前被认证链拒绝。 */
final class PublishPreDispatchRejection {
    private PublishPreDispatchRejection() { }
    private static String code(HttpServletRequest request, Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST || !(handler instanceof HandlerMethod method)) return null;
        if (method.getBeanType() == ConnectorController.class)
            return switch (method.getMethod().getName()) {
                case "createWithReceipt" -> "CONNECTOR_CREATE_REJECTED";
                case "updateWithReceipt" -> "CONNECTOR_UPDATE_REJECTED";
                default -> null;
            };
        if (method.getBeanType() == ConnectorDeleteReceiptController.class && method.getMethod().getName().equals("delete"))
            return "CONNECTOR_DELETE_REJECTED";
        if (method.getBeanType() == ConversationTaskController.class
                && (method.getMethod().getName().equals("select") || method.getMethod().getName().equals("update")))
            return "TASK_OPERATION_REJECTED_BEFORE_MUTATION";
        if (method.getBeanType() == TeamDraftSaveController.class && method.getMethod().getName().equals("save"))
            return TeamDraftSaveController.REJECTED;
        if (!method.getMethod().getName().equals("publish")) return null;
        if (method.getBeanType() == TeamSavedPublishController.class) return TeamPublishController.PRE_DISPATCH_REJECTED;
        if (method.getBeanType() == TeamPublishController.class) return TeamPublishController.PRE_DISPATCH_REJECTED;
        if (method.getBeanType() == SkillPublishController.class) return SkillPublishController.PRE_DISPATCH_REJECTED;
        if (method.getBeanType() == KnowledgeRevisionPublishController.class) return KnowledgeRevisionPublishController.PRE_DISPATCH_REJECTED;
        return null;
    }
    static RuntimeException rateLimit(HttpServletRequest request, Object handler, RuntimeException failure) {
        String code = code(request, handler);
        if (code != null && failure instanceof ApiException api && api.getStatus() == HttpStatus.TOO_MANY_REQUESTS)
            return new ApiException(api.getStatus(), code, String.valueOf(api.getDetail()), false);
        return failure;
    }
    static RuntimeException map(HttpServletRequest request, Object handler, RuntimeException failure) {
        String code = code(request, handler);
        if (code == null) return failure;
        if (failure instanceof ApiException api && (api.getStatus() == HttpStatus.UNAUTHORIZED || api.getStatus() == HttpStatus.FORBIDDEN))
            return new ApiException(api.getStatus(), code, String.valueOf(api.getDetail()), false);
        if (failure instanceof NotLoginException)
            return new ApiException(HttpStatus.UNAUTHORIZED, code, "未登录或登录已过期", false);
        if (failure instanceof NotPermissionException)
            return new ApiException(HttpStatus.FORBIDDEN, code, "无权访问该资源", false);
        return failure;
    }
}
