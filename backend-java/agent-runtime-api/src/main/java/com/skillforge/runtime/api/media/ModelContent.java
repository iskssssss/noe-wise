package com.skillforge.runtime.api.media;

import java.util.Objects;

/** 消息内有序的供应商无关内容，图片不字符串化。 */
public sealed interface ModelContent permits ModelContent.Text, ModelContent.Image {
    record Text(String text) implements ModelContent {
        public Text { Objects.requireNonNull(text, "text"); }
    }
    record Image(MediaRef media, ImageDetail detail) implements ModelContent {
        public Image { Objects.requireNonNull(media, "media"); Objects.requireNonNull(detail, "detail"); }
    }
}
