package com.skillforge.institution.artifact;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 把 Agent 委派子 Run 的独立 Session 投影回用户产品会话。
 *
 * <p>该投影只服务用户展示和文件访问；Agent 工具仍以自身 Session 为唯一可见范围。</p>
 */
@Service
public class StudioArtifactProjection {
    private static final String CHILD_SESSION_PREFIX = "delegation:";
    private final StudioArtifactProjectionStore store;
    private final AgentRunBindings bindings;

    public StudioArtifactProjection(StudioArtifactProjectionStore store, AgentRunBindings bindings) {
        this.store = Objects.requireNonNull(store);
        this.bindings = Objects.requireNonNull(bindings);
    }

    /** 返回产品会话及当前仍有权披露成果的来源 Agent 子会话。 */
    public List<String> visibleSessions(String tenant, String productSession) {
        requireText(tenant);
        requireText(productSession);
        var sessions = new LinkedHashSet<String>();
        sessions.add(productSession);
        List<StudioArtifactProjectionMapper.Row> rows = store.children(tenant, productSession);
        for (var row : rows) {
            require(row, productSession);
            try {
                requireReadable(tenant, row);
                sessions.add(row.childSessionId);
            } catch (SecurityException | ApiException revoked) {
                // 已撤销来源 Agent 的历史成果不再进入用户列表；其他存储异常继续向上失败。
            }
        }
        return List.copyOf(sessions);
    }

    /** 按 artifact 的精确 child Run 解析产品会话，并重新检查来源 Agent。 */
    public Optional<Route> route(String tenant, String childSession, String childRun) {
        requireText(tenant);
        requireText(childSession);
        requireText(childRun);
        if (!childSession.startsWith(CHILD_SESSION_PREFIX)) {
            return Optional.empty();
        }
        var stored = store.find(tenant, childSession, childRun);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        var row = stored.orElseThrow();
        require(row, row.productSessionId);
        try {
            requireReadable(tenant, row);
        } catch (SecurityException | ApiException revoked) {
            throw ArtifactStorage.unavailable();
        }
        return Optional.of(new Route(row.productSessionId, row.originTargetKind,
                row.originTargetId, row.sourceAgentId, row.parentRunId));
    }

    /** 使用不可变 Run 绑定复核父子关系，当前授权只能收窄已冻结的可读范围。 */
    private void requireReadable(String tenant, StudioArtifactProjectionMapper.Row row) {
        TenantId tenantId = new TenantId(tenant);
        RunRef parentRun = new RunRef(tenantId, row.parentRunId);
        AgentRunBindings.Binding parent = bindings.find(parentRun)
                .orElseThrow(() -> new SecurityException("artifact_projection_parent_missing"));
        AgentRunBindings.Binding child = bindings.find(new RunRef(tenantId, row.childRunId))
                .orElseThrow(() -> new SecurityException("artifact_projection_child_missing"));
        if (parent.role() != AgentRunBindings.Role.PRIMARY
                || !parent.owner().sessionRef().sessionId().equals(row.productSessionId)
                || parent.owner().requireUser().value() != row.endUserId
                || !parent.owner().target().kind().name().equals(row.originTargetKind)
                || !parent.owner().target().id().equals(row.originTargetId)
                || !parent.scope().primaryRun().equals(parentRun)
                || child.role() != AgentRunBindings.Role.DELEGATE
                || !child.owner().sessionRef().sessionId().equals(row.childSessionId)
                || child.owner().requireUser().value() != row.endUserId
                || !child.actor().value().equals(row.sourceAgentId)
                || !child.parentRunRef().equals(Optional.of(parentRun))
                || !child.scope().primaryRun().equals(parentRun)) {
            throw new SecurityException("artifact_projection_binding_invalid");
        }
        bindings.authorizeRead(parent, row.sourceAgentId);
    }

    private static void require(StudioArtifactProjectionMapper.Row row, String productSession) {
        Objects.requireNonNull(row, "studio artifact projection row");
        if (!text(row.childSessionId) || !row.childSessionId.startsWith(CHILD_SESSION_PREFIX)
                || !text(row.childRunId) || !text(row.parentRunId) || !text(row.productSessionId)
                || !row.productSessionId.equals(productSession) || !text(row.originTargetKind)
                || !text(row.originTargetId)
                || !text(row.sourceAgentId) || row.endUserId <= 0) {
            throw new IllegalStateException("studio_artifact_projection_invalid");
        }
    }

    private static void requireText(String value) {
        if (!text(value)) {
            throw new IllegalArgumentException("studio_artifact_projection_identity_required");
        }
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    public record Route(String productSession, String originTargetKind, String originTargetId,
                        String sourceAgentId, String parentRunId) {
        public Route {
            requireText(productSession);
            requireText(originTargetKind);
            requireText(originTargetId);
            requireText(sourceAgentId);
            requireText(parentRunId);
        }
    }
}
