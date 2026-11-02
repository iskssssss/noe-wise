package com.skillforge.core.foundation.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationPermissionInterceptor;
import com.skillforge.core.foundation.access.RequirePermission;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/** 动作授权取代JWT角色；外部认证和异步派发必须具有明确可信来源。 */
class FoundationPermissionInterceptorTest {
    /** 测试入口拥有明确动作，不存在角色字段的默认放行。 */
    static class Endpoint {
        /** 入口声明由实际拦截器消费。 */
        @RequirePermission("foundation.operations.audit_read") public void read() { }
    }
    /** 同一动作每次调用重新授权，撤销后立即拒绝。 */
    @Test void declaredPermissionRecheckedOnEveryRequest() throws Exception {
        FoundationAccess access=mock(FoundationAccess.class);
        var interceptor=new FoundationPermissionInterceptor(access);
        var handler=new HandlerMethod(new Endpoint(),Endpoint.class.getMethod("read"));
        var request=new MockHttpServletRequest(); var response=new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request,response,handler)); verify(access).require("foundation.operations.audit_read");
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"已撤权")).when(access).require("foundation.operations.audit_read");
        assertThrows(ApiException.class,()->interceptor.preHandle(request,response,handler));
    }
    /** 异步派发缺少被认证链保存的主体时不能借用线程身份。 */
    @Test void asyncRequiresExplicitTrustedSubject() throws Exception {
        FoundationAccess access=mock(FoundationAccess.class);
        var interceptor=new FoundationPermissionInterceptor(access);
        var handler=new HandlerMethod(new Endpoint(),Endpoint.class.getMethod("read"));
        var request=new MockHttpServletRequest(); request.setDispatcherType(DispatcherType.ASYNC);
        assertThrows(ApiException.class,()->interceptor.preHandle(request,new MockHttpServletResponse(),handler)); verifyNoInteractions(access);
        var subject=new FoundationAccess.Subject(false,"7","42");
        request.setAttribute(com.skillforge.core.foundation.security.FoundationSecurityInterceptor.SUBJECT_ATTRIBUTE,subject);
        assertTrue(interceptor.preHandle(request,new MockHttpServletResponse(),handler));
        verify(access).require(subject,"foundation.operations.audit_read");
    }
    /** 只有宿主提供的独立认证判定能走已授权外部入口。 */
    @Test void externalBypassMustBeExplicitlyVerifiedByHost() throws Exception {
        FoundationAccess access=mock(FoundationAccess.class);
        var request=new MockHttpServletRequest();
        var handler=new HandlerMethod(new Endpoint(),Endpoint.class.getMethod("read"));
        var interceptor=new FoundationPermissionInterceptor(access,current->Boolean.TRUE.equals(current.getAttribute("verified")));
        request.setAttribute("verified",true);
        assertTrue(interceptor.preHandle(request,new MockHttpServletResponse(),handler)); verifyNoInteractions(access);
    }
}
