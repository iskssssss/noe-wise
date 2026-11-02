package com.skillforge.institution.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.application.EndUserService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 雪花用户ID从ensure输出到创建会话始终为精确十进制字符串。 */
class OpenPlatformEndUserControllerTest {
    @Test void ensuredIdentityIsAJsonStringBeyondJavaScriptIntegerPrecision() {
        var access=mock(OpenPlatformAccessService.class);
        var users=mock(EndUserService.class);
        var principal=new ServicePrincipalContext.Identity("sp","7","crm",1,Set.of("end_users.write"));
        when(access.require("end_users.write")).thenReturn(new OpenPlatformAccessService.Access(principal));
        var user=new EndUserDO();user.setId(9007199254740993L);user.setExternalUserRef("external-user");
        when(users.ensureExternal("7","crm","external-user","用户")).thenReturn(user);
        var result=new OpenPlatformEndUserController(access,users).ensure(
                new OpenPlatformEndUserController.EnsureEndUser("external-user","用户"));
        var json=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).valueToTree(result.data());
        assertTrue(json.path("end_user_id").isTextual());
        assertEquals("9007199254740993",json.path("end_user_id").textValue());
    }
}
