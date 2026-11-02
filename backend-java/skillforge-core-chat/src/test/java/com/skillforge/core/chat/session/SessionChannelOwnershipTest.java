package com.skillforge.core.chat.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 渠道归属在统一创建入口冻结，不能因续聊或读取重新归属。 */
class SessionChannelOwnershipTest {
    @BeforeAll static void metadata(){
        var configuration=new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration,""),SessionDO.class);
    }
    @AfterEach void cleanup(){ChannelContext.clear();}
    private final SessionMapper mapper=mock(SessionMapper.class);
    private SessionService service(){
        var settings=mock(OperationalSettingsService.class);
        when(settings.read("memory")).thenReturn(new OperationalSettingsService.Snapshot(1,Map.of("session_ttl_minutes",1440L)));
        var access=mock(com.skillforge.core.chat.access.SessionAccessPolicy.class);
        when(access.visibleSessionIds(any())).thenReturn(null);
        return new SessionService(mapper,settings,access);
    }
    @Test void emptyAndDirectNewSessionsFreezeAuthenticatedChannel(){
        ChannelContext.setChannelId("8");var service=service();
        for(String marker:new String[]{null,"new","pending"}){
            var created=service.getOrCreate("7",9L,marker,10L,null,null);
            assertEquals(8L,created.getChannelId());verify(mapper).insert(org.mockito.ArgumentMatchers.argThat((SessionDO row) -> SessionService.view(row).equals(created)));
        }
    }
    @Test void adminSessionStaysWithoutChannelAndInvalidContextCreatesNothing(){
        assertNull(service().getOrCreate("7",9L,null,10L,null,null).getChannelId());
        reset(mapper);ChannelContext.setChannelId("invalid");
        assertThrows(ApiException.class,()->service().getOrCreate("7",9L,null,10L,null,null));
        verifyNoInteractions(mapper);
    }
    @Test void readsAndContinuationRejectOtherOrUnassignedChannel(){
        var existing=new SessionDO();existing.setSessionId("s");existing.setEndUserId(9L);existing.setAgentId(10L);existing.setChannelId(8L);
        when(mapper.selectOne(any())).thenReturn(existing);var service=service();
        ChannelContext.setChannelId("8");assertEquals(SessionService.view(existing),service.require("7","s"));
        assertEquals(SessionService.view(existing),service.getOrCreate("7",9L,"s",10L,null,null));
        ChannelContext.setChannelId("18");
        assertThrows(ApiException.class,()->service.require("7","s"));
        assertThrows(ApiException.class,()->service.getOrCreate("7",9L,"s",10L,null,null));
        existing.setChannelId(null);
        assertThrows(ApiException.class,()->service.require("7","s"));
        assertThrows(ApiException.class,()->service.getOrCreate("7",9L,"s",10L,null,null));
        verify(mapper,never()).updateById(any(SessionDO.class));
    }
    @Test void listingKeepsTenantAndChannelScopeWithoutUserFilter(){
        ChannelContext.setChannelId("8");service().list("7",null,10L);
        verify(mapper).listByLatestMessage(7L,null,10L,null,8L,null);
        ChannelContext.clear();service().list("7",null,null);
        verify(mapper).listByLatestMessage(7L,null,null,null,null,null);
    }
    @Test void directoryKeepsVisibleScopeAndSkipsQueryWhenScopeIsEmpty(){
        var access=mock(com.skillforge.core.chat.access.SessionAccessPolicy.class);
        var settings=mock(OperationalSettingsService.class);
        var service=new SessionService(mapper,settings,access);
        var visible=java.util.List.of("session-1");
        var item=new SessionDirectoryItem("session-1","目录摘要",3,java.time.Instant.parse("2026-10-30T01:00:00Z"),null,"team-1","第一条消息");
        when(access.visibleSessionIds("7")).thenReturn(visible);
        when(mapper.listByLatestMessage(7L,9L,null,"team-1",8L,visible)).thenReturn(java.util.List.of(item));
        ChannelContext.setChannelId("8");
        assertEquals(java.util.List.of(item),service.list("7",9L,null,"team-1"));
        verify(mapper).listByLatestMessage(7L,9L,null,"team-1",8L,visible);
        when(access.visibleSessionIds("7")).thenReturn(java.util.List.of());
        assertEquals(java.util.List.of(),service.list("7",null,null));
        verifyNoMoreInteractions(mapper);
    }
    @Test void directoryRejectsAgentAndTeamBeforeReadingScopeOrRows(){
        var access=mock(com.skillforge.core.chat.access.SessionAccessPolicy.class);
        var service=new SessionService(mapper,mock(OperationalSettingsService.class),access);
        assertThrows(ApiException.class,()->service.list("7",null,10L,"team-1"));
        verifyNoInteractions(access,mapper);
    }

}
