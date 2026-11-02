package com.skillforge.institution.group;

import com.skillforge.runtime.api.port.RunIdentity;
import java.util.Objects;

/** 唯一公开投影提供正式采纳的具体final；控制模块不自行解码私有结果或提升普通Outbox文本。 */
public interface GroupDeliveryFinalMessages {
    /** 核当前执行域、同群原Run不可逆完成和准确消息版本，拒绝不存在或尚未正式采纳的final。 */
    FinalMessage requireFinal(String tenant,String integration,String group,String run,String message,long version);

    /** 正式公开正文和原Run身份；根来源始终来自identity.origin，不由生成/入队时间推断。 */
    record FinalMessage(RunIdentity identity,String messageId,long messageVersion,String content) {
        public FinalMessage { Objects.requireNonNull(identity); Objects.requireNonNull(messageId); Objects.requireNonNull(content); if(messageVersion<1)throw new IllegalArgumentException("group_final_version_invalid"); }
    }
}
