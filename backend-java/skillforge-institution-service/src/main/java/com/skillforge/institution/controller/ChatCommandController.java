package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.institution.service.ChatChannelAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 两个聊天入口共用的命令目录；只描述实际可用的聊天与控制操作，不创建 Session。 */
@RestController
public class ChatCommandController {
    private final ChatChannelAccess access;

    public ChatCommandController(ChatChannelAccess access) { this.access = access; }

    @Operation(summary = "读取智能体支持的聊天命令", description = "只读，不创建会话或执行；支持机构登录、渠道令牌或渠道 OpenAPI 签名")
    @GetMapping("/api/v1/chat/commands")
    public ApiResponse<Catalog> commands(
            @Parameter(description = "已发布的智能体 ID 或 key；渠道调用还须绑定当前渠道", required = true)
            @RequestParam(value="agent_id", required=false) String agentId,
            @RequestParam(value="team_id", required=false) String teamId,
            @RequestParam(value="end_user_id", required=false) String endUserId) {
        if (teamId != null && !teamId.isBlank()) {
            if (agentId != null && !agentId.isBlank()) throw new com.skillforge.core.shared.exception.ApiException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "请选择一个智能体或团队");
            access.requireCommandTeam(teamId, endUserId);
            return ApiResponses.wrap(new Catalog(List.of(new ChatCommand("/plan", "开启计划模式", "主助手先给出可审查计划，批准后继续执行"),
                    new ChatCommand("/plan off", "关闭计划模式", "后续消息由主助手直接处理并按需委派"))));
        }
        access.requireCommandAgent(agentId);
        return ApiResponses.wrap(new Catalog(List.of(
                new ChatCommand("/plan", "开启计划模式", "先规划再执行，也可在命令后继续输入任务内容"),
                new ChatCommand("/plan off", "关闭计划模式", "切回普通对话模式"),
                new ChatCommand("/goal", "设置持续目标", "在命令后输入明确目标；默认最多续行 3 轮，可用 --rounds 1~20 指定"),
                new ChatCommand("/goal status", "查看目标", "读取当前目标、进度和续行状态"),
                new ChatCommand("/goal pause", "暂停目标", "暂停后续轮次，并请求停止当前目标执行"),
                new ChatCommand("/goal resume", "恢复目标", "显式恢复暂停或服务重启后的目标"),
                new ChatCommand("/delivery", "查看送达状态", "按需读取当前执行的最新结果送达状态"))));
    }

    public record ChatCommand(String command, String title, String description) { }
    public record Catalog(List<ChatCommand> items) { }
}
