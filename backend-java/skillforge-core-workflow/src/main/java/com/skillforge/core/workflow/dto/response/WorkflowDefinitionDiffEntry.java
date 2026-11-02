package com.skillforge.core.workflow.dto.response;

/** 草稿相对当前 active DefinitionVersion 的一条确定性结构差异。 */
public record WorkflowDefinitionDiffEntry(
        String operation,
        String path,
        Object beforeValue,
        Object afterValue) {
}
