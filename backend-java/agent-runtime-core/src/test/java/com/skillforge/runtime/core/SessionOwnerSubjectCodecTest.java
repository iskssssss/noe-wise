package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.SessionOwner;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 显式 subject 的规范字节与严格解码，不依赖个人执行入口才能保存群归属。 */
class SessionOwnerSubjectCodecTest {
    private static final CodecLimits LIMITS = new CodecLimits(4000, 100, 10);
    private static final String GROUP_JSON = "{\"sessionRef\":{\"sessionId\":\"shared\",\"tenantId\":\"tenant\"},"
            + "\"subject\":{\"groupBindingRef\":{\"groupBindingId\":\"opaque\",\"tenantId\":\"tenant\"},\"kind\":\"GROUP\"},"
            + "\"target\":{\"id\":\"opaque\",\"kind\":\"GROUP\"}}";

    /** 群规范 owner 不含 EndUser；编码、重启后解码及重编码保持完全相同。 */
    @Test void groupOwnerMatchesIndependentCanonicalBytesAndRoundTrips() {
        var group = new GroupBindingRef(new TenantId("tenant"), "opaque");
        var owner = new SessionOwner(new SessionRef(group.tenantId(), "shared"), SessionSubject.group(group), SessionTarget.group(group));
        assertEquals(GROUP_JSON, CanonicalJson.encode(ModelPreparationIdentity.owner(owner)));
        var decoded = ModelPreparationIdentity.decodeOwner(CanonicalJson.parse(GROUP_JSON, LIMITS));
        assertEquals(owner, decoded); assertEquals(GROUP_JSON, CanonicalJson.encode(ModelPreparationIdentity.owner(decoded)));
        assertThrows(IllegalStateException.class, decoded::requireUser);
    }

    /** 个人分支的十进制精度与显式 USER 类型不会被替换为群或丢失。 */
    @Test void userOwnerHasExplicitSubjectAndPreservesLargeId() {
        var owner = SessionOwner.user(new SessionRef(new TenantId("tenant"), "personal"), SessionTarget.team("team"), new EndUserId(9007199254740993L));
        String encoded = CanonicalJson.encode(ModelPreparationIdentity.owner(owner));
        assertTrue(encoded.contains("\"subject\":{\"endUserId\":\"9007199254740993\",\"kind\":\"USER\"}"));
        assertEquals(owner, ModelPreparationIdentity.decodeOwner(CanonicalJson.parse(encoded, LIMITS)));
    }

    /** 未知分支、错机构、错目录、混合字段和旧顶层用户字段均明确拒绝。 */
    @Test void strictDecoderRejectsCrossTenantWrongTargetAndLegacyShape() {
        for (String invalid : List.of(
                GROUP_JSON.replace("\"groupBindingId\":\"opaque\",\"tenantId\":\"tenant\"", "\"groupBindingId\":\"opaque\",\"tenantId\":\"other\""),
                GROUP_JSON.replace("\"target\":{\"id\":\"opaque\"", "\"target\":{\"id\":\"different\""),
                GROUP_JSON.replace("\"kind\":\"GROUP\"},\"target\"", "\"kind\":\"UNKNOWN\"},\"target\""),
                GROUP_JSON.replace("\"kind\":\"GROUP\"},\"target\"", "\"endUserId\":\"7\",\"kind\":\"GROUP\"},\"target\""),
                "{\"sessionRef\":{\"sessionId\":\"s\",\"tenantId\":\"tenant\"},\"target\":{\"id\":\"agent\",\"kind\":\"AGENT\"},\"endUserId\":\"7\"}")) {
            assertThrows(IllegalArgumentException.class, () -> ModelPreparationIdentity.decodeOwner(CanonicalJson.parse(invalid, LIMITS)));
        }
    }
}
