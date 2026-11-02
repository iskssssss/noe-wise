package com.skillforge.institution.auth;
import static org.mockito.Mockito.*;
import com.skillforge.core.foundation.security.FoundationSecurityService;
import com.skillforge.core.foundation.application.AuthService;
import org.junit.jupiter.api.Test;
/** 宿主入口固定账号体系，不能由客户端改变realm。 */
class AuthServiceTest {
 @Test void hostAuthenticationUsesFixedRealm(){var security=mock(FoundationSecurityService.class);var service=new AuthService(security);service.login("acme","admin","password");verify(security).login(false,"acme","admin","password");service.refresh("opaque");verify(security).refresh(false,"opaque");}
}
