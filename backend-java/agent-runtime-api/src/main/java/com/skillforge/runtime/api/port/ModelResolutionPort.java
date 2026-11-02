package com.skillforge.runtime.api.port;

/**
 * 纯本地解析请求的模型策略，将宿主配置冻结为不含 endpoint、secret 或供应商选项的目标。
 * 实现不得联网、决定 Agent Policy 或修改预算。
 */
public interface ModelResolutionPort {

    /**
     * 在同一运行身份与租户绑定内解析冻结模型目标。
     *
     * @param request 运行身份、已验证身份、宿主绑定与请求策略
     * @return 冻结目标或不可重试的本地拒绝
     */
    ResolutionResult resolve(ModelResolutionRequest request);

    /** 表示一次纯本地模型解析的身份闭包、绑定和请求策略。 */
    record ModelResolutionRequest(
            RunIdentity runIdentity,
            VerifiedIdentity identity,
            ModelPort.ModelBindingRef binding,
            ModelPort.RequestedModelStrategy requestedStrategy) {

        /** 拒绝缺失值、归属身份漂移或跨租户模型绑定。 */
        public ModelResolutionRequest {
            runIdentity = PortContracts.requireNonNull(runIdentity, "runIdentity");
            identity = PortContracts.requireNonNull(identity, "identity");
            binding = PortContracts.requireNonNull(binding, "binding");
            requestedStrategy = PortContracts.requireNonNull(requestedStrategy, "requestedStrategy");
            // 解析只能消费当前调用已验证的原始运行归属，不能替换 owner。
            if (!runIdentity.owner().equals(identity.owner())) {
                throw new IllegalArgumentException(
                        "runIdentity owner must match verified identity owner");
            }
            // 宿主模型绑定必须封闭在运行 owner 的租户中。
            PortContracts.requireSameTenant(
                    runIdentity.owner().sessionRef().tenantId(),
                    binding.tenantId(),
                    "binding.tenantId");
        }
    }

    /** 封闭本地解析成功与确定性拒绝两种结果。 */
    sealed interface ResolutionResult
            permits ResolutionResult.Resolved, ResolutionResult.Rejected {

        /** 表示请求策略已解析为完整、冻结且可审计的模型目标。 */
        record Resolved(ModelPort.FrozenModelTarget target) implements ResolutionResult {

            /** 拒绝缺失的冻结模型目标。 */
            public Resolved {
                target = PortContracts.requireNonNull(target, "target");
            }
        }

        /** 表示纯本地解析被拒绝；该结果不允许声明任何自动重试方式。 */
        record Rejected(PortFailure failure) implements ResolutionResult {

            /** 拒绝缺失失败事实或非 NONE 的本地解析重试模式。 */
            public Rejected {
                failure = PortContracts.requireNonNull(failure, "failure");
                // 纯本地解析没有可安全承接的远程结果查询或协调路径。
                if (failure.retryMode() != RetryMode.NONE) {
                    throw new IllegalArgumentException(
                            "model resolution rejection retryMode must be NONE");
                }
            }
        }
    }
}
