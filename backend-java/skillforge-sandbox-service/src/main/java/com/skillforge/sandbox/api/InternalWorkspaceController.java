package com.skillforge.sandbox.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.execution.WorkspaceCommand;
import com.skillforge.sandbox.workspace.SessionWorkspaces;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 可信宿主使用服务间认证访问工作区；模型不能直接提供绑定身份或宿主路径。 */
@RestController
@RequestMapping("/internal/workspaces")
public class InternalWorkspaceController {
    private static final Logger log = LoggerFactory.getLogger(InternalWorkspaceController.class);
    private final SessionWorkspaces workspaces;
    private final Clock clock;

    /** 工作区事实与排他权归所属服务，入口仅校验控制请求格式和剩余时间。 */
    public InternalWorkspaceController(SessionWorkspaces workspaces, Clock clock) {
        this.workspaces = workspaces;
        this.clock = clock;
    }

    /** 文件和快照操作共用服务端回执，重复提交由工作区事实层回读原结果。 */
    @PostMapping("/operate")
    public Map<String, Object> operate(@RequestBody WorkspaceCommand command) {
        log.debug("开始内部工作区操作");
        try {
            if (command == null || command.workspace() == null || command.operationId() == null
                    || command.operationId().isBlank() || command.operationId().length() > 512
                    || command.operation() == null || command.operation().isBlank()) {
                throw new SandboxRequestException("WORKSPACE_REQUEST_INVALID", "工作区请求字段无效");
            }
            requireWorkspaceId(command.workspace().workspaceId());
            if (command.deadlineAt() == null || !clock.instant().isBefore(command.deadlineAt())) {
                throw new SandboxRequestException("WORKSPACE_DEADLINE_EXPIRED", "工作区操作已超时");
            }
            return workspaces.operate(command);
        } finally {
            log.debug("结束内部工作区操作");
        }
    }

    /** 只读查询不创建目录；不存在时返回安全404，不泄露实际磁盘路径。 */
    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestParam("workspaceId") String workspaceId) {
        log.debug("开始内部工作区状态查询");
        try {
            requireWorkspaceId(workspaceId);
            var status = workspaces.status(workspaceId);
            return status == null
                    ? ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new ApiError("WORKSPACE_NOT_FOUND", "工作区不存在"))
                    : ResponseEntity.ok(status);
        } finally {
            log.debug("结束内部工作区状态查询");
        }
    }

    /** 登记删除意图并返回是否已清理；未完成由持久后台任务继续，不绕过活动写权。 */
    @DeleteMapping("/delete")
    public Map<String, Object> delete(@RequestBody DeleteRequest request) {
        log.debug("开始内部工作区删除");
        try {
            if (request == null) {
                throw new SandboxRequestException("WORKSPACE_REQUEST_INVALID", "工作区请求字段无效");
            }
            requireWorkspaceId(request.workspaceId());
            return Map.of("success", workspaces.delete(request.workspaceId()));
        } finally {
            log.debug("结束内部工作区删除");
        }
    }

    /** 工作区目录使用受控摘要标识，不允许参数参与任意磁盘路径解析。 */
    private void requireWorkspaceId(String workspaceId) {
        if (workspaceId == null || !workspaceId.matches("[0-9a-f]{64}")) {
            throw new SandboxRequestException("WORKSPACE_REQUEST_INVALID", "工作区标识格式无效");
        }
    }

    /** 删除身份通过类型化请求体传递，保持内部控制接口的camelCase约定。 */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record DeleteRequest(String workspaceId) { }
}
