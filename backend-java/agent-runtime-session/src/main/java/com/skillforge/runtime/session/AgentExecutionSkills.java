package com.skillforge.runtime.session;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.api.port.NativeAgentTools;
import com.skillforge.runtime.core.SkillActivation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 已冻结Skill包的纯上下文计算；与实际模型结果一起提交，不创建Provider Attempt或独立journal。
 * 本类不读取宿主路径，也不把Skill资源或允许工具清单当作新的执行授权。
 */
public final class AgentExecutionSkills {
    private final List<SkillActivation.Mount> mounts;
    private final Set<CapabilityRef> available;
    private final AgentExecutionCodec codec;

    /** 原目录由准入不可变材料拥有，调用者不得从恢复时的当前发布指针构造。 */
    public AgentExecutionSkills(List<SkillActivation.Mount> mounts, Set<CapabilityRef> available, CodecLimits limits) {
        this.mounts = List.copyOf(mounts); this.available = Set.copyOf(available);
        codec = new AgentExecutionCodec(Objects.requireNonNull(limits));
        SkillActivation.initial(this.mounts, this.available, new SkillActivation.State(List.of()));
    }

    /** ALWAYS激活使用同一冻结目录及其冲突、依赖和包预算规则，拒绝项不注入正文。 */
    public State initial() {
        var active = SkillActivation.initial(mounts, available, new SkillActivation.State(List.of())).state();
        var loaded = active.activeHandles().stream().map(handle -> {
            var resource = mount(handle).descriptor().entrypoint();
            return new LoadedResource(handle, resource.path(), resource.sha256());
        }).toList();
        return new State(active, loaded);
    }

    /** 初次模型准备注入实际ALWAYS入口，恢复只在初始上下文重建时调用一次。 */
    public List<ModelContent> initialContents(State state) {
        requireState(state);
        return state.activation().activeHandles().stream().map(handle -> {
            var mount = mount(handle); var root = feedback(handle, "ACTIVATED", mount);
            resource(root, mount.descriptor().entrypoint());
            return (ModelContent) new ModelContent.Text(root.toString());
        }).toList();
    }

    /**
     * 原模型已决定的按需激活或资源读取；候选状态仅在模型complete提交成功后采用。
     * 失败反馈保留原原因，不能回退到文件系统、另一版本或未激活的包。
     */
    public Transition apply(State before, AgentAction action) {
        requireState(before); Objects.requireNonNull(action);
        var arguments = AgentExecutionCodec.object();
        final String operation; final State state; final StringBuilder response;
        if (action instanceof AgentAction.ActivateSkill activate) {
            operation = "activate_skill"; arguments.put("skill_handle", activate.skillHandle());
            var result = SkillActivation.activate(activate.skillHandle(), mounts, available, before.activation());
            var selected = mounts.stream().filter(mount -> mount.handle().equals(result.handle())).findFirst();
            response = feedback(result.handle(), result.status().name(), selected.orElse(null));
            var loaded = new ArrayList<>(before.loaded());
            if (result.status() == SkillActivation.Status.ACTIVATED) {
                var entrypoint = selected.orElseThrow().descriptor().entrypoint();
                resource(response, entrypoint);
                addLoaded(loaded, new LoadedResource(result.handle(), entrypoint.path(), entrypoint.sha256()));
            }
            state = new State(result.state(), loaded);
        } else if (action instanceof AgentAction.ReadSkillResource read) {
            operation = "read_skill_resource"; arguments.put("skill_handle", read.skillHandle()); arguments.put("path", read.path());
            var result = SkillActivation.readResource(read.skillHandle(), read.path(), mounts, available, before.activation());
            var selected = mounts.stream().filter(mount -> mount.handle().equals(result.handle())).findFirst();
            response = feedback(result.handle(), result.status().name(), selected.orElse(null)); response.append("Requested path: ").append(result.path()).append("\n");
            var loaded = new ArrayList<>(before.loaded());
            result.resource().ifPresent(value -> {
                resource(response, value);
                addLoaded(loaded, new LoadedResource(result.handle(), value.path(), value.sha256()));
            });
            state = new State(result.state(), loaded);
        } else throw new IllegalArgumentException("execution_skill_action_required");
        return new Transition(state, codec.encode("application/json", arguments),
                NativeAgentTools.wireName(operation, Optional.empty()),
                List.of(new ModelContent.Text(response.toString())));
    }

