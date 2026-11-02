package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.skillforge.runtime.core.SkillActivation.*;

/** 验证真实发布 wire、一次预算和冻结依赖，不借用宿主 codec 构造预期。 */
class SkillActivationTest {
    private static final String WIRE = """
            {"model_invocable":true,"user_invocable":false,"activation_mode":"ON_DEMAND",
             "package":{"entrypoint":"SKILL.md","metadata":{"name":"sample-skill","description":"Sample skill","license":null,"compatibility":null,"extensions":{},"allowed_tools":["shell"]},
             "files":[{"path":"SKILL.md","media_type":"text/markdown","encoding":"UTF8","content":"---\\nname: sample-skill\\ndescription: Sample skill\\n---\\n说明😀 123 \\\"quoted\\\"","sha256":"93cbc1937e466aeb59b21bea347e65357d105121dc1ecd3da51b99ca3afb67ff","size":"76"}]},
             "conflict_group":null,"required_capabilities":[],"estimated_tokens":7}
            """;
    @Test void planRequirementComesFromFrozenMetadataAndActivationIsMonotonic() {
        var required=decode(payload(WIRE.replace("\"extensions\":{}","\"extensions\":{\"skillforge-plan\":\"required\"}")));
        assertTrue(required.metadata().requiresPlan());
        assertFalse(decode(payload(WIRE)).metadata().requiresPlan());
        assertThrows(IllegalArgumentException.class,()->decode(payload(WIRE.replace("\"extensions\":{}","\"extensions\":{\"skillforge-plan\":\"ignored\"}"))));
        var first=mount("first",required,100);var second=mount("second",decode(payload(WIRE)),100);
        var mounts=List.of(first,second);var available=Set.of(first.capability(),second.capability());
        var activated=activate("first",mounts,available,new State(List.of()));
        var later=activate("second",mounts,available,activated.state());
        assertEquals(List.of("first","second"),later.state().activeHandles());
        assertEquals(later.state(),activate("missing",mounts,available,later.state()).state());
        assertEquals(later.state(),activate("first",mounts,available,later.state()).state());
    }
    @Test void publishedWireKeepsNumbersFlagsAndInstructionBytes() {
        var descriptor = decode(payload(WIRE));
        assertTrue(descriptor.modelInvocable()); assertFalse(descriptor.userInvocable());
        assertEquals(7, descriptor.estimatedTokens());
        assertTrue(descriptor.entrypoint().content().endsWith("说明😀 123 \"quoted\""));
        assertEquals("sample-skill", descriptor.metadata().name());
        assertEquals(List.of("shell"), descriptor.metadata().allowedTools());
    }
    @Test void rejectsAmbiguousWireAndBoundedResources() {
        for (String token : List.of("\"7\"", "0", "-1", "01", "1e1", "1.0", "9223372036854775808", "{\"$number\":\"7\"}"))
            assertThrows(IllegalArgumentException.class, () -> decode(payload(WIRE.replace(":7}", ":" + token + "}"))));
        for (String wire : List.of(WIRE + "{}", WIRE.replace("\"model_invocable\":true", "\"model_invocable\":true,\"model_invocable\":false"),
                WIRE.replace("ON_DEMAND", "PRIVATE_MODE"), WIRE.replace("\"size\":\"76\"", "\"size\":\"75\"")))
            assertThrows(IllegalArgumentException.class, () -> decode(payload(wire)));
        assertThrows(IllegalArgumentException.class, () -> decode(payload(WIRE), new CodecLimits(20, 100, 10)));
        assertThrows(IllegalArgumentException.class, () -> decode(payload(WIRE), new CodecLimits(1000, 5, 10)));
        assertThrows(IllegalArgumentException.class, () -> decode(new TypedPayload("wrong", WIRE)));
    }
    @Test void rejectsRemovedIntentsField() {
        for (String intents : List.of("[]", "[\"help\"]")) {
            String wire = WIRE.replace("\"conflict_group\"", "\"intents\":" + intents + ",\"conflict_group\"");
            assertThrows(IllegalArgumentException.class, () -> decode(payload(wire)));
        }
    }
    @Test void onDemandActivatesOnceAndReplayReturnsNoNewBlocks() {
        var mount = mount("a", decode(payload(WIRE)), 9);
        var mounts = List.of(mount); var available = Set.of(mount.capability());
        var result = activate("a", mounts, available, new State(List.of()));
        assertEquals(Status.ACTIVATED, result.status()); assertEquals(List.of("a"), result.state().activeHandles());
        assertEquals(mount.descriptor().entrypoint().content(), result.blocks().getFirst().content());
        var repeat = activate("a", mounts, available, result.state());
        assertEquals(Status.ALREADY_ACTIVE, repeat.status()); assertEquals(result.state(), repeat.state()); assertTrue(repeat.blocks().isEmpty());
        var capped = mount("a", mount.descriptor(), 6);
        assertEquals(Status.BUDGET_EXCEEDED, activate("a", List.of(capped), available, new State(List.of())).status());
    }
    @Test void exactDependencyVersionAndConflictAreDeterministic() {
        var dependency = new CapabilityRef(CapabilityType.TOOL, "tool", "v1");
        var original = decode(payload(WIRE));
        var dependent = new Descriptor(true, false, Mode.ON_DEMAND, original.metadata(), original.resources(),
                Optional.of("group"), List.of(dependency), 7);
        var a = mount("a", dependent, 20); var b = mount("b", dependent, 20); var mounts=List.of(a,b);
        var upgraded=Set.of(a.capability(), b.capability(), new CapabilityRef(CapabilityType.TOOL,"tool","v2"));
        var rejected = activate("a", mounts, upgraded, new State(List.of()));
        assertEquals(Status.DEPENDENCY_MISSING, rejected.status());
        assertTrue(rejected.state().activeHandles().isEmpty()); assertTrue(rejected.blocks().isEmpty());
        var missing=Set.of(a.capability(), b.capability(), new CapabilityRef(CapabilityType.TOOL,"different-tool","v2"));
        assertEquals(Status.DEPENDENCY_MISSING, activate("a", mounts, missing, new State(List.of())).status());
        var present=Set.of(a.capability(),b.capability(),dependency);
        var first=activate("a",mounts,present,new State(List.of()));
        assertEquals(Status.ACTIVATED, first.status());
        var conflict=activate("b",mounts,present,first.state());
        assertEquals(Status.CONFLICT,conflict.status()); assertEquals(first.state(),conflict.state()); assertTrue(conflict.blocks().isEmpty());
    }
    @Test void alwaysUsesFrozenOrderAndDoesNotRequireModelInvocationPermission() {
        var descriptor=decode(payload(WIRE.replace("ON_DEMAND","ALWAYS").replace("\"model_invocable\":true","\"model_invocable\":false")));
        var a=mount("a",descriptor,20); var b=mount("b",descriptor,20); var mounts=List.of(b,a);
        var result=initial(mounts,Set.of(a.capability(),b.capability()),new State(List.of()));
        assertEquals(List.of(Status.ACTIVATED,Status.ACTIVATED),result.results().stream().map(Result::status).toList());
        assertEquals(List.of("b","a"),result.state().activeHandles());
        assertEquals(Status.MODE_MISMATCH,activate("a",mounts,Set.of(a.capability(),b.capability()),result.state()).status());
        var disabled=mount("c",decode(payload(WIRE.replace("\"model_invocable\":true","\"model_invocable\":false"))),20);
        assertEquals(Status.NOT_INVOCABLE,activate("c",List.of(disabled),Set.of(disabled.capability()),new State(List.of())).status());
        var tight=mount("d",descriptor,6);
        assertEquals(Status.BUDGET_EXCEEDED,initial(List.of(tight),Set.of(tight.capability()),new State(List.of()))
                .results().getFirst().status());
    }
    @Test void corruptActiveStateAndMountsFailClosed() {
        var a=mount("a",decode(payload(WIRE)),20);
        assertThrows(IllegalArgumentException.class,()->initial(List.of(a,a),Set.of(a.capability()),new State(List.of())));
        assertThrows(IllegalArgumentException.class,()->initial(List.of(a),Set.of(a.capability()),new State(List.of("missing"))));
        assertThrows(IllegalArgumentException.class,()->initial(List.of(a),Set.of(),new State(List.of())));
        assertThrows(IllegalArgumentException.class,()->new Mount("a",a.capability(),Mode.ALWAYS,20,a.descriptor()));
        assertEquals(Status.NOT_FOUND,activate("missing",List.of(a),Set.of(a.capability()),new State(List.of())).status());
    }
    @Test void resourceReadRequiresActiveFrozenSkillAndMountBudget() {
        var original=decode(payload(WIRE));
        var guide=new Resource("references/guide.md","text/markdown","UTF8","guide text",
                "5285687a159a964606970181e030c7731969f7188be7d088825b3bfd9be6533c",10);
        var descriptor=new Descriptor(original.modelInvocable(),original.userInvocable(),original.mode(),
                original.metadata(),List.of(original.entrypoint(),guide),original.conflictGroup(),
                original.requiredCapabilities(),original.estimatedTokens());
        var mount=mount("a",descriptor,20); var available=Set.of(mount.capability());
        var inactive=readResource("a","references/guide.md",List.of(mount),available,new State(List.of()));
        assertEquals(ResourceStatus.SKILL_NOT_ACTIVE,inactive.status());
        var loaded=readResource("a","references/guide.md",List.of(mount),available,new State(List.of("a")));
        assertEquals(ResourceStatus.LOADED,loaded.status()); assertEquals(List.of("a"),loaded.state().activeHandles());
        assertEquals("guide text",loaded.resource().orElseThrow().content());
        assertEquals(ResourceStatus.RESOURCE_NOT_FOUND,readResource("a","SKILL.md",List.of(mount),available,loaded.state()).status());
        var capped=mount("a",descriptor,2);
        assertEquals(ResourceStatus.BUDGET_EXCEEDED,readResource("a","references/guide.md",List.of(capped),available,new State(List.of("a"))).status());
    }

    private static TypedPayload payload(String wire) { return new TypedPayload(DESCRIPTOR_SCHEMA,wire); }
    private static Mount mount(String handle, Descriptor descriptor,long budget) {
        return new Mount(handle,new CapabilityRef(CapabilityType.SKILL,handle,"v1"),descriptor.mode(),budget,descriptor);
    }
}
