package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.RunIdentity;
import java.util.*;

/** 唯一冻结模型配置契约；请求与结果由typed ExecutionStore拥有，不保留旧准备来源树。 */
public interface SessionPreparations {
    String PROFILE_VERSION="skillforge.runtime.session.preparation-profile.v4";
    String AGENT_INPUT_RENDERER_VERSION="input-log.v45";
    String WORKFLOW_RENDERER_VERSION="workflow-node.v3";
    /** 只标识一次模型上下文用途；不携带旧准备、checkpoint或发送许可。 */
    record Key(RunIdentity identity,String loopId,int iteration) {
        public Key {Objects.requireNonNull(identity);text(loopId);if(iteration<=0)throw invalid();}
        @Override public String toString(){return "SessionPreparationKey[REDACTED]";}
    }
    /** 受信配置明确输入渲染、模型解析和预算；变更配置使用新ref，不覆盖历史准备策略。 */
    record Profile(ResultRef reference, String version, String rendererVersion, SessionAdmissionFacts.ContextKind contextKind, SessionPrompt systemPrompt,
                   ModelBindingRef binding, RequestedModelStrategy strategy, ResponseContract response,
                   GenerationOptions generation, ReasoningPolicy reasoning, PortalCallKind callKind,
                   ResultRef tokenizerProfileRef, SessionContextPolicy contextPolicy,
                   long maxTokens, long maxCharacters, int maxHistoryEvents,
                   boolean delegationEnabled, Set<InboundKind> allowedInputs) {
        public Profile {
            Objects.requireNonNull(reference); text(version); text(rendererVersion); Objects.requireNonNull(contextKind); Objects.requireNonNull(systemPrompt);
            Objects.requireNonNull(binding); Objects.requireNonNull(strategy); Objects.requireNonNull(response);
            Objects.requireNonNull(generation); Objects.requireNonNull(reasoning); Objects.requireNonNull(callKind);
            Objects.requireNonNull(tokenizerProfileRef); Objects.requireNonNull(contextPolicy);
            allowedInputs = Set.copyOf(allowedInputs);
            if (!PROFILE_VERSION.equals(version) || maxTokens <= 0 || maxCharacters <= 0 || maxHistoryEvents < 0
                    || contextPolicy.immediateToolTokens() >= maxTokens
                    || callKind != PortalCallKind.MODEL_GENERATE && callKind != PortalCallKind.MODEL_STREAM
                    || contextKind == SessionAdmissionFacts.ContextKind.RESTRICTED_WORKFLOW
                        && (!allowedInputs.isEmpty() || maxHistoryEvents != 0 || delegationEnabled)) throw invalid();
        }
        @Override public String toString() { return "SessionPreparationProfile[REDACTED]"; }
    }

    Optional<Profile> profile(ResultRef reference);
    private static void text(String value){if(value==null||value.isBlank())throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("session_preparation_profile_invalid");}
}
