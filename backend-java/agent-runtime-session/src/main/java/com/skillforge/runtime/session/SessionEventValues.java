package com.skillforge.runtime.session;

import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 业务日志的稳定值摘要与提交回执核验；不协调Run、外部动作或恢复。 */
final class SessionEventValues {
    private SessionEventValues() { }
    static String hash(String schema,String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((schema+"\n"+value).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}
    }
    static void requireAppend(AppendSessionEvent command,CommittedSessionEvent event,long afterSequence) {
        var target=command.target();var draft=command.event();
        if(!event.owner().equals(target.owner())||!event.inputId().equals(target.inputId())||!event.runRef().equals(target.runRef())
                ||!event.executionRef().equals(target.executionRef())||!event.actionRef().equals(target.actionRef())||!event.delegationRef().equals(target.delegationRef())
                ||!event.eventId().equals(draft.eventId())||!event.eventType().equals(draft.eventType())||!event.eventVersion().equals(draft.eventVersion())
                ||!event.trace().equals(draft.trace())||!event.payload().equals(draft.payload())||!event.occurredAt().equals(draft.occurredAt())||event.sessionSequence()<=afterSequence)
            throw new IllegalStateException("session_event_ack_mismatch");
    }
}