    /** 反馈索引只来自原冻结包；路径描述包内资源，不暗示宿主挂载或脚本依赖已经就绪。 */
    private static StringBuilder feedback(String handle, String status, SkillActivation.Mount selected) {
        var result = new StringBuilder("## Skill result\nSkill handle: ").append(handle)
                .append("\nStatus: ").append(status).append("\n");
        result.append(switch (status) {
            case "ACTIVATED" -> "The skill is active. Follow the entry instructions below.\n";
            case "ALREADY_ACTIVE" -> "The skill is already active; its entry instructions were loaded earlier. Read a listed reference only when needed.\n";
            case "LOADED" -> "The requested frozen resource is loaded below.\n";
            case "NOT_FOUND" -> "Reason: this handle is not in the frozen skill catalog. Next: select an exact handle from the available skills.\n";
            case "MODE_MISMATCH" -> "Reason: this skill is not activated on demand. Next: use the active skill instructions already provided, or choose an on-demand skill.\n";
            case "NOT_INVOCABLE" -> "Reason: this skill does not allow model activation. Next: choose a model-invocable skill or explain the limitation to the user.\n";
            case "DEPENDENCY_MISSING" -> "Reason: a required capability is unavailable in this run. Next: use available capabilities or ask for the missing capability to be configured for a new run.\n";
            case "CONFLICT" -> "Reason: an active skill occupies the same conflict group. Next: continue with the active skill; another activation cannot replace it in this run.\n";
            case "BUDGET_EXCEEDED" -> "Reason: the skill or resource exceeds its frozen package token budget. Next: use already loaded instructions or a smaller listed resource; repeating this request will not increase the budget.\n";
            case "SKILL_NOT_ACTIVE" -> "Reason: the handle is unknown or the skill is not active. Next: select an available on-demand skill handle and activate it before reading its resources.\n";
            case "RESOURCE_NOT_FOUND" -> "Reason: the requested path is not a readable reference in this frozen package; SKILL.md is supplied during activation. Next: choose an exact reference path from the resource index below.\n";
            default -> throw new IllegalArgumentException("execution_skill_feedback_status_invalid");
        });
        if (selected != null && Set.of("ACTIVATED", "ALREADY_ACTIVE", "LOADED", "RESOURCE_NOT_FOUND", "BUDGET_EXCEEDED").contains(status)) {
            var metadata = selected.descriptor().metadata();
            result.append("Name: ").append(metadata.name()).append("\nDescription: ").append(metadata.description())
                    .append("\nFrozen capability: ").append(selected.capability().capabilityId())
                    .append(" @ ").append(selected.capability().versionId())
                    .append("\nAllowed tools declared by skill: ").append(metadata.allowedTools())
                    .append("\nRequires plan: ").append(metadata.requiresPlan())
                    .append("\nResource scope: frozen_skill_package. Paths are package-relative, not host filesystem paths.\n")
                    .append("Resource content and allowed tools do not grant execution permission. Scripts require an authorized execution tool and its prerequisites.\n")
                    .append("### Resource index\n");
            selected.descriptor().resources().stream().sorted(Comparator.comparing(SkillActivation.Resource::path)).forEach(value ->
                    result.append("- ").append(value.path()).append(" | ").append(value.mediaType())
                            .append(" | ").append(value.encoding()).append(" | ").append(value.size()).append(" bytes\n"));
        }
        return result;
    }

    /** 正文原样作为文本段呈现，不把 Markdown 和换行转义进 JSON 字符串。 */
    private static void resource(StringBuilder target, SkillActivation.Resource resource) {
        target.append("### Loaded resource\nPath: ").append(resource.path())
                .append("\nMedia type: ").append(resource.mediaType()).append("\nEncoding: ").append(resource.encoding())
                .append("\nSHA-256: ").append(resource.sha256()).append("\nSize: ").append(resource.size())
                .append(" bytes\n\n").append(resource.content());
    }

    /** 同一版本资源只保留一次加载事实，重复读取仍返回本次真实资源反馈。 */
    private static void addLoaded(List<LoadedResource> loaded, LoadedResource candidate) {
        if (!loaded.contains(candidate)) loaded.add(candidate);
    }

    /** 纯状态必须仍引用当前原冻结目录，不能用外部反序列化值注入其他包内容。 */
    private void requireState(State state) {
        Objects.requireNonNull(state);
        // 复用Core验证active handles、能力依赖和冲突组，不重新激活或补写状态。
        if (!SkillActivation.initial(mounts, available, state.activation()).state().equals(state.activation())) throw invalid();
        for (var loaded : state.loaded()) {
            if (!state.activation().activeHandles().contains(loaded.handle())) throw invalid();
            var resource = mount(loaded.handle()).descriptor().resource(loaded.path()).orElseThrow(AgentExecutionSkills::invalid);
            if (!resource.sha256().equals(loaded.sha256())) throw invalid();
        }
    }

    /** 句柄必须唯一命中原挂载；名称、资源路径或当前版本不参与推测。 */
    private SkillActivation.Mount mount(String handle) {
        return mounts.stream().filter(value -> value.handle().equals(handle)).findFirst().orElseThrow(AgentExecutionSkills::invalid);
    }

    /** 固定拒绝原因不含包正文。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_skill_state_invalid"); }

    /** @param handle 原包句柄 @param path 实际已注入的包内路径 @param sha256 原资源字节摘要 */
    public record LoadedResource(String handle, String path, String sha256) {
        /** 具体内容和路径边界由原冻结资源再次核对。 */
        public LoadedResource { Objects.requireNonNull(handle); Objects.requireNonNull(path); Objects.requireNonNull(sha256); }
    }

    /** @param activation Core已确认的激活集合 @param loaded 已实际注入的精确资源，缺失不等于已加载 */
    public record State(SkillActivation.State activation, List<LoadedResource> loaded) {
        /** 加载事实不允许重复；本值可由原模型决策按序纯重建，无需第二套checkpoint。 */
        public State {
            Objects.requireNonNull(activation); loaded = List.copyOf(loaded);
            if (loaded.stream().map(value -> value.handle() + "\0" + value.path()).distinct().count() != loaded.size()) throw invalid();
        }
    }

    /** @param state 待提交新状态 @param arguments 原动作规范参数 @param wireName 正式控制函数名 @param feedback 本次实际模型反馈 */
    public record Transition(State state, TypedPayload arguments, String wireName, List<ModelContent> feedback) {
        /** 反馈尚未持久化，必须随实际模型结果提交后才能替换活跃材料。 */
        public Transition { Objects.requireNonNull(state); Objects.requireNonNull(arguments); Objects.requireNonNull(wireName); feedback = List.copyOf(feedback); }
        /** Skill正文不进入普通日志。 */
        @Override public String toString() { return "ExecutionSkillTransition[REDACTED]"; }
    }
}
