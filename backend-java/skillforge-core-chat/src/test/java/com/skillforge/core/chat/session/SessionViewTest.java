package com.skillforge.core.chat.session;

import com.skillforge.core.chat.persistence.SessionDO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 对外快照不会随实体或开放元数据的后续修改变化。 */
class SessionViewTest {
    @Test void snapshotSeparatesNestedMetadataAndPersistenceMutation() {
        var values = new ArrayList<Object>();
        values.add("first");
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("values", values);
        metadata.put("nullable", null);
        var entity = new SessionDO();
        entity.setSessionId("original");
        entity.setMetadata(metadata);
        var view = SessionService.view(entity);
        values.add("second");
        entity.setSessionId("changed");
        metadata.put("new", "value");
        assertEquals("original", view.getSessionId());
        assertEquals(java.util.List.of("first"), view.getMetadata().get("values"));
        assertFalse(view.getMetadata().containsKey("new"));
        assertThrows(UnsupportedOperationException.class, () -> view.getMetadata().put("new", "value"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((java.util.List<?>) view.getMetadata().get("values")).clear());
    }
}
