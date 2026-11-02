package com.skillforge.institution.chat;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.team.TeamDefinition;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 用户端工作室维护只接收成员引用与主助手设置，不接收系统提示词、挂载或成员版本。 */
@RestController
@RequestMapping("/api/v1/chat/teams")
public class ChatTeamController {
    private final ChatTeamService teams;
    public ChatTeamController(ChatTeamService teams) { this.teams = teams; }
    public record Member(String agentId) { }
    public record SaveRequest(String clientRef, String id, long revision, String name, String description,
                              List<Member> members, String primaryAgentId) { }
    public record DisableRequest(String clientRef, String id, long revision) { }
    @GetMapping public ApiResponse<List<TeamDefinition>> list(@RequestParam("client_ref") String clientRef) { return ApiResponses.wrap(teams.list(clientRef)); }
    @PutMapping public ApiResponse<TeamDefinition> save(@RequestBody SaveRequest body) {
        TeamDefinition definition;
        try {
            definition = new TeamDefinition(body.id(), body.name(), body.description(), "draft", body.revision(),
                    body.members().stream().map(member -> new TeamDefinition.Member(member.agentId(), "", "", "", List.of())).toList(),
                    body.primaryAgentId());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "请检查工作室名称、成员和主助手");
        }
        return ApiResponses.wrap(teams.save(body.clientRef(), definition));
    }
    @PostMapping("/disable") public ApiResponse<TeamDefinition> disable(@RequestBody DisableRequest body) {
        return ApiResponses.wrap(teams.disable(body.clientRef(), body.id(), body.revision()));
    }
}
