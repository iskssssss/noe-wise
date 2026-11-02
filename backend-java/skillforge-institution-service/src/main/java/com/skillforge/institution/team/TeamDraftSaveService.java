package com.skillforge.institution.team;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 草稿与原请求的窄回执同事务提交，查询不重放写入。 */
@Service
public class TeamDraftSaveService {
    private final FoundationAccess access;
    private final TeamService teams;
    private final TeamDraftReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;

    public TeamDraftSaveService(FoundationAccess access, TeamService teams, TeamDraftReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context) {
        this.access = access; this.teams = teams; this.receipts = receipts; this.context = context;
    }

    @Transactional
    public Receipt save(long tenant, Command command) {
        if (command == null || command.requestId() == null || command.payload() == null ||
                (command.id() != null && command.id().isBlank()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "团队保存请求无效");
        access.require(command.id() == null ? "foundation.teams.create" : "foundation.teams.update");
        String actor = member(tenant);
        context.bindTenant(tenant);
        var team = teams.save(Long.toString(tenant), command.id(), command.payload().definition());
        if (receipts.insert(tenant, command.requestId().toString(), actor, team.id(), team.revision()) != 1)
            throw new IllegalStateException("团队保存回执写入失败");
        return new Receipt(command.requestId(), team.id(), team.revision());
    }

    @Transactional(readOnly = true)
    public Receipt result(long tenant, UUID requestId) {
        access.require("foundation.teams.read");
        String actor = member(tenant);
        context.bindTenant(tenant);
        var row = receipts.find(tenant, requestId.toString(), actor);
        return row == null ? null : new Receipt(requestId, row.teamId(), row.revision());
    }

    private String member(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        return subject.memberId();
    }
    public record Command(@JsonProperty("request_id") UUID requestId, String id, TeamController.SaveRequest payload) { }
    public record Receipt(@JsonProperty("request_id") UUID requestId, @JsonProperty("team_id") String teamId, long revision) { }
}
