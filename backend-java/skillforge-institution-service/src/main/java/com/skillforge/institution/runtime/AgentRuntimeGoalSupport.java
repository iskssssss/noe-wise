package com.skillforge.institution.runtime;

import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.CapabilityMountSnapshot;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.tool.ToolCatalog;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 持续目标依赖已授权的读取与更新工具；可用性查询不发布绑定或补授工具。 */
@Service
public final class AgentRuntimeGoalSupport {
    private static final Set<String> REQUIRED = Set.of("get_goal", "update_goal");
    private static final String REASON = "当前智能体未配置完整的目标管理能力，暂不能创建或继续持续目标。请联系机构管理员添加“查看任务目标”和“更新任务目标”工具并发布；也可以使用普通对话。";
    private final AgentPublicationService publications;
    private final CapabilityMountResolver mounts;
    private final ToolCatalog tools;

    public AgentRuntimeGoalSupport(AgentPublicationService publications, CapabilityMountResolver mounts, ToolCatalog tools) {
        this.publications = publications; this.mounts = mounts; this.tools = tools;
    }

    public Availability read(long tenant, long agent) {
        var published = publications.requirePublished(tenant, agent).mountSet();
        var selected = published.mounts().stream().filter(CapabilityMount::active)
                .filter(mount -> mount.capability().type() == CapabilityType.TOOL).toList();
        String input = "goal-support:" + published.generation();
        var snapshot = mounts.restore(new CapabilityMountSnapshot(CapabilityMountSnapshot.snapshotIdForInput(input),
                input, tenant, agent, published.generation(), selected, Instant.now()));
        var names = tools.list(snapshot).stream()
                .filter(entry -> entry.target().version().metadata().invocationPolicy().modelInvocable())
                .filter(entry -> entry.target().deployment().providerType() == ToolDeployment.ProviderType.PLATFORM)
                .filter(entry -> entry.target().deployment().providerRef().equals(entry.definition().name()))
                .map(entry -> entry.definition().name()).collect(Collectors.toSet());
        return new Availability(names.containsAll(REQUIRED), names.containsAll(REQUIRED) ? null : REASON);
    }

    /** 首轮及续轮使用本次实际冻结目录，先拒绝再受理，历史重放不重查当前挂载。 */
    static void require(List<SessionToolDirectory.Entry> entries) {
        var names = entries.stream().filter(entry -> entry.target().modelInvocable())
                .filter(entry -> entry.binding().providerType() == ToolBinding.ProviderType.PLATFORM)
                .filter(entry -> entry.binding().providerRef().equals(entry.binding().toolName()))
                .map(entry -> entry.binding().toolName()).collect(Collectors.toSet());
        if (!names.containsAll(REQUIRED))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "goal_tools_unavailable", REASON, false);
    }

    public record Availability(boolean available, String reason) { }

    public void require(long tenant, long agent) {
        var support = read(tenant, agent);
        if (!support.available())
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "goal_tools_unavailable", support.reason(), false);
    }
}
