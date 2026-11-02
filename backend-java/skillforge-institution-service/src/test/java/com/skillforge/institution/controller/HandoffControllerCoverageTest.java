package com.skillforge.institution.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.foundation.constant.RoleConstants;
import com.skillforge.core.chat.dto.handoff.CreateHandoffTicketRequest;
import com.skillforge.core.chat.dto.handoff.HandoffActionResponse;
import com.skillforge.core.chat.enums.HandoffTicketStatus;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.persistence.HumanHandoffTicketDO;
import com.skillforge.institution.service.HandoffApplicationService;
import com.skillforge.institution.ws.AgentSessionRegistry;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 转人工控制器覆盖：角色边界、租户参数与写入后通知。 */
class HandoffControllerCoverageTest {

    private HandoffApplicationService handoffs;
    private AgentSessionRegistry sessions;
    private HandoffController controller;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("7");
        handoffs = mock(HandoffApplicationService.class);
        sessions = mock(AgentSessionRegistry.class);
        controller = new HandoffController(handoffs, sessions, mock(com.skillforge.institution.service.HandoffReplyService.class), mock(com.skillforge.core.chat.ChatSessionService.class));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void listUsesTenantScopedApplicationQueryAndMapsRestSnapshot() {
        HumanHandoffTicketDO existing = ticket("ticket-1", "pending");
        existing.setEndUserId(99L);
        existing.setCreatedAt(Instant.parse("2026-08-25T00:00:00Z"));
        when(handoffs.list(7L, "pending")).thenReturn(List.of(existing));

        var response = controller.list("pending").data();

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.items().get(0).user()).isEqualTo("99");
        verify(handoffs).list(7L, "pending");
    }

    @Test
    void successfulWritesNotifyTenantAndNeverPublishClientPayloads() {
        CreateHandoffTicketRequest create = new CreateHandoffTicketRequest(
                null, "summary", "session-1", "execution-1", null, "00000000-0000-0000-0000-000000000001");
        when(handoffs.create(7L, create)).thenReturn(new HandoffApplicationService.CreateReceipt("ticket-1", "pending", "session-1", create.commandId(), true));
        when(handoffs.accept(7L, "ticket-1", 42L, 1L))
                .thenReturn(action("ticket-1", HandoffTicketStatus.ASSIGNED));
        when(handoffs.resolve(7L, "ticket-1", 42L, 2L, null))
                .thenReturn(action("ticket-1", HandoffTicketStatus.RESOLVED));

        assertThat(controller.create(create).data().status()).isEqualTo("pending");
        try (var stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("42");
            assertThat(controller.accept("ticket-1", 1L).status()).isEqualTo(HandoffTicketStatus.ASSIGNED);
            assertThat(controller.resolve("ticket-1", 2L, null).status()).isEqualTo(HandoffTicketStatus.RESOLVED);
        }

        verify(sessions, org.mockito.Mockito.times(3)).notifyTicketChanged("7", "ticket-1");
    }

    @Test
    void auditorCanOnlyUseListEndpoint() throws Exception {
        RequirePermission listRoles = HandoffController.class.getMethod("list", String.class)
                .getAnnotation(RequirePermission.class);
        RequirePermission createRoles = HandoffController.class
                .getMethod("create", CreateHandoffTicketRequest.class)
                .getAnnotation(RequirePermission.class);
        RequirePermission acceptRoles = HandoffController.class
                .getMethod("acceptEndpoint", HandoffController.AcceptCommand.class)
                .getAnnotation(RequirePermission.class);
        RequirePermission resolveRoles = HandoffController.class
                .getMethod("resolveEndpoint", HandoffController.ResolveCommand.class)
                .getAnnotation(RequirePermission.class);

        assertThat(listRoles.value()).isEqualTo("foundation.handoff.read");
        assertThat(createRoles.value()).isEqualTo("foundation.handoff.create");
        assertThat(acceptRoles.value()).isEqualTo("foundation.handoff.update");
        assertThat(resolveRoles.value()).isEqualTo("foundation.handoff.update");
    }

    @Test
    void failedCasDoesNotPublishChangeNotification() {
        when(handoffs.accept(7L, "ticket-1", 42L, 1L))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "工单已被接单"));

        try (var stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("42");
            assertThatThrownBy(() -> controller.accept("ticket-1", 1L))
                    .isInstanceOfSatisfying(ApiException.class,
                            exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }

        verifyNoInteractions(sessions);
    }

    private static HandoffActionResponse action(String id, HandoffTicketStatus status) {
        return new HandoffActionResponse(id, status);
    }

    private static HumanHandoffTicketDO ticket(String id, String status) {
        HumanHandoffTicketDO ticket = new HumanHandoffTicketDO();
        ticket.setId(1L);
        ticket.setTicketId(id);
        ticket.setRevision(1L);
        ticket.setTenantId(7L);
        ticket.setReason("user_request");
        ticket.setSummary("summary");
        ticket.setPriority(1);
        ticket.setStatus(status);
        return ticket;
    }
}
