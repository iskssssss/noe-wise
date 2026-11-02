package com.skillforge.runtime.adapter.model.protocol;

import com.skillforge.runtime.adapter.model.provider.ModelProvider;

import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.openai.chat.OpenAiChatConversationCodec;
import java.util.List;
import java.util.Map;

/** 按冻结协议选择 Conversation Codec；供应商品牌不参与选择。 */
public final class ConversationCodecs {

    private final Map<ModelProtocol, ConversationCodec> codecs;

    private ConversationCodecs(List<ConversationCodec> codecs) {
        this.codecs = codecs.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                ConversationCodec::protocol, value -> value));
    }

    public static ConversationCodecs standard() {
        return new ConversationCodecs(List.of(new OpenAiChatConversationCodec()));
    }

    public ConversationCodec require(ModelProtocol protocol) {
        ConversationCodec codec = codecs.get(protocol);
        if (codec == null) {
            throw new IllegalArgumentException("model_protocol_codec_missing");
        }
        return codec;
    }
}
