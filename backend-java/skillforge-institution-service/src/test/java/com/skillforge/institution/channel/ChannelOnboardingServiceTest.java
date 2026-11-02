package com.skillforge.institution.channel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.channel.ChannelService;
import com.skillforge.core.channel.dto.request.CreateChannelRequest;
import com.skillforge.core.channel.dto.response.CreateChannelResponse;
import com.skillforge.core.channel.enums.ChannelStatus;
import com.skillforge.core.channel.enums.ChannelType;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChannelOnboardingServiceTest {
    @Test void originalResponseContainsTokenButReceiptAndReplayNeverDo() {
        var access = mock(FoundationAccess.class);
        var channels = mock(ChannelService.class);
        var receipts = mock(ChannelCreateReceiptMapper.class);
        var context = mock(CapabilityCatalogDatabaseContext.class);
        var service = new ChannelOnboardingService(access, channels, receipts, context, new ObjectMapper());
        var requestId = UUID.randomUUID();
        var request = new CreateChannelRequest("渠道", ChannelType.WEB_WIDGET, Map.of(), Map.of(), null);
        var command = new ChannelOnboardingService.Command(requestId, request);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "8"));
        when(receipts.claim(eq(7L), eq(requestId.toString()), eq("8"), anyString())).thenReturn(1, 0);
        when(receipts.complete(7L, requestId.toString(), 31L)).thenReturn(1);
        when(channels.create(7L, request)).thenReturn(new CreateChannelResponse(31L, "渠道",
                ChannelType.WEB_WIDGET, ChannelStatus.ACTIVE, "secret", Map.of(), Map.of()));
        assertThat(service.create(7L, command).channel().channelToken()).isEqualTo("secret");
        var fingerprint = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(receipts).claim(eq(7L), eq(requestId.toString()), eq("8"), fingerprint.capture());
        when(receipts.find(7L, requestId.toString()))
                .thenReturn(new ChannelCreateReceiptMapper.Row("8", fingerprint.getValue(), 31L));
        assertThat(service.create(7L, command).channel()).isNull();
        assertThat(service.result(7L, requestId).channel()).isNull();
        assertThat(service.result(7L, requestId).channelId()).isEqualTo("31");
        verify(channels, times(1)).create(7L, request);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "9"));
        assertThat(service.result(7L, requestId)).isNull();
        assertThatThrownBy(() -> service.create(7L, command)).isInstanceOf(ApiException.class);
    }
}
