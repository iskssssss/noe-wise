package com.skillforge.core.capability.tool;

import com.skillforge.core.capability.CapabilityMountSnapshot;
import com.skillforge.core.capability.CapabilityType;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** 默认冻结 Tool 目录；不可用、停用或不匹配的 mount 失败关闭且不进入结果。 */
public final class DefaultToolCatalog implements ToolCatalog {

    private static final Comparator<Entry> STABLE_ORDER = Comparator
            .comparing(Entry::serverIdentity)
            .thenComparing(entry -> entry.definition().name())
            .thenComparing(Entry::handle);

    private final ToolResolver resolver;

    public DefaultToolCatalog(ToolResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public List<Entry> list(CapabilityMountSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.mounts().stream()
                .filter(mount -> mount.capability().type() == CapabilityType.TOOL)
                .map(mount -> resolve(snapshot, mount.handle()))
                .flatMap(Optional::stream)
                .sorted(STABLE_ORDER)
                .toList();
    }

    @Override
    public Optional<Entry> resolve(
            CapabilityMountSnapshot snapshot, String toolHandle) {
        if (snapshot == null || toolHandle == null || toolHandle.isBlank()) {
            return Optional.empty();
        }
        ToolResolver.Resolution resolution = resolver.resolve(toolHandle, snapshot);
        if (!(resolution instanceof ToolResolver.Resolved resolved)) {
            return Optional.empty();
        }
        if (!resolved.version().metadata().invocationPolicy().modelInvocable()) {
            return Optional.empty();
        }
        String serverIdentity = resolved.deployment().providerType().name()
                .toLowerCase(Locale.ROOT) + ":" + resolved.deployment().providerRef();
        return Optional.of(new Entry(
                serverIdentity,
                serverIdentity + "/" + resolved.version().definition().name(),
                resolved));
    }
}
