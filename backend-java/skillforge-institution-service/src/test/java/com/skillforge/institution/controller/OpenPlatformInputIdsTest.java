package com.skillforge.institution.controller;

import com.skillforge.common.context.ServicePrincipalContext;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 外部最长键、轮换和含分隔符命名空间在真实共享入口形成有界且隔离的身份。 */
class OpenPlatformInputIdsTest {
    @Test void longestLegalKeyRemainsBoundedAndCredentialRotationKeepsItsIdentity() {
        var principal=principal("7","integration:"+"i".repeat(116),1);
        String session="session:"+"s".repeat(192),key="k".repeat(256);
        var rotated=principal("7",principal.integrationId(),2);
        var identities=new HashSet<String>();
        for(String kind:new String[]{"message","action","callback","command","steer"}) {
            String input=OpenPlatformInputIds.create(principal,session,kind,key);
            assertTrue(input.length()<=128);
            assertTrue(("run:"+input).length()<=240);
            assertTrue(identities.add(input));
            assertEquals(input,OpenPlatformInputIds.create(rotated,session,kind,key));
            assertNotEquals(input,OpenPlatformInputIds.create(principal,session,kind,key.substring(0,255)+"x"));
            assertNotEquals(input,OpenPlatformInputIds.create(principal,"other-session",kind,key));
        }
    }

    @Test void FullNamespaceDoesNotCollideThroughColonConcatenationOrSharedIntegration() {
        String left=OpenPlatformInputIds.create(principal("7","a:b",1),"c","message","key");
        String right=OpenPlatformInputIds.create(principal("7","a",1),"b:c","message","key");
        assertNotEquals(left,right);
        assertNotEquals(left,OpenPlatformInputIds.create(principal("8","a:b",1),"c","message","key"));
        assertEquals(left,OpenPlatformInputIds.create(principal("7","a:b",1),"c","message"," key "));
    }

    private ServicePrincipalContext.Identity principal(String tenant,String integration,long version) {
        return new ServicePrincipalContext.Identity("principal-"+version,tenant,integration,version,Set.of("sessions.write"));
    }
}
