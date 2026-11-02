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
    private com.skillforge.institution.service.HandoffActionService actions;
    private HandoffController controller;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("7");
        handoffs = mock(HandoffApplicationService.class);
        sessions = mock(AgentSessionRegistry.class);
        actions = mock(com.skillforge.institution.service.HandoffActionService.class);
        controller = new HandoffController(handoffs, sessions, mock(com.skillforge.institution.service.HandoffReplyService.class), mock(com.skillforge.core.chat.ChatSessionService.class), actions);
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
        String acceptCommand = "12345678-1234-4234-8234-123456789abc";
        String resolveCommand = "22345678-1234-4234-8234-123456789abc";
        when(actions.execute(7L, 42L, "ticket-1", 1L, acceptCommand, "accept", null))
                .thenReturn(new com.skillforge.institution.service.HandoffActionService.Receipt(acceptCommand, "ticket-1", "accept", "assigned", true));
        when(actions.execute(7L, 42L, "ticket-1", 2L, resolveCommand, "resolve", null))
                .thenReturn(new com.skillforge.institution.service.HandoffActionService.Receipt(resolveCommand, "ticket-1", "resolve", "resolved", true));

        assertThat(controller.create(create).data().status()).isEqualTo("pending");
        try (var stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("42");
            assertThat(controller.acceptEndpoint(new HandoffController.AcceptCommand("ticket-1", 1L, acceptCommand)).data().status()).isEqualTo("assigned");
            assertThat(controller.resolveEndpoint(new HandoffController.ResolveCommand("ticket-1", 2L, resolveCommand, null)).data().status()).isEqualTo("resolved");
        }

        verify(sessions, org.mockito.Mockito.times(3)).notifyTicketChanged("7", "ticket-1");
    }

    @Test
    void actionEndpointNotifiesOnlyForFirstCommitAndReceiptReadsOriginalCommand() {
        String command = "12345678-1234-4234-8234-123456789abc";
        var first = new com.skillforge.institution.service.HandoffActionService.Receipt(command, "ticket-1", "accept", "assigned", true);
        var replay = new com.skillforge.institution.service.HandoffActionService.Receipt(command, "ticket-1", "accept", "assigned", false);
        when(actions.execute(7L, 42L, "ticket-1", 1L, command, "accept", null)).thenReturn(first, replay);
        when(actions.receipt(7L, 42L, "ticket-1", command)).thenReturn(replay);
        try (var stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("42");
            var request = new HandoffController.AcceptCommand("ticket-1", 1L, command);
            assertThat(controller.acceptEndpoint(request).data().commandId()).isEqualTo(command);
            assertThat(controller.acceptEndpoint(request).data().created()).isFalse();
            assertThat(controller.actionReceipt("ticket-1", command).data().ticketId()).isEqualTo("ticket-1");
        }
        verify(sessions).notifyTicketChanged("7", "ticket-1");
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
        String command = "12345678-1234-4234-8234-123456789abc";
        when(actions.execute(7L, 42L, "ticket-1", 1L, command, "accept", null))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "工单已被接单"));

        try (var stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("42");
            assertThatThrownBy(() -> controller.acceptEndpoint(new HandoffController.AcceptCommand("ticket-1", 1L, command)))
                    .isInstanceOfSatisfying(ApiException.class,
                            exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }

        verifyNoInteractions(sessions);
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
