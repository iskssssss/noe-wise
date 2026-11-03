package com.skillforge.institution.team;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TeamSavedPublishServiceTest {
    private final FoundationAccess access=mock(FoundationAccess.class);
    private final TeamService teams=mock(TeamService.class);
    private final TeamPublishReceiptMapper receipts=mock(TeamPublishReceiptMapper.class);
    private final CapabilityCatalogDatabaseContext context=mock(CapabilityCatalogDatabaseContext.class);
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    private final ObjectMapper json=new ObjectMapper();
    private final TeamPublishService service=new TeamPublishService(access,teams,receipts,context,json,transactions);

    private void setup() {
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","70"));
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(receipts.claim(eq(7L),anyString(),eq("70"),anyString())).thenReturn(1);
        when(receipts.complete(eq(7L),anyString(),anyString(),anyString())).thenReturn(1);
    }
    @Test void publishesOnlyTheRequestedRevisionAndRecordsSuccess() {
        setup();
        var result=new TeamDefinition("team","团队","","published",5,List.of(new TeamDefinition.Member("11","","成员","",List.of())),"11");
        when(teams.publish("7","team",4L)).thenReturn(result);
        var command=new TeamPublishService.SavedCommand(UUID.randomUUID(),"team",4);
        assertEquals(result,service.publishSaved(7,command).team());
        verify(receipts).complete(eq(7L),eq(command.requestId().toString()),eq("SUCCEEDED"),anyString());
        verify(teams).publish("7","team",4L);
        verify(teams,never()).save(anyString(),any(),any());
    }
    @Test void staleRevisionRecordsRejectionWithoutAnotherWrite() {
        setup();
        when(teams.publish("7","team",4L)).thenThrow(new ApiException(HttpStatus.CONFLICT,"版本已变化"));
        var command=new TeamPublishService.SavedCommand(UUID.randomUUID(),"team",4);
        assertEquals("REJECTED",service.publishSaved(7,command).status());
        verify(receipts).complete(eq(7L),eq(command.requestId().toString()),eq("REJECTED"),anyString());
        verify(teams,never()).save(anyString(),any(),any());
    }
    @Test void previouslyClaimedRequestDoesNotPublishAgain() {
        setup();
        when(receipts.claim(eq(7L),anyString(),eq("70"),anyString())).thenReturn(0);
        when(receipts.find(eq(7L),anyString())).thenAnswer(call -> {
            var captured=org.mockito.ArgumentCaptor.forClass(String.class);
            verify(receipts).claim(eq(7L),anyString(),eq("70"),captured.capture());
            return new TeamPublishReceiptMapper.Row("70",captured.getValue(),"PROCESSING",null);
        });
        assertEquals("PROCESSING",service.publishSaved(7,new TeamPublishService.SavedCommand(UUID.randomUUID(),"team",4)).status());
        verifyNoInteractions(teams);
    }
}
