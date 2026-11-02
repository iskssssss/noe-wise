package com.skillforge.core.chat.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 有序的用户输入；图片只携带选择器，服务端解析并冻结可信媒体事实。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = ChatContent.Text.class, name = "text"),
        @JsonSubTypes.Type(value = ChatContent.Image.class, name = "image")})
public sealed interface ChatContent permits ChatContent.Text, ChatContent.Image {
    /** 历史 JSONB 的动态 metadata 边界同样需要保留具体类型。 */
    @JsonProperty("type")
    default String type() { return this instanceof Text ? "text" : "image"; }

    @Schema(name = "ChatContentText")
    record Text(String text) implements ChatContent {
        public Text { Objects.requireNonNull(text, "text"); }
    }
    @Schema(name = "ChatContentImage")
    record Image(@JsonProperty("media_id") String mediaId) implements ChatContent {
        public Image {
            if (mediaId == null || !java.util.UUID.fromString(mediaId).toString().equals(mediaId))
                throw new IllegalArgumentException("chat_media_id_invalid");
        }
    }
    static List<ChatContent> text(String value) {
        return value == null || value.isEmpty() ? List.of() : List.of(new Text(value));
    }
    static String textValue(List<ChatContent> content) {
        return content.stream().filter(Text.class::isInstance).map(Text.class::cast)
                .map(Text::text).collect(java.util.stream.Collectors.joining());
    }
    static String fingerprint(List<ChatContent> content) {
        StringBuilder value = new StringBuilder();
        for (var part : content) {
            String payload = part instanceof Text text ? text.text() : ((Image) part).mediaId();
            value.append(part instanceof Text ? 'T' : 'I').append(payload.length()).append(':').append(payload);
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
