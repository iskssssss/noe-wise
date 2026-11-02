package com.skillforge.runtime.session;

import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import java.util.List;
import java.util.Objects;

/** 模型准备只替换明确位置的图片引用；恢复时以纯校验对应原历史，不重新准备媒体。 */
public final class AgentExecutionModelMedia {
    private AgentExecutionModelMedia() { }

    /** 同一原消息内容位置的实际来源和已准备模型变体；不重复保存原完整上下文。 */
    public record MediaTransformation(int messageIndex, int contentIndex, MediaRef source, MediaRef prepared) {
        public MediaTransformation {
            Objects.requireNonNull(source, "source"); Objects.requireNonNull(prepared, "prepared");
            if (messageIndex < 0 || contentIndex < 0 || !source.owner().equals(prepared.owner())) throw invalid();
        }
    }

    /** 实际待计量并发送的消息及按消息/内容位置升序保存的图片转换。 */
    public record Prepared(List<ModelMessage> messages, List<MediaTransformation> transformations) {
        public Prepared {
            messages = List.copyOf(messages); transformations = List.copyOf(transformations);
            int transformationIndex = 0;
            for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
                var message = messages.get(messageIndex);
                for (int contentIndex = 0; contentIndex < message.contents().size(); contentIndex++) {
                    if (!(message.contents().get(contentIndex) instanceof ModelContent.Image image)) continue;
                    if (transformationIndex >= transformations.size()) throw invalid();
                    var transformation = transformations.get(transformationIndex++);
                    if (transformation.messageIndex() != messageIndex || transformation.contentIndex() != contentIndex
                            || !transformation.prepared().equals(image.media())) throw invalid();
                }
            }
            if (transformationIndex != transformations.size()) throw invalid();
        }

        /** 除显式映射的图片引用外，正文、顺序、角色、消息身份和原生工具配对必须逐项相同。 */
        public void requireCorrespondence(List<ModelMessage> originals) {
            Objects.requireNonNull(originals, "originals");
            if (originals.size() != messages.size()) throw invalid();
            int transformationIndex = 0;
            for (int messageIndex = 0; messageIndex < originals.size(); messageIndex++) {
                var original = Objects.requireNonNull(originals.get(messageIndex)); var prepared = messages.get(messageIndex);
                if (!original.messageId().equals(prepared.messageId()) || original.role() != prepared.role()
                        || !original.toolCalls().equals(prepared.toolCalls()) || !original.toolCallId().equals(prepared.toolCallId())
                        || !original.reasoningContent().equals(prepared.reasoningContent())
                        || !original.protocolState().equals(prepared.protocolState())
                        || original.contents().size() != prepared.contents().size()) throw invalid();
                for (int contentIndex = 0; contentIndex < original.contents().size(); contentIndex++) {
                    var source = original.contents().get(contentIndex); var target = prepared.contents().get(contentIndex);
                    if (source instanceof ModelContent.Image sourceImage) {
                        if (!(target instanceof ModelContent.Image targetImage) || sourceImage.detail() != targetImage.detail()
                                || transformationIndex >= transformations.size()) throw invalid();
                        var transformation = transformations.get(transformationIndex++);
                        if (transformation.messageIndex() != messageIndex || transformation.contentIndex() != contentIndex
                                || !transformation.source().equals(sourceImage.media())
                                || !transformation.prepared().equals(targetImage.media())) throw invalid();
                    } else if (!source.equals(target)) throw invalid();
                }
            }
            if (transformationIndex != transformations.size()) throw invalid();
        }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_model_media_mismatch"); }
}
