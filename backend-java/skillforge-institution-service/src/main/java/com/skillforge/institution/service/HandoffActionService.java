package com.skillforge.institution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.dto.handoff.ResolveTicketRequest;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 工单状态变更与原命令回执共同提交；查询不到回执不推断原命令失败。 */
@Service
public class HandoffActionService {
    private final HandoffApplicationService handoffs;
    private final HandoffActionMapper receipts;
    private final FoundationAccess access;
    private final ObjectMapper json = new ObjectMapper();
    public HandoffActionService(HandoffApplicationService handoffs, HandoffActionMapper receipts, FoundationAccess access) {
        this.handoffs = handoffs; this.receipts = receipts; this.access = access;
    }
    @Transactional
    public Receipt execute(long tenant, long member, String ticket, long revision, String command, String action, String note) {
        validateCommand(command);
        if (!action.equals("accept") && !action.equals("resolve")) throw new IllegalArgumentException("invalid_handoff_action");
        var ticketRow = handoffs.requireForWrite(tenant, ticket);
        handoffs.requireWriter(tenant, member, ticketRow, false);
        String fingerprint = fingerprint(tenant, member, ticket, revision, command, action, note);
        boolean created = receipts.insert(tenant, command, ticket, member, action, revision, fingerprint) == 1;
        if (!created) {
            var original = receipts.find(tenant, command);
            if (original == null || original.operatorId() != member || !original.fingerprint().equals(fingerprint))
                throw new ApiException(HttpStatus.CONFLICT, "工单命令已用于其他操作");
        } else {
            try {
                if (action.equals("accept")) handoffs.accept(tenant, ticket, member, revision);
                else handoffs.resolve(tenant, ticket, member, revision, new ResolveTicketRequest(note));
            } catch (ApiException rejected) {
                // 仅首次占用命令后转换；抛出异常使回执与状态变更共同回滚。
                if (rejected.getStatus() == HttpStatus.CONFLICT)
                    throw new ApiException(HttpStatus.CONFLICT, "HANDOFF_ACTION_NOT_ACCEPTED", rejected.getMessage(), false);
                throw rejected;
            }
        }
        return new Receipt(command, ticket, action, action.equals("accept") ? "assigned" : "resolved", created);
    }
    @Transactional(readOnly = true)
    public Receipt receipt(long tenant, long member, String ticket, String command) {
        validateCommand(command);
        access.require("foundation.handoff.read");
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()) || !Long.toString(member).equals(subject.memberId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "工单操作者归属不匹配");
        handoffs.require(tenant, ticket);
        var row = receipts.find(tenant, command);
        if (row == null || row.operatorId() != member || !row.ticketId().equals(ticket)) return null;
        return new Receipt(command, ticket, row.action(), row.action().equals("accept") ? "assigned" : "resolved", false);
    }
    private void validateCommand(String command) {
        try {
            if (command == null || !UUID.fromString(command).toString().equals(command)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "工单命令必须是标准 UUID");
        }
    }
    private String fingerprint(long tenant, long member, String ticket, long revision, String command, String action, String note) {
        try {
            byte[] bytes = json.writeValueAsString(Arrays.asList(tenant, member, ticket, revision, command, action, note)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    public record Receipt(@com.fasterxml.jackson.annotation.JsonProperty("command_id") String commandId,
            @com.fasterxml.jackson.annotation.JsonProperty("ticket_id") String ticketId, String action, String status,
            @com.fasterxml.jackson.annotation.JsonIgnore boolean created) { }
}
