package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.SkillActivation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 核验冻结资源实际反馈、重复加载边界及恢复状态不可伪造，不读取宿主文件。 */
class AgentExecutionSkillsTest {
    private static final CodecLimits LIMITS = new CodecLimits(64_000, 8_000, 32);

    @Test void activatedInstructionsAreEmittedOnceAndExactResourceCanBeRebuiltFromOriginalDecision() {
        var mount = mount(SkillActivation.Mode.ON_DEMAND);
        var skills = new AgentExecutionSkills(List.of(mount), Set.of(mount.capability()), LIMITS);
        var initial = skills.initial();
        assertTrue(skills.initialContents(initial).isEmpty());
        var first = skills.apply(initial, new AgentAction.ActivateSkill("skill:one"));
        var firstBody = text(first.feedback());
        assertTrue(firstBody.endsWith("入口😀"));
        assertTrue(firstBody.contains("Path: SKILL.md"));
        var repeated = skills.apply(first.state(), new AgentAction.ActivateSkill("skill:one"));
        assertEquals(first.state(), repeated.state());
        assertFalse(text(repeated.feedback()).contains("入口😀"), "重复激活不能复制入口正文");
        assertTrue(text(repeated.feedback()).contains("Status: ALREADY_ACTIVE"));
        var action = new AgentAction.ReadSkillResource("skill:one", "references/guide.md");
        var read = skills.apply(first.state(), action);
        assertTrue(text(read.feedback()).endsWith("原版本参考正文"));
        assertEquals(read, skills.apply(skills.apply(skills.initial(), new AgentAction.ActivateSkill("skill:one")).state(), action));
        assertEquals(2, read.state().loaded().size());
        assertEquals(read.state(), skills.apply(read.state(), action).state());
    }

    @Test void packageRelativeReadCannotReachHostOrUnactivatedResource() {
        var mount = mount(SkillActivation.Mode.ON_DEMAND);
        var skills = new AgentExecutionSkills(List.of(mount), Set.of(mount.capability()), LIMITS);
        var denied = skills.apply(skills.initial(), new AgentAction.ReadSkillResource("skill:one", "references/guide.md"));
        assertTrue(text(denied.feedback()).contains("Status: SKILL_NOT_ACTIVE"));
        assertTrue(text(denied.feedback()).contains("activate it before reading"));
        assertFalse(text(denied.feedback()).contains("### Loaded resource"));
        var active = skills.apply(skills.initial(), new AgentAction.ActivateSkill("skill:one")).state();
        for (var path : List.of("../private", "C:\\private.txt", "/etc/passwd", "SKILL.md")) {
            var missing = skills.apply(active, new AgentAction.ReadSkillResource("skill:one", path));
            assertTrue(text(missing.feedback()).contains("Status: RESOURCE_NOT_FOUND"));
            assertTrue(text(missing.feedback()).contains("choose an exact reference path"));
            assertFalse(text(missing.feedback()).contains("### Loaded resource"));
            assertEquals(active, missing.state());
        }
    }

