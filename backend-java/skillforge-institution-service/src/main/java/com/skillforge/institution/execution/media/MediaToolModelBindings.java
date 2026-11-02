package com.skillforge.institution.execution.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 媒体工具的受信发布版本绑定；缺省不选择模型，也不从模型参数补充配置。 */
@ConfigurationProperties("skillforge.models.media-tools")
public record MediaToolModelBindings(Binding image, Binding video, Binding transcription, Binding speech) {
    public record Binding(String modelConfigId, long publishedRevision) {
        public Binding {
            if (modelConfigId == null || !modelConfigId.matches("[1-9][0-9]*") || publishedRevision <= 0)
                throw new IllegalArgumentException("media_tool_model_binding_invalid");
        }
    }
}
