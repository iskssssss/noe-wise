package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SkillCreationServiceTest {
    @Test
    void generatedKeyIsStableAcrossOriginalRequestAndReplay() {
        var drafts = mock(SkillDraftService.class);
        var receipts = mock(SkillCreateReceiptMapper.class);
        var context = mock(CapabilityCatalogDatabaseContext.class);
        var access = mock(FoundationAccess.class);
        var service = new SkillCreationService(drafts, receipts, context, access, new ObjectMapper());
        var scope = CapabilityCatalogScope.tenant(7L);
        var requestId = UUID.randomUUID();
        var fingerprint = new AtomicReference<String>();
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "8"));
        when(receipts.claim(eq(requestId.toString()), eq(7L), eq("8"), anyString())).thenAnswer(call -> {
            if (fingerprint.get() == null) { fingerprint.set(call.getArgument(3)); return 1; }
            return 0;
        });
        var draft = mock(SkillDraftView.class);
        when(draft.definitionId()).thenReturn(11L);
        when(draft.id()).thenReturn(12L);
        when(draft.revision()).thenReturn(1L);
        when(drafts.createSkill(scope, "skill-" + requestId, "测试技能", "说明")).thenReturn(draft);
        when(receipts.complete(requestId.toString(), 11L, 12L, 1L)).thenReturn(1);
        when(receipts.find(requestId.toString())).thenAnswer(call -> new SkillCreateReceiptMapper.Row("8", fingerprint.get(), "11", "12", 1L));
        var command = new SkillCreationService.Command(requestId, new SkillCreationService.Payload(null, "测试技能", "说明"));
        assertEquals("11", service.create(scope, command).definitionId());
        assertEquals("11", service.create(scope, command).definitionId());
        verify(drafts, times(1)).createSkill(scope, "skill-" + requestId, "测试技能", "说明");
        var changed = new SkillCreationService.Command(requestId, new SkillCreationService.Payload(null, "不同名称", "说明"));
        assertThrows(ApiException.class, () -> service.create(scope, changed));
    }
}
