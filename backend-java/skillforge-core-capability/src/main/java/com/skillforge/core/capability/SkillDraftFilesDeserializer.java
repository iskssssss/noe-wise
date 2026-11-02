package com.skillforge.core.capability;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 文件映射不接受 JSON 重复键或隐式数值转换，防止保存时悄悄丢失文件。 */
public final class SkillDraftFilesDeserializer extends JsonDeserializer<Map<String, String>> {
    @Override
    public Map<String, String> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.isExpectedStartObjectToken()) throw JsonMappingException.from(parser, "files must be an object");
        Map<String, String> result = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) throw JsonMappingException.from(parser, "files path required");
            String path = parser.currentName();
            if (result.containsKey(path)) throw JsonMappingException.from(parser, "duplicate skill file path: " + path);
            if (result.size() >= SkillPackageValidator.MAX_FILES) throw JsonMappingException.from(parser, "too many skill files");
            if (parser.nextToken() != JsonToken.VALUE_STRING) throw JsonMappingException.from(parser, "file content must be base64 string");
            String content = parser.getText();
            if (content.length() > ((SkillPackageValidator.MAX_FILE_BYTES + 2L) / 3L) * 4L) {
                throw JsonMappingException.from(parser, "skill file exceeds size limit: " + path);
            }
            result.put(path, content);
        }
        return result;
    }
}
