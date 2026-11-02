package com.skillforge.institution.team;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.port.SessionOwner;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TeamWorkInputsTest {
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void recentReceiptsKeepOriginalOperationVersionAndStateWithoutPrivateContent() throws Exception {
        var mapper = mock(TeamWorkInputMapper.class);
        var json = new ObjectMapper();
        var inputs = new TeamWorkInputs(mapper, json);
        var work = work("7", "work-1");
        var rows = List.of(row(json, "applied", "approve_plan", 3L, "APPLIED"),
                row(json, "accepted", "approve_plan", 4L, "ACCEPTED"),
                row(json, "rejected", "approve_plan", 2L, "REJECTED"),
                row(json, "supplement", "supplement", null, "APPLIED"));
        when(mapper.recent("7", "session-1", "work-1", 20)).thenReturn(rows);
        var receipts = inputs.recent(work);
        assertEquals(List.of("APPLIED", "ACCEPTED", "REJECTED", "APPLIED"),
                receipts.stream().map(TeamWorkInputs.Receipt::state).toList());
        assertEquals(List.of(3L, 4L, 2L), receipts.subList(0, 3).stream().map(TeamWorkInputs.Receipt::planRevision).toList());
        assertEquals("supplement", receipts.getLast().operation());
        assertNull(receipts.getLast().planRevision());
        var encoded = json.writeValueAsString(receipts);
        assertTrue(encoded.contains("\"operation\":\"approve_plan\""));
        assertTrue(encoded.contains("\"plan_revision\":3"));
        assertFalse(encoded.contains("private-content"));
        assertFalse(encoded.contains("private-pending"));
        assertFalse(encoded.contains("private-actor"));
        assertFalse(encoded.contains("private-detail"));
        assertNull(TenantContext.getTenantId());
        assertEquals(List.of(), inputs.recent(work("7", "another-work")));
        verify(mapper).recent("7", "session-1", "work-1", 20);
        verify(mapper).recent("7", "session-1", "another-work", 20);
        TenantContext.setTenantId("8");
        assertThrows(SecurityException.class, () -> inputs.recent(work));
        assertEquals("8", TenantContext.getTenantId());
        verifyNoMoreInteractions(mapper);
    }

    private static TeamWorkInputMapper.Row row(ObjectMapper json, String id, String operation,
            Long revision, String state) throws Exception {
        var row = new TeamWorkInputMapper.Row();
        row.inputId = id;
        row.rootInputId = "work-1";
        row.state = state;
        row.detail = "private-detail";
        row.payload = json.writeValueAsString(new TeamWorkInputs.Control(id, "", operation,
                "private-content".repeat(1000), revision, "private-pending", "private-version", "private-actor"));
        return row;
    }

    private static DagWork work(String tenant, String root) {
        var owner = SessionOwner.user(new SessionRef(new TenantId(tenant), "session-1"),
                SessionTarget.agent(new AgentId("101")), new EndUserId(8L));
        return new DagWork(new DagWork.Key(owner, new InputId(root)), new AgentId("101"), 1,
                DagWork.Status.ACTIVE, null, 0, 0, Map.of(), Map.of(),
                new DagWork.Request("目标", Optional.empty(), new RunRef(new TenantId(tenant), "run-1"), ""));
    }
}