    @Test void alwaysContentsKeepOriginalBytesAndForgedLoadedReferencesFailClosed() {
        var mount = mount(SkillActivation.Mode.ALWAYS);
        var skills = new AgentExecutionSkills(List.of(mount), Set.of(mount.capability()), LIMITS);
        var state = skills.initial();
        assertTrue(text(skills.initialContents(state)).endsWith("入口😀"));
        var forged = new AgentExecutionSkills.State(state.activation(), List.of(
                new AgentExecutionSkills.LoadedResource("skill:one", "SKILL.md", "0".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> skills.initialContents(forged));
        var omittedAlways = new AgentExecutionSkills.State(new SkillActivation.State(List.of()), List.of());
        assertThrows(IllegalArgumentException.class, () -> skills.initialContents(omittedAlways));
    }

    @Test void markdownIsReadableAndFrozenResourceIdentityAndPermissionScopeRemainVisible() {
        var entry = "# Instructions\n\n- Keep \"quoted\" text.\n- Use `tools` only when authorized.\n";
        var base = mount(SkillActivation.Mode.ON_DEMAND);
        var descriptor = new SkillActivation.Descriptor(true, false, base.mode(),
                new SkillActivation.Metadata("Readable skill", "Use for scoped tasks", List.of("search"), true),
                List.of(resource("SKILL.md", entry), resource("references/guide.md", "reference")),
                Optional.empty(), List.of(), 5);
        var mount = new SkillActivation.Mount(base.handle(), base.capability(), base.mode(), base.tokenBudget(), descriptor);
        var skills = new AgentExecutionSkills(List.of(mount), Set.of(mount.capability()), LIMITS);
        var activated = skills.apply(skills.initial(), new AgentAction.ActivateSkill(mount.handle()));
        var content = text(activated.feedback());
        assertTrue(content.endsWith(entry), "Markdown正文保留原换行和引号");
        assertTrue(content.contains("Frozen capability: one @ v1"));
        assertTrue(content.contains("SHA-256: " + descriptor.entrypoint().sha256()));
        assertTrue(content.contains("Requires plan: true"));
        assertTrue(content.contains("Allowed tools declared by skill: [search]"));
        assertTrue(content.contains("references/guide.md | text/markdown | UTF8"));
        assertTrue(content.contains("Resource scope: frozen_skill_package"));
        assertTrue(content.contains("do not grant execution permission"));
        assertEquals("skill:one", SessionRuntimePayloadJson.parse(activated.arguments().canonicalJson(), LIMITS).path("skill_handle").textValue());
    }

    @Test void rejectedActivationExplainsReasonAndNextStepWithoutLoadingPackageContent() {
        var base = mount(SkillActivation.Mode.ON_DEMAND);
        var deniedDescriptor = new SkillActivation.Descriptor(false, false, base.mode(), base.descriptor().metadata(),
                base.descriptor().resources(), Optional.empty(), List.of(), 5);
        var deniedMount = new SkillActivation.Mount(base.handle(), base.capability(), base.mode(), base.tokenBudget(), deniedDescriptor);
        var skills = new AgentExecutionSkills(List.of(deniedMount), Set.of(base.capability()), LIMITS);
        for (var handle : List.of(base.handle(), "skill:unknown")) {
            var result = skills.apply(skills.initial(), new AgentAction.ActivateSkill(handle));
            var feedback = text(result.feedback());
            assertTrue(feedback.contains("Reason:"));
            assertTrue(feedback.contains("Next:"));
            assertFalse(feedback.contains("入口😀"));
            assertTrue(result.state().activation().activeHandles().isEmpty());
        }
    }

    @Test void oversizedReferenceReportsBudgetFailureWithoutChangingLoadedResources() {
        var base = mount(SkillActivation.Mode.ON_DEMAND);
        var descriptor = new SkillActivation.Descriptor(true, false, base.mode(), base.descriptor().metadata(),
                List.of(resource("SKILL.md", "entry"), resource("references/large.md", "x".repeat(100))),
                Optional.empty(), List.of(), 1);
        var mount = new SkillActivation.Mount(base.handle(), base.capability(), base.mode(), 5, descriptor);
        var skills = new AgentExecutionSkills(List.of(mount), Set.of(mount.capability()), LIMITS);
        var active = skills.apply(skills.initial(), new AgentAction.ActivateSkill(mount.handle())).state();
        var result = skills.apply(active, new AgentAction.ReadSkillResource(mount.handle(), "references/large.md"));
        assertEquals(active, result.state());
        assertTrue(text(result.feedback()).contains("Status: BUDGET_EXCEEDED"));
        assertTrue(text(result.feedback()).contains("repeating this request will not increase the budget"));
        assertFalse(text(result.feedback()).contains("### Loaded resource"));
    }

    /** 核对模型实际收到的文本。 */
    private static String text(List<ModelContent> feedback) {
        return assertInstanceOf(ModelContent.Text.class, feedback.getFirst()).text();
    }

    /** 使用精确资源字节和摘要构造冻结包，和运行时当前文件系统无关。 */
    private static SkillActivation.Mount mount(SkillActivation.Mode mode) {
        var descriptor = new SkillActivation.Descriptor(true, false, mode,
                new SkillActivation.Metadata("测试包", "冻结资源验证", List.of()),
                List.of(resource("SKILL.md", "入口😀"), resource("references/guide.md", "原版本参考正文")),
                Optional.empty(), List.of(), 5);
        return new SkillActivation.Mount("skill:one", new CapabilityRef(CapabilityType.SKILL, "one", "v1"), mode, 1000, descriptor);
    }

    /** 摘要和大小都基于真实UTF-8字节，避免用字符数冒充文件大小。 */
    private static SkillActivation.Resource resource(String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            return new SkillActivation.Resource(path, "text/markdown", "UTF8", content,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length);
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
