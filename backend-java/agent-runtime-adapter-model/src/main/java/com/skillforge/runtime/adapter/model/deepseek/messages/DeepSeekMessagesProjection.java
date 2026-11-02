package com.skillforge.runtime.adapter.model.deepseek.messages;

import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;

import com.skillforge.runtime.api.media.ModelContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Messages 发送与预算计量共用的累积指令投影，不修改持久消息。 */
public final class DeepSeekMessagesProjection {
    public static final String DIALECT = "deepseek-messages-v1";
    private DeepSeekMessagesProjection() { }

    /** 头部指令独立发送；历史 SYSTEM 是出现至该位置的完整快照。 */
    public record Projection(String leadingSystem, List<ModelProvider.Message> messages) {
        public Projection { messages = List.copyOf(messages); }
    }

    /** 计量和发送共用精确路由边界，其他供应商 Messages 方言不得借用本投影。 */
    public static boolean supports(String providerId, ModelProvider.InvocationProfile profile) {
        return "deepseek".equals(providerId) && "deepseek-api".equals(profile.serviceId())
                && "2026-09-13".equals(profile.profileVersion())
                && profile.protocol() == ModelProviderCatalog.ModelProtocol.ANTHROPIC_MESSAGES
                && profile.authenticationScheme() == ModelProviderCatalog.AuthenticationScheme.ANTHROPIC_API_KEY
                && DeepSeekMessagesCodec.DIALECT.equals(profile.dialectVersion());
    }

    public static Projection project(List<ModelProvider.Message> messages, com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities capabilities) {
        Objects.requireNonNull(messages); Objects.requireNonNull(capabilities);
        if (capabilities.systemUpdatesInHistory() != com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus.SUPPORTED) return leading(messages);
        var result = new ArrayList<ModelProvider.Message>();
        var system = new ArrayList<String>();
        String leading = null;
        String pending = null;
        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            if (message.role() == ModelProvider.Role.SYSTEM) {
                do {
                    if (message.contents().stream().anyMatch(part -> !(part instanceof ModelContent.Text)))
                        throw new IllegalArgumentException("model_messages_image_unsupported");
                    system.add(message.textContent());
                    if (i + 1 == messages.size() || messages.get(i + 1).role() != ModelProvider.Role.SYSTEM) break;
                    message = messages.get(++i);
                } while (true);
                String snapshot = String.join("\n\n", system);
                if (result.isEmpty()) leading = snapshot;
                else {
                    pending = snapshot;
                }
                continue;
            }
            if (emptyTurn(message)) continue;
            if (message.role() == ModelProvider.Role.ASSISTANT && pending != null) {
                if (!canAppend(result)) return leading(messages);
                append(result, pending); pending = null;
            }
            result.add(message);
        }
        // 纠正早停等宿主反馈可能紧随最后一个 assistant，协议不能在这里放历史 SYSTEM。
        // 只改变当前请求的指令位置，回退完整头部快照，不伪造用户，也不丢弃真实 assistant/tool 历史。
        if (pending != null) {
            if (!canAppend(result)) return leading(messages);
            append(result, pending);
        }
        return new Projection(leading, result);
    }

    private static Projection leading(List<ModelProvider.Message> messages) {
        var systems = new ArrayList<String>();
        var history = new ArrayList<ModelProvider.Message>();
        for (var message : messages) {
            if (message.role() == ModelProvider.Role.SYSTEM) systems.add(message.textContent());
            else if (!emptyTurn(message)) history.add(message);
        }
        return new Projection(systems.isEmpty() ? null : String.join("\n\n", systems), history);
    }

    private static boolean canAppend(List<ModelProvider.Message> messages) {
        if (messages.isEmpty()) return false;
        var role = messages.getLast().role();
        return role == ModelProvider.Role.USER || role == ModelProvider.Role.TOOL;
    }

    private static boolean emptyTurn(ModelProvider.Message message) {
        return (message.role() == ModelProvider.Role.ASSISTANT || message.role() == ModelProvider.Role.USER)
                && message.toolCalls().isEmpty() && message.reasoningContent() == null && message.protocolState().isEmpty()
                && message.contents().stream().allMatch(part -> part instanceof ModelContent.Text text && text.text().isEmpty());
    }

    private static void append(List<ModelProvider.Message> messages, String snapshot) {
        var previous = messages.getLast().role();
        if (previous != ModelProvider.Role.USER && previous != ModelProvider.Role.TOOL)
            throw new IllegalArgumentException("model_messages_system_position_invalid");
        messages.add(new ModelProvider.Message(ModelProvider.Role.SYSTEM, snapshot, null, List.of()));
    }
}
