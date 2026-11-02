package com.skillforge.execution;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;

/** 文件及检查点操作的内部控制请求；路径只相对持久项目目录。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record WorkspaceCommand(String operationId, WorkspaceBinding workspace, String operation,
        String path, String targetPath, String content, Long expectedRevision, String expectedSha256,
        Integer offset, Integer limit, String snapshotId, Instant deadlineAt) { }
