package com.skillforge.institution.group;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.institution.media.SessionMediaService;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 原来源事务发布到唯一Outbox；公开来源恢复也复用稳定事件键，不构造执行身份。 */
@Service
public class GroupHistorySourcePublisher {
    private static final int RECOVERY_PAGE_SIZE=100;
    private final GroupHistoryMapper mapper;
    private final SessionMailboxService mailbox;
    private final SessionMediaService media;
    private final ObjectMapper json;

    public GroupHistorySourcePublisher(GroupHistoryMapper mapper,SessionMailboxService mailbox,
            SessionMediaService media,ObjectMapper json) {
        this.mapper=mapper;this.mailbox=mailbox;this.media=media;this.json=json;
    }

    /** 来源、pending、Outbox和原命令回执必须由同一事务提交。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void publish(GroupMessageMapper.Message message) {
        // 非用户回流已经引用原正式公开消息，记录来源不再复制其正文。
        if(!"end_user".equals(message.authorKind()))return;
        var source=mapper.source(message.tenantId(),message.integrationId(),message.groupBindingId(),message.sourceMessageId());
        if(source==null)throw new IllegalStateException("group_public_source_missing");
        publish(source);
    }

    /** 从真实群Session补齐缺失来源投影；幂等键关闭提交未知及重启后的重复发布窗口。 */
    @Transactional
    public void reconcile(SessionView session) {
        if(session.getGroupBindingId()==null||session.getIntegrationId()==null)
            throw new IllegalArgumentException("group_public_session_required");
        while(true) {
            var page=mapper.unpublishedSources(session.getTenantId(),session.getIntegrationId(),session.getGroupBindingId(),RECOVERY_PAGE_SIZE);
            if(page.isEmpty())return;
            for(var source:page)publish(source);
        }
    }

    private void publish(GroupHistoryMapper.Source source) {
        try {
            List<ChatContent> content=json.readValue(source.contentJson(),new TypeReference<List<ChatContent>>() { });
            Map<String,Object> author=json.readValue(source.authorJson(),new TypeReference<Map<String,Object>>() { });
            var resolved=media.resolveContent(new SessionRef(new TenantId(source.tenantId().toString()),source.sessionId()),content);
            var data=new LinkedHashMap<String,Object>();
            data.put("session_id",source.sessionId());data.put("message_id",source.sourceMessageId());
            data.put("message_version",1);data.put("role","user");data.put("state","completed");
            data.put("content",ChatContent.textValue(content));data.put("content_blocks",resolved);
            data.put("author",author);data.put("source_message_id",source.sourceMessageId());
            data.put("source_sequence",source.sourceSequence());data.put("created_at",source.createdAt().toString());
            data.put("references",List.of());
            mailbox.appendOutput(source.tenantId().toString(),source.sessionId(),eventId(source.sourceMessageId()),
                    "message.delta",Map.of("event","message.delta","data",data));
        } catch(JsonProcessingException malformed) {
            throw new IllegalStateException("group_public_source_invalid",malformed);
        }
    }

    static String eventId(String sourceMessageId){return "output:group-source:"+sourceMessageId;}
}
