package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 每次模型准备和 Tool 执行时读取授权目录；模型身份映射不改变精确执行目标。 */
@FunctionalInterface
public interface SessionToolDirectory {
    /** Tool 发现身份与语义提示；名称仅供模型发现，不能替代 opaque handle 调用。 */
    record Discovery(String serverIdentity,String qualifiedName,String name,Optional<String> title,String description) {
        public Discovery {
            serverIdentity=text(serverIdentity);qualifiedName=text(qualifiedName);name=text(name);
            title=title==null?Optional.empty():title.map(SessionToolDirectory::text);description=text(description);
            if(!qualifiedName.equals(serverIdentity+"/"+name))throw invalid();
        }
    }

    /** 当前目录条目把可见定义与同一精确 Runtime target 交给一次模型准备。 */
    record Entry(FrozenToolTarget target,ToolBinding binding,Discovery discovery, boolean parallelSafe) {
        public Entry(FrozenToolTarget target, ToolBinding binding, Discovery discovery) {
            this(target, binding, discovery, false);
        }
        public Entry {
            Objects.requireNonNull(target);Objects.requireNonNull(binding);Objects.requireNonNull(discovery);
            if(!binding.serverIdentity().equals(discovery.serverIdentity())||!binding.toolName().equals(discovery.name()))throw invalid();
        }
    }

    List<Entry> list(SessionOwner owner, AgentId agentId);

    /**
     * 仅消费原冻结条目和精确参数，核证本次调用是否读取当前状态；默认不授予该资格。
     * 实现不得查询当前目录或执行I/O，本值不授予执行、重试或并行权限。
     */
    default boolean currentRead(Entry frozen, TypedPayload arguments) { return false; }

    /** 直连 Agent 快捷入口；团队目录必须显式指定实际执行 Agent。 */
    default List<Entry> list(SessionOwner owner) {
        return list(owner, owner.target().requireAgent());
    }

    default List<Entry> list(RunIdentity run) {
        Objects.requireNonNull(run);
        return list(run.owner(), run.agentId());
    }

    /**
     * 一次准备中批量声明 exact handle 到稳定模型身份的一一映射。
     *
     * <p>目录拥有者从冻结挂载声明身份，不得通过裁剪或替换不透明 handle 推断身份。
     * 返回值必须恰好覆盖给定条目，身份不含 Run、Input 或执行 attempt。
     * 默认实现只适用于拥有者已保证 handle 本身稳定的目录；含运行身份的目录必须覆盖本方法。
     * Session 持久化该次映射，恢复不重新查询目录；映射只服务模型投影，不授予调用权限。</p>
     */
    default Map<String, String> modelIdentities(RunIdentity run, List<Entry> entries) {
        Objects.requireNonNull(run);
        Objects.requireNonNull(entries);
        var identities = new LinkedHashMap<String, String>();
        for (var entry : entries) {
            String handle = entry.target().handle().value();
            if (identities.putIfAbsent(handle, handle) != null) throw invalid();
        }
        return requireModelIdentities(entries, identities);
    }

    /** 校验完整覆盖、精确 handle 唯一及模型身份唯一，并返回按条目顺序固定的不可变映射。 */
    static Map<String, String> requireModelIdentities(List<Entry> entries, Map<String, String> identities) {
        Objects.requireNonNull(entries);
        Objects.requireNonNull(identities);
        if (entries.size() != identities.size()) throw invalid();
        var result = new LinkedHashMap<String, String>();
        var visible = new HashSet<String>();
        for (var entry : entries) {
            String handle = entry.target().handle().value();
            String identity = text(identities.get(handle));
            if (result.putIfAbsent(handle, identity) != null || !visible.add(identity)) throw invalid();
        }
        return Collections.unmodifiableMap(result);
    }

    /** 只按模型返回的 exact handle 选择该次准备中唯一目标。 */
    static Entry requirePrepared(List<Entry> tools, String handle) {
        Objects.requireNonNull(tools);
        if (handle == null || handle.isBlank()) throw invalid();
        var matches = tools.stream()
                .filter(tool -> tool.target().handle().value().equals(handle))
                .toList();
        if (matches.size() != 1) throw invalid();
        return matches.getFirst();
    }

    /** Provider 调用前要求完整目标仍存在于当前目录；同名或同 handle 的漂移都不接受。 */
    default Entry requireCurrent(SessionOwner owner, FrozenToolTarget target) {
        return requireCurrent(owner, owner.target().requireAgent(), target);
    }

    default Entry requireCurrent(SessionOwner owner, AgentId agentId, FrozenToolTarget target) {
        Objects.requireNonNull(owner); Objects.requireNonNull(target);
        var matches = list(owner, Objects.requireNonNull(agentId)).stream()
                .filter(tool -> tool.target().equals(target)).toList();
        if (matches.size() != 1) throw invalid();
        return matches.getFirst();
    }

    default Entry requireCurrent(RunIdentity run, FrozenToolTarget target) {
        Objects.requireNonNull(run); Objects.requireNonNull(target);
        var matches = list(run).stream().filter(entry -> entry.target().equals(target)).toList();
        if (matches.size() != 1) throw invalid();
        return matches.getFirst();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("session_tool_directory_rejected");
    }

    private static String text(String value) {
        if(value==null||value.isBlank()||!value.equals(value.trim()))throw invalid();
        return value;
    }
}
