package com.skillforge.runtime.starter;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.List;
import java.util.Objects;

/** 只按已持久化Run的原始来源选择唯一宿主；未知来源或重叠注册均拒绝执行。 */
public final class ExecutionHostRegistry {
    private final List<Host> hosts;
    public ExecutionHostRegistry(List<Host> hosts) {
        this.hosts = List.copyOf(hosts);
        if (this.hosts.isEmpty() || this.hosts.stream().map(Host::id).distinct().count() != this.hosts.size()
                || this.hosts.stream().anyMatch(host -> host.id() == null || host.id().isBlank()))
            throw new IllegalArgumentException("execution_host_registration_invalid");
    }
    /** 这些Store共享同一权威typed表；这里只通过一个只读入口发现地址，不从任一Host推断写权。 */
    public ExecutionStore store() { return hosts.getFirst().store(); }
    public Host host(RunRef reference) { return host(store().load(Objects.requireNonNull(reference)).orElseThrow(() -> new IllegalArgumentException("execution_host_run_missing"))); }
    public Host host(ExecutionStore.Run persisted) {
        var owners = hosts.stream().filter(host -> host.owns(persisted)).toList();
        if (owners.size() != 1) throw new IllegalArgumentException(owners.isEmpty() ? "execution_host_origin_unknown" : "execution_host_origin_ambiguous");
        return owners.getFirst();
    }
    /** 单租户恢复预算在宿主间分配，所有恢复共用Worker的同一启动/五秒窗口。 */
    public void recover(TenantId tenant, int limit) {
        int remaining = limit;
        for (int index = 0; index < hosts.size() && remaining > 0; index++) {
            int share = Math.max(1, remaining / (hosts.size() - index));
            hosts.get(index).recover(tenant, share); remaining -= share;
        }
    }
    /** frame、当前权限和控制租约始终属于已选Host；Host不能创建执行线程池或第二Run队列。 */
    public interface Host {
        String id();
        boolean owns(ExecutionStore.Run persisted);
        ExecutionStore store();
        void begin(RunRef run);
        void end(RunRef run);
        ExecutionCoordinator.Outcome drive(ExecutionStore.Claim claim);
        void committed(ExecutionStore.Run run);
        void recover(TenantId tenant, int limit);
        void cancelled(RunRef run);
        /** 宿主必须核已提交ProgramChild的完整持久来源；其他宿主默认拒绝inline能力。 */
        default void requireProgramChild(ExecutionStore.Run parent, ExecutionStore.ProgramChildSource source, ExecutionStore.Run child) {
            throw new IllegalArgumentException("execution_inline_source_unsupported");
        }
    }
}
