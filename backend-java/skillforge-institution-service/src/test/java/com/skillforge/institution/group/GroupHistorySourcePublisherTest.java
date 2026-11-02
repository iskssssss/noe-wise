package com.skillforge.institution.group;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.institution.media.SessionMediaService;
import com.skillforge.runtime.api.SessionRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupHistorySourcePublisherTest {
    private GroupMessageMapper.Message message(String kind){return new GroupMessageMapper.Message(11L,"integration",41L,"session","source",1,"event",1,kind,"end_user".equals(kind)?31L:null,"agent".equals(kind)?61L:null,"agent".equals(kind)?"run":null,"agent".equals(kind)?"original-public-message":null,"{}","[]","fingerprint");}

    @Test void sourcePublishesStableMessageWithoutCreatingInputRunOrTrace() {
        var mapper=mock(GroupHistoryMapper.class);var mailbox=mock(SessionMailboxService.class);var media=mock(SessionMediaService.class);
        var now=Instant.parse("2026-10-22T01:00:00Z");
        when(mapper.source(11,"integration",41,"source")).thenReturn(new GroupHistoryMapper.Source(11L,"integration",41L,"session","source",1,"end_user","{\"kind\":\"end_user\",\"end_user_id\":\"31\"}","[{\"type\":\"text\",\"text\":\"第一段\"},{\"type\":\"text\",\"text\":\"第二段\"}]",now));
        when(media.resolveContent(any(SessionRef.class),anyList())).thenReturn(List.of(new SessionMediaService.TextContent("text","第一段"),new SessionMediaService.TextContent("text","第二段")));
        var publisher=new GroupHistorySourcePublisher(mapper,mailbox,media,new ObjectMapper().findAndRegisterModules());publisher.publish(message("end_user"));
        verify(mailbox).appendOutput(eq("11"),eq("session"),eq("output:group-source:source"),eq("message.delta"),argThat(payload->{
            var data=(Map<?,?>)payload.get("data");return "source".equals(data.get("message_id"))&&"第一段第二段".equals(data.get("content"))&&!data.containsKey("run_id")&&!data.containsKey("input_id")&&((List<?>)data.get("content_blocks")).size()==2;
        }));
        verifyNoMoreInteractions(mailbox);
    }

    @Test void agentSourceReflowDoesNotDuplicateTheOriginalPublicBody() {
        var mapper=mock(GroupHistoryMapper.class);var mailbox=mock(SessionMailboxService.class);var media=mock(SessionMediaService.class);
        new GroupHistorySourcePublisher(mapper,mailbox,media,new ObjectMapper()).publish(message("agent"));
        verifyNoInteractions(mapper,mailbox,media);
    }
}
