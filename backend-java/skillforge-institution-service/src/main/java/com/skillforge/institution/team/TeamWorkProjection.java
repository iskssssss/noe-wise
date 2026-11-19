package com.skillforge.institution.team;

import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.api.dag.DagWork;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** DAG 已提交版本只投递变化通知；公开详情仍经原工作查询与权限边界读取。 */
@Component
public class TeamWorkProjection {
    private final JdbcDagWorkPort works;
    private final SessionMailboxService mailbox;

    public TeamWorkProjection(JdbcDagWorkPort works, SessionMailboxService mailbox) {
        this.works = works; this.mailbox = mailbox;
    }

    /** 提交后回调使用独立事务；原工作版本派生稳定身份，不占用执行日志投影游标。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void changed(DagWork.Key key) {
        var work = works.read(key).orElseThrow();
        String tenant = key.owner().sessionRef().tenantId().value(), session = key.owner().sessionRef().sessionId();
        String source = "work:" + tenant + ":" + session + ":" + key.rootInputId().value() + ":" + work.revision();
        String id = "output:" + UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        var data = Map.of("work_id", key.rootInputId().value(), "session_id", session);
        var trace = new TraceContext(key.rootInputId().value(), key.rootInputId().value(), source, source, source, null);
        mailbox.appendOutput(tenant, session, id, "work.updated", Map.of("event", "work.updated", "data", data), trace);
    }
}
