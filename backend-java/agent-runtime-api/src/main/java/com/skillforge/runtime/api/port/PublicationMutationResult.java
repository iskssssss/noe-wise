package com.skillforge.runtime.api.port;

/** 表示领域事件 publication mutation 的封闭收敛结果。 */
public sealed interface PublicationMutationResult
        permits PublicationMutationResult.Applied,
                PublicationMutationResult.Replayed,
                PublicationMutationResult.StaleLease,
                PublicationMutationResult.NotFound,
                PublicationMutationResult.Terminal {

    /** 表示本次 mutation 首次成功。 */
    record Applied(StateEventPublication publication) implements PublicationMutationResult {
        /** 拒绝缺失 publication。 */
        public Applied {
            publication = PortContracts.requireNonNull(publication, "publication");
        }
    }

    /** 表示相同 mutation 已经成功提交。 */
    record Replayed(StateEventPublication publication) implements PublicationMutationResult {
        /** 拒绝缺失 publication。 */
        public Replayed {
            publication = PortContracts.requireNonNull(publication, "publication");
        }
    }

    /** 表示调用方持有过期 fence。 */
    record StaleLease(StateEventPublication publication, PortFailure failure)
            implements PublicationMutationResult {
        /** 拒绝缺失 publication 或稳定失败。 */
        public StaleLease {
            publication = PortContracts.requireNonNull(publication, "publication");
            failure = PortContracts.requireNonNull(failure, "failure");
        }
    }

    /** 表示目标 publication 不存在。 */
    record NotFound(PortFailure failure) implements PublicationMutationResult {
        /** 拒绝缺失稳定失败。 */
        public NotFound {
            failure = PortContracts.requireNonNull(failure, "failure");
        }
    }

    /** 表示 publication 已进入不可再变更的终态。 */
    record Terminal(StateEventPublication publication) implements PublicationMutationResult {
        /** 拒绝缺失 publication。 */
        public Terminal {
            publication = PortContracts.requireNonNull(publication, "publication");
        }
    }
}
