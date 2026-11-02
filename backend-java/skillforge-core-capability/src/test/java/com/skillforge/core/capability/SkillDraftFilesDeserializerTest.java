package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** HTTP 文件映射必须保留一条路径对应一份精确字节内容。 */
class SkillDraftFilesDeserializerTest {
    private final ObjectMapper json = new ObjectMapper();
    record Input(@JsonDeserialize(using = SkillDraftFilesDeserializer.class) Map<String, String> files) { }

    @Test
    void acceptsEmptyFileAndRejectsDuplicateOrCoercedValues() throws Exception {
        assertEquals(Map.of("empty.txt", ""), json.readValue("{\"files\":{\"empty.txt\":\"\"}}", Input.class).files());
        assertThrows(Exception.class, () -> json.readValue("{\"files\":{\"a\":\"YQ==\",\"a\":\"Yg==\"}}", Input.class));
        assertThrows(Exception.class, () -> json.readValue("{\"files\":{\"a\":123}}", Input.class));
        assertThrows(Exception.class, () -> json.readValue("{\"files\":{\"a\":null}}", Input.class));
        assertThrows(Exception.class, () -> json.readValue("{\"files\":[]}", Input.class));
    }
}
