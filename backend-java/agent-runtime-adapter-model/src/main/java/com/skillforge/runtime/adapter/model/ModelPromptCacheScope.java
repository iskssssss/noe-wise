package com.skillforge.runtime.adapter.model;

import com.skillforge.runtime.adapter.model.provider.ModelProvider.PromptCacheScope;
import com.skillforge.runtime.api.port.RunIdentity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 缓存路由只绑定会话和冻结模型，不含轮次身份、正文或凭据，也不替代当前授权。 */
final class ModelPromptCacheScope {
    private ModelPromptCacheScope() { }

    static PromptCacheScope create(RunIdentity identity, OpenAiModelPort.Binding binding) {
        var owner = identity.owner();
        var target = binding.target();
        var model = target.binding();
        if (!owner.sessionRef().tenantId().equals(model.tenantId())) {
            throw new IllegalArgumentException("model_prompt_cache_tenant_mismatch");
        }
        var profile = binding.providerProfile();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var field : new String[] {
                    "skillforge.prompt-cache-scope.v2", model.tenantId().value(),
                    owner.sessionRef().sessionId(), owner.target().kind().name(), owner.target().id(),
                    switch (owner.subject()) {
                        case com.skillforge.runtime.api.SessionSubject.User user -> "USER:" + user.endUserId().value();
                        case com.skillforge.runtime.api.SessionSubject.Group group -> "GROUP:" + group.groupBindingRef().groupBindingId();
                    }, identity.agentId().value(),
                    model.bindingId(), Long.toString(model.revision()), target.effectiveModelId(),
                    profile.providerId(), profile.serviceId(), profile.profileVersion(),
                    profile.protocol().id(), profile.authenticationScheme().id(), profile.dialectVersion(),
                    binding.endpoint() }) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                // 长度编码使合法标识中的分隔符不会产生不同身份的同一输入串。
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return new PromptCacheScope(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
