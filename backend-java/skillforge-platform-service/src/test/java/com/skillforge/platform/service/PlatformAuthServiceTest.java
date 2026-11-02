package com.skillforge.platform.service;
import static org.mockito.Mockito.*;
import com.skillforge.core.foundation.security.FoundationSecurityService;

import org.junit.jupiter.api.Test;
/** 宿主入口固定账号体系，不能由客户端改变realm。 */
class PlatformAuthServiceTest {
 @Test void hostAuthenticationUsesFixedRealm(){var security=mock(FoundationSecurityService.class);var service=new PlatformAuthService(security);service.login("admin","password");verify(security).login(true,null,"admin","password");service.refresh("opaque");verify(security).refresh(true,"opaque");}
}
