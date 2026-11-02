package com.skillforge.runtime.adapter.jdbc.fixture;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.SessionOwner;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 测试 JSON codec 显式保存当前归属分支，并拒绝没有鉴别字段的旧 owner 快照。 */
class SessionSubjectJsonFixtureTest {
    private final ObjectMapper json = SessionSubjectJsonFixture.configure(new ObjectMapper());

    /** USER 与 GROUP 都经正式构造器读回，群不会补出个人用户。 */
    @Test void bothSubjectBranchesRoundTripThroughTheSnapshotMapper() throws Exception {
        var tenant = new TenantId("tenant"); var session = new SessionRef(tenant, "session");
        var group = new GroupBindingRef(tenant, "group");
        for (var owner : List.of(SessionOwner.user(session, new AgentId("agent"), new EndUserId(7)),
                new SessionOwner(session, SessionSubject.group(group), SessionTarget.group(group)))) {
            String encoded = json.writeValueAsString(owner);
            assertEquals(owner, json.readValue(encoded, SessionOwner.class));
            assertEquals(encoded, json.writeValueAsString(json.readValue(encoded, SessionOwner.class)));
        }
    }

    /** 缺少或未知 kind 不回落为 USER，类型错误不由旧 endUserId 字段兜底。 */
    @Test void missingAndUnknownSubjectKindsFailClosed() throws Exception {
        var owner = SessionOwner.user(new SessionRef(new TenantId("tenant"), "session"), new AgentId("agent"), new EndUserId(7));
        ObjectNode missing = json.valueToTree(owner);
        ((ObjectNode) missing.get("subject")).remove("kind");
        assertThrows(JsonProcessingException.class, () -> json.treeToValue(missing, SessionOwner.class));
        ObjectNode unknown = json.valueToTree(owner);
        ((ObjectNode) unknown.get("subject")).put("kind", "UNKNOWN");
        assertThrows(JsonProcessingException.class, () -> json.treeToValue(unknown, SessionOwner.class));
    }
}
