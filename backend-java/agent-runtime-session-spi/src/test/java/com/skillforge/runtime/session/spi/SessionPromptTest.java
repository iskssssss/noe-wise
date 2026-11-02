package com.skillforge.runtime.session.spi;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionPromptTest {
    @Test void freezesNamedSourcesAndPreservesContentAndOrder() {
        var modules = new ArrayList<SessionPrompt.Module>();
        modules.add(new SessionPrompt.Module("core", SessionPrompt.Type.HOST, "sha256:core", "宿主核心规则"));
        modules.add(new SessionPrompt.Module("policy", SessionPrompt.Type.PLATFORM, "v1", "平台规则"));
        modules.add(new SessionPrompt.Module("role", SessionPrompt.Type.AGENT, "sha256:role", "  原始角色\n"));
        modules.add(new SessionPrompt.Module("protocol", SessionPrompt.Type.RUNTIME, "v3", "程序协议"));
        var prompt = new SessionPrompt(modules);
        modules.clear();
        assertEquals("宿主核心规则\n\n平台规则\n\n  原始角色\n\n\n程序协议", prompt.render());
        assertEquals("sha256:role", prompt.modules().get(2).sourceVersion());
        assertThrows(UnsupportedOperationException.class, () -> prompt.modules().clear());
        assertFalse(prompt.toString().contains("原始角色"));
    }

    @Test void rejectsAmbiguousIdentityMissingSourceAndOutOfOrderModules() {
        var policy = new SessionPrompt.Module("policy", SessionPrompt.Type.PLATFORM, "v1", "rules");
        var role = new SessionPrompt.Module("role", SessionPrompt.Type.AGENT, "v1", "role");
        var core = new SessionPrompt.Module("core", SessionPrompt.Type.HOST, "v1", "core");
        assertThrows(IllegalArgumentException.class, () -> new SessionPrompt(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SessionPrompt(List.of(policy, policy)));
        assertThrows(IllegalArgumentException.class, () -> new SessionPrompt(List.of(role, policy)));
        assertThrows(IllegalArgumentException.class, () -> new SessionPrompt(List.of(policy, core)));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionPrompt.Module("role", SessionPrompt.Type.AGENT, "", "role"));
    }
}
