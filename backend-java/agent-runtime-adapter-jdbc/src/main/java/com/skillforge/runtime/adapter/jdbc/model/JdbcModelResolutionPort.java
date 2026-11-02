package com.skillforge.runtime.adapter.jdbc.model;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.ModelResolutionPort;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.RetryMode;
import java.util.Objects;
import java.util.function.Predicate;

/** 当前身份授权后只解析精确绑定；不解密、不联网、不重选策略。 */
public final class JdbcModelResolutionPort implements ModelResolutionPort {
    private final JdbcModelBindings bindings;
    private final Predicate<ModelResolutionRequest> authorizer;
    public JdbcModelResolutionPort(JdbcModelBindings bindings, Predicate<ModelResolutionRequest> mandatoryAuthorizer) {
        this.bindings = Objects.requireNonNull(bindings); this.authorizer = Objects.requireNonNull(mandatoryAuthorizer);
    }
    @Override public ResolutionResult resolve(ModelResolutionRequest request) {
        Objects.requireNonNull(request);
        try {
            if (!authorizer.test(request)) return rejected("model_resolution_denied");
            var binding = bindings.findForOwner(request.binding(), request.runIdentity().agentId());
            if (binding.isEmpty() || !binding.orElseThrow().strategy().equals(request.requestedStrategy()))
                return rejected("model_binding_unavailable");
            return new ResolutionResult.Resolved(binding.orElseThrow().target());
        } catch (RuntimeException failure) { return rejected("model_resolution_unavailable"); }
    }
    private static ResolutionResult rejected(String reason) {
        return new ResolutionResult.Rejected(new PortFailure(reason, RetryMode.NONE, new ResultRef(reason)));
    }
}
