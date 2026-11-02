package com.skillforge.core.workflow.dto.request;

import java.util.Map;

/** 以 If-Match revision 追加的 Builder 草稿内容。 */
public record SaveWorkflowDraftRequest(Map<String, Object> definition) {
}
