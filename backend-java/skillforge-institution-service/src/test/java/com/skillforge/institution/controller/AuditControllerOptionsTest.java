package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.AuditLogService;
import com.skillforge.core.foundation.persistence.AuditLogDO;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditControllerOptionsTest {
    @Test
    void stringActorsDoNotPreventActionOptionsAndMemberNamesRemainExact() {
        var logs = mock(AuditLogService.class);
        var members = mock(TenantMemberMapper.class);
        var member = new TenantMemberDO();
        member.setId(9007199254740993L);
        member.setUsername("巡检成员");
        when(members.selectList(any())).thenReturn(List.of(member));
        when(logs.listObjs(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<AuditLogDO>>any())).thenAnswer(call -> {
            QueryWrapper<AuditLogDO> query = call.getArgument(0);
            assertTrue(query.getSqlSegment().contains("tenant_id"));
            assertTrue(query.getParamNameValuePairs().containsValue(7L));
            return switch (query.getSqlSelect()) {
                case "DISTINCT action" -> List.of("auth.login", "trajectory.content.read");
                case "DISTINCT actor_id" -> List.of("9007199254740993", "channel:42", "service:opaque-id", "999999999999999999999999999");
                case "DISTINCT category" -> List.of("authentication");
                case "DISTINCT outcome" -> List.of("success");
                case "DISTINCT resource_type" -> List.of("security_session");
                default -> throw new AssertionError(query.getSqlSelect());
            };
        });
        var response = TenantContext.callWithTenant("7", () -> new AuditController(logs, members).options());
        var options = response.data();
        assertEquals(List.of("auth.login", "trajectory.content.read"), options.actions().stream().map(AuditController.AuditFilterOption::value).toList());
        assertEquals("登录", options.actions().get(0).label());
        assertEquals(List.of("9007199254740993", "channel:42", "service:opaque-id", "999999999999999999999999999"), options.actors().stream().map(AuditController.AuditFilterOption::value).toList());
        assertEquals("巡检成员", options.actors().get(0).label());
        assertEquals("安全会话", options.resourceTypes().get(0).label());
    }
}
