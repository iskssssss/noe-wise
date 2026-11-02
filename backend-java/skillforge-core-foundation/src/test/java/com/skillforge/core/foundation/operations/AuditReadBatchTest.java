package com.skillforge.core.foundation.operations;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.operations.OperationsContracts.AuditQuery;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 授权批次身份必须保留主体、版本、过滤和时间边界，分页不得放大新增审计量。 */
class AuditReadBatchTest {
    /** 同分钟同范围各页共享过滤身份；NULL与实值及纳秒时刻仍不同。 */
    @Test void exactFiltersAndMinuteBoundariesRemainDistinct() {
        var subject = new FoundationAccess.Subject(false, "7", "42");
        Instant at = Instant.parse("2026-10-13T05:06:30Z");
        AuditQuery first = new AuditQuery(null,null,null,null,null,null,null,null,1,50);
        AuditQuery next = new AuditQuery(null,null,null,null,null,null,null,null,2,100);
        AuditReadBatch a = AuditReadBatch.create(subject, 3, first, at);
        AuditReadBatch b = AuditReadBatch.create(subject, 3, next, at.plusSeconds(20));
        assertEquals(a.queryDigest(), b.queryDigest()); assertEquals(a.windowStart(), b.windowStart());
        assertNotEquals(a.windowStart(), AuditReadBatch.create(subject, 3, first, at.plusSeconds(30)).windowStart());
        AuditQuery filter = new AuditQuery("auth.login",null,null,null,null,null,null,null,1,50);
        assertNotEquals(a.queryDigest(), AuditReadBatch.create(subject, 3, filter, at).queryDigest());
        assertNotEquals(a.authorizationVersion(), AuditReadBatch.create(subject, 4, first, at).authorizationVersion());
        assertNotEquals(a.scope(), AuditReadBatch.create(new FoundationAccess.Subject(false,"8","42"), 3, first, at).scope());
        assertNotEquals(a.actorId(), AuditReadBatch.create(new FoundationAccess.Subject(false,"7","43"), 3, first, at).actorId());
        AuditQuery nanos = new AuditQuery(null,null,null,null,null,null,at.plusNanos(1),null,1,50);
        AuditQuery nanosChanged = new AuditQuery(null,null,null,null,null,null,at.plusNanos(2),null,1,50);
        assertNotEquals(AuditReadBatch.create(subject,3,nanos,at).queryDigest(), AuditReadBatch.create(subject,3,nanosChanged,at).queryDigest());
    }
}
