package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.TypedPayload;
import java.util.*;

/** 当前 Run 内只增不减的 Skill 加载；来源、权限、日志和 checkpoint 提交由 Session 证明。 */
public final class SkillActivation {
    public static final String DESCRIPTOR_SCHEMA = "skillforge.runtime.capability.descriptor.v1";
    private SkillActivation() { }
    public enum Mode { ALWAYS, ON_DEMAND }
    public enum Status { ACTIVATED, ALREADY_ACTIVE, NOT_FOUND, MODE_MISMATCH, NOT_INVOCABLE,
        DEPENDENCY_MISSING, CONFLICT, BUDGET_EXCEEDED }
    public enum ResourceStatus { LOADED, SKILL_NOT_ACTIVE, RESOURCE_NOT_FOUND, BUDGET_EXCEEDED }
    public record Block(String blockId, String content, boolean required) {
        public Block { text(blockId); text(content); }
    }
    public record Metadata(String name, String description, List<String> allowedTools, boolean requiresPlan) {
        public Metadata(String name,String description,List<String> allowedTools) { this(name,description,allowedTools,false); }
        public Metadata {
            text(name); text(description); allowedTools = List.copyOf(allowedTools);
            allowedTools.forEach(SkillActivation::text);
        }
    }
    public record Resource(String path, String mediaType, String encoding, String content,
                           String sha256, long size) {
        public Resource {
            text(path); text(mediaType); text(encoding); Objects.requireNonNull(content); text(sha256);
            if (size < 0 || !sha256.matches("[0-9a-f]{64}")
                    || !(encoding.equals("UTF8") || encoding.equals("BASE64"))) throw invalid();
        }
        public boolean script() { return path.startsWith("scripts/"); }
        @Override public String toString() { return "SkillResource[path=" + path + ",sha256=" + sha256 + "]"; }
    }
    public record Descriptor(boolean modelInvocable, boolean userInvocable, Mode mode,
            Metadata metadata, List<Resource> resources,
            Optional<String> conflictGroup, List<CapabilityRef> requiredCapabilities,
            long estimatedTokens) {
        public Descriptor {
            Objects.requireNonNull(mode); Objects.requireNonNull(metadata); resources = List.copyOf(resources);
            Objects.requireNonNull(conflictGroup); requiredCapabilities = List.copyOf(requiredCapabilities);
            if (resources.isEmpty() || resources.stream().noneMatch(resource -> resource.path().equals("SKILL.md"))
                    || resources.stream().map(Resource::path).distinct().count() != resources.size()
                    || estimatedTokens <= 0
                    || requiredCapabilities.stream().map(ref -> ref.type()+":"+ref.capabilityId()).distinct().count()
                        != requiredCapabilities.size()) throw invalid();
            conflictGroup.ifPresent(SkillActivation::text);
        }
        public Resource entrypoint() {
            return resources.stream().filter(resource -> resource.path().equals("SKILL.md"))
                    .findFirst().orElseThrow(SkillActivation::invalid);
        }
        public Optional<Resource> resource(String path) {
            return resources.stream().filter(resource -> resource.path().equals(path)).findFirst();
        }
        @Override public String toString() { return "SkillDescriptor[REDACTED]"; }
    }
    public record Mount(String handle, CapabilityRef capability, Mode mode, long tokenBudget, Descriptor descriptor) {
        public Mount {
            text(handle); Objects.requireNonNull(capability); Objects.requireNonNull(mode); Objects.requireNonNull(descriptor);
            if (capability.type() != CapabilityType.SKILL || tokenBudget <= 0 || mode != descriptor.mode()
                    || descriptor.requiredCapabilities().stream().anyMatch(ref -> sameDefinition(ref,capability))) throw invalid();
        }
    }
    public record State(List<String> activeHandles) {
        public State {
            activeHandles = List.copyOf(activeHandles); activeHandles.forEach(SkillActivation::text);
            if (new HashSet<>(activeHandles).size() != activeHandles.size()) throw invalid();
        }
    }
    public record Result(String handle, Status status, State state, List<Block> blocks) {
        public Result { text(handle); Objects.requireNonNull(status); Objects.requireNonNull(state); blocks=List.copyOf(blocks); }
    }
    public record Initial(List<Result> results, State state) {
        public Initial { results=List.copyOf(results); Objects.requireNonNull(state); }
    }
    public record ResourceResult(String handle, String path, ResourceStatus status,
                                 State state, Optional<Resource> resource) {
        public ResourceResult {
            text(handle); text(path); Objects.requireNonNull(status); Objects.requireNonNull(state);
            Objects.requireNonNull(resource);
        }
    }
    /** 依赖绑定精确能力版本，并与当前 Run 的冻结挂载核对；不递归激活。 */
    public static Initial initial(List<Mount> mounts, Set<CapabilityRef> available, State state) {
        validate(mounts, available, state);
        var results = new ArrayList<Result>();
        for (Mount mount : mounts) {
            if (mount.mode() == Mode.ALWAYS) {
                var result = apply(mount.handle(), mounts, available, state, Mode.ALWAYS);
                results.add(result); state = result.state();
            }
        }
        return new Initial(results, state);
    }
    /** 模型只能激活 ON_DEMAND 且 modelInvocable 的冻结 Skill；拒绝不产生内容。 */
    public static Result activate(String handle, List<Mount> mounts, Set<CapabilityRef> available, State state) {
        validate(mounts, available, state);
        return apply(handle, mounts, available, state, Mode.ON_DEMAND);
    }
    /** 已激活 Skill 的引用资源按需读取；脚本只作为内容返回，执行仍必须进入授权 Tool。 */
    public static ResourceResult readResource(String handle, String path, List<Mount> mounts,
                                              Set<CapabilityRef> available, State state) {
        validate(mounts, available, state); text(handle); text(path);
        var mount = mounts.stream().filter(value -> value.handle().equals(handle)).findFirst().orElse(null);
        if (mount == null || !state.activeHandles().contains(handle)) {
            return new ResourceResult(handle, path, ResourceStatus.SKILL_NOT_ACTIVE, state, Optional.empty());
        }
        var resource = mount.descriptor().resource(path);
        if (resource.isEmpty() || path.equals("SKILL.md")) {
            return new ResourceResult(handle, path, ResourceStatus.RESOURCE_NOT_FOUND, state, Optional.empty());
        }
        long estimate = Math.max(1, (resource.orElseThrow().content().length() + 3L) / 4L);
        if (estimate > mount.tokenBudget()) {
            return new ResourceResult(handle, path, ResourceStatus.BUDGET_EXCEEDED, state, Optional.empty());
        }
        return new ResourceResult(handle, path, ResourceStatus.LOADED, state, resource);
    }
    private static Result apply(String handle, List<Mount> mounts, Set<CapabilityRef> available, State state, Mode mode) {
        text(handle);
        var mount = mounts.stream().filter(m -> m.handle().equals(handle)).findFirst().orElse(null);
        Status status;
        if (mount == null) status = Status.NOT_FOUND;
        else if (mount.mode() != mode) status = Status.MODE_MISMATCH;
        else if (mode == Mode.ON_DEMAND && !mount.descriptor().modelInvocable()) status = Status.NOT_INVOCABLE;
        else if (state.activeHandles().contains(handle)) status = Status.ALREADY_ACTIVE;
        else if (!available.containsAll(mount.descriptor().requiredCapabilities())) status = Status.DEPENDENCY_MISSING;
        else if (mount.descriptor().conflictGroup().isPresent() && mounts.stream()
                .filter(m -> state.activeHandles().contains(m.handle()))
                .anyMatch(m -> m.descriptor().conflictGroup().equals(mount.descriptor().conflictGroup()))) status = Status.CONFLICT;
        else if (mount.descriptor().estimatedTokens() > mount.tokenBudget()) status = Status.BUDGET_EXCEEDED;
        else {
            var handles = new ArrayList<>(state.activeHandles()); handles.add(handle);
            var root = mount.descriptor().entrypoint();
            if (!root.encoding().equals("UTF8")) throw invalid();
            return new Result(handle, Status.ACTIVATED, new State(handles),
                    List.of(new Block(root.path(), root.content(), true)));
        }
        return new Result(handle, status, state, List.of());
    }
    private static void validate(List<Mount> mounts, Set<CapabilityRef> available, State state) {
        Objects.requireNonNull(available); Objects.requireNonNull(state);
        var handles = new HashSet<String>();
        for (Mount mount : mounts) if (!handles.add(mount.handle()) || !available.contains(mount.capability())) throw invalid();
        if (!handles.containsAll(state.activeHandles())) throw invalid();
        var groups = new HashSet<String>();
        for (Mount mount : mounts) if (state.activeHandles().contains(mount.handle()) && mount.descriptor().conflictGroup().isPresent()
                && !groups.add(mount.descriptor().conflictGroup().orElseThrow())) throw invalid();
    }
    /** 原发布 descriptor 有 JSON 整数；独立有界词法桥保留数字类型，不放宽 SDK 快照编码。 */
    public static Descriptor decode(TypedPayload payload) {
        return SkillDescriptorDecoder.decode(payload, new CodecLimits(262144, 32768, 16));
    }
    public static Descriptor decode(TypedPayload payload, CodecLimits limits) {
        return SkillDescriptorDecoder.decode(payload, limits);
    }
    private static void text(String value) { if (value == null || value.isBlank()) throw invalid(); }
    private static boolean sameDefinition(CapabilityRef left,CapabilityRef right) {
        return left.type()==right.type()&&left.capabilityId().equals(right.capabilityId());
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("skill_activation_rejected"); }
}
