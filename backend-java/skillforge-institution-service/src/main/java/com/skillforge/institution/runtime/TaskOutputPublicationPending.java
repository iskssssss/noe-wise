package com.skillforge.institution.runtime;

import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 发布尚未结算是临时不可用；不能记录成不可变来源的永久格式拒绝。 */
final class TaskOutputPublicationPending extends ApiException {
    TaskOutputPublicationPending() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "TASK_OUTPUT_PUBLICATION_PENDING", "成果正在准备发布，请稍后重试。", true);
    }
}
