package com.skillforge.core.foundation.security;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
/** 异步派发必须复用可信身份重新查权，不能只用旧的已通过标记。 */
class FoundationSecurityInterceptorTest {
 @ParameterizedTest @CsvSource({"GET,/api/v1/tenant/foundation/security/sessions,true,false","GET,/api/v1/auth/me,false,false","POST,/api/v1/tenant/foundation/security/policy,true,true"})
 void ordinaryRequestAuthenticatesOnceAndKeepsOnlyTrustedIdentity(String method,String uri,boolean full,boolean stepUp) throws Exception {
  var security=mock(FoundationSecurityService.class);var access=mock(FoundationAccess.class);
  var subject=new FoundationAccess.Subject(false,"7","11");when(access.currentSubject()).thenReturn(subject);
  when(security.verifyCurrentSession(subject,full,stepUp)).thenReturn(new SecurityRows.Session(71,"tenant:7",11,"credential",null,"full","browser",java.time.Instant.EPOCH,java.time.Instant.MAX,null,null));
  var request=new MockHttpServletRequest(method,uri);request.addHeader("security_session_id","999");
  var handler=new HandlerMethod(new FoundationSecurityController(security),FoundationSecurityController.class.getMethod("sessions"));
  assertThat(new FoundationSecurityInterceptor(security,access).preHandle(request,new MockHttpServletResponse(),handler)).isTrue();
  verify(security).verifyCurrentSession(subject,full,stepUp);verifyNoMoreInteractions(security);
  assertThat(request.getAttribute(FoundationSecurityInterceptor.SUBJECT_ATTRIBUTE)).isSameAs(subject);
  assertThat(request.getAttribute(FoundationSecurityInterceptor.SESSION_ATTRIBUTE)).isEqualTo(71L);
 }
 @Test void asyncDispatchRechecksTrustedDatabaseSessionWithoutReadingJwt() throws Exception {
  var security=mock(FoundationSecurityService.class);var access=mock(FoundationAccess.class);
  var interceptor=new FoundationSecurityInterceptor(security,access);var request=new MockHttpServletRequest("GET","/api/v1/tenant/foundation/security/sessions");request.setDispatcherType(DispatcherType.ASYNC);
  var subject=new FoundationAccess.Subject(false,"7","11");request.setAttribute(FoundationSecurityInterceptor.SUBJECT_ATTRIBUTE,subject);request.setAttribute(FoundationSecurityInterceptor.SESSION_ATTRIBUTE,71L);
  var controller=new FoundationSecurityController(security);var handler=new HandlerMethod(controller,FoundationSecurityController.class.getMethod("sessions"));
  assertThat(interceptor.preHandle(request,new MockHttpServletResponse(),handler)).isTrue();verify(security).verifyTrustedSession(subject,71,true,false);verifyNoInteractions(access);
  doThrow(new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"revoked")).when(security).verifyTrustedSession(subject,71,true,false);
  assertThatThrownBy(()->interceptor.preHandle(request,new MockHttpServletResponse(),handler)).isInstanceOf(ApiException.class);
  verify(security,times(2)).verifyTrustedSession(subject,71,true,false);
 }
 @Test void missingAsyncIdentityIsDenied() throws Exception {
  var security=mock(FoundationSecurityService.class);var interceptor=new FoundationSecurityInterceptor(security,mock(FoundationAccess.class));
  var request=new MockHttpServletRequest("GET","/api/v1/tenant/foundation/security/sessions");request.setDispatcherType(DispatcherType.ASYNC);
  var handler=new HandlerMethod(new FoundationSecurityController(security),FoundationSecurityController.class.getMethod("sessions"));
  assertThatThrownBy(()->interceptor.preHandle(request,new MockHttpServletResponse(),handler)).isInstanceOf(ApiException.class);verifyNoInteractions(security);
 }
 @ParameterizedTest @ValueSource(strings={"CHANNEL","OPENAPI","SERVICE_PRINCIPAL"})
 void externalAuthenticationKeepsItsOwningAuthenticationChain(String source) throws Exception {
  var security=mock(FoundationSecurityService.class);var access=mock(FoundationAccess.class);
  var request=new MockHttpServletRequest("GET","/api/v1/tenant/foundation/security/sessions");request.setAttribute("AUTH_SOURCE",source);
  var handler=new HandlerMethod(new FoundationSecurityController(security),FoundationSecurityController.class.getMethod("sessions"));
  assertThat(new FoundationSecurityInterceptor(security,access).preHandle(request,new MockHttpServletResponse(),handler)).isTrue();
  verifyNoInteractions(security,access);
 }
 @ParameterizedTest @ValueSource(booleans={false,true})
 void incompatibleAccountScopeRejectsBeforeDatabaseAuthentication(boolean platform) throws Exception {
  var security=mock(FoundationSecurityService.class);var access=mock(FoundationAccess.class);
  when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(platform,platform?null:"7","11"));
  var request=new MockHttpServletRequest("GET",platform?"/api/v1/tenant/foundation/security/sessions":"/api/v1/admin/foundation/security/sessions");
  var handler=new HandlerMethod(new FoundationSecurityController(security),FoundationSecurityController.class.getMethod("sessions"));
  assertThatThrownBy(()->new FoundationSecurityInterceptor(security,access).preHandle(request,new MockHttpServletResponse(),handler))
   .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
  verifyNoInteractions(security);
 }
}
