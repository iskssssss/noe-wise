package com.skillforge.institution.team;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.core.shared.exception.ApiException;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.institution.common.TenantIds;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 机构团队配置及发布入口；执行状态通过会话工作投影读取。 */
@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {
    public record Member(String agentId) { }
    public record SaveRequest(String name, String description, String status, long revision,
            List<Member> members, String primaryAgentId) {
        /** 只将本请求构造定义时的输入拒绝转换为 400，不拦截后续服务或持久化异常。 */
        TeamDefinition definition() {
            if (members == null || members.stream().anyMatch(Objects::isNull)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "团队成员不能为空");
            }
            try {
                return new TeamDefinition(null, name, description, status, revision,
                        members.stream().map(member -> new TeamDefinition.Member(member.agentId(), "", "", "", List.of())).toList(),
                        primaryAgentId);
            } catch (IllegalArgumentException invalidInput) {
                throw new ApiException(HttpStatus.BAD_REQUEST, invalidInput.getMessage(), invalidInput);
            }
        }
    }
    private final TeamService teams;
    public TeamController(TeamService teams) { this.teams = teams; }

    @RequirePermission("foundation.teams.read")
    @GetMapping
    public ApiResponse<List<TeamDefinition>> list() { return ApiResponses.wrap(teams.list(TenantIds.requireTenantId())); }

    @RequirePermission("foundation.teams.read")
    @GetMapping("/revisions")
    public ApiResponse<List<TeamDefinition>> revisions(@RequestParam String id) {
        return ApiResponses.wrap(teams.revisions(TenantIds.requireTenantId(), id));
    }

    @RequirePermission("foundation.teams.create")
    @PostMapping
    public ApiResponse<TeamDefinition> create(@RequestBody SaveRequest request) {
        return ApiResponses.wrap(teams.save(TenantIds.requireTenantId(), null, request.definition()));
    }

    @RequirePermission("foundation.teams.update")
    @PutMapping("/update")
    public ApiResponse<TeamDefinition> update(@RequestBody UpdateCommand command) {
        String id = command.id();
        SaveRequest request = command.payload();
        return ApiResponses.wrap(teams.save(TenantIds.requireTenantId(), id, request.definition()));
    }

    @RequirePermission("foundation.teams.publish")
    @PostMapping("/publish")
    public ApiResponse<TeamDefinition> publish(@RequestBody PublishCommand command) { return ApiResponses.wrap(teams.publish(TenantIds.requireTenantId(), command.id())); }

    public record UpdateCommand(String id, SaveRequest payload) { }
    public record PublishCommand(String id) { }
}
