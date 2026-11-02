package com.skillforge.core.agent.integration.runtime.session;


import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.SessionOwner;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** 冻结 Run binding 的非 Tool 挂载解析不读取当前团队版本。 */
class JdbcSessionCapabilitiesTest {
    @Test
    void mountedToolIdentityIsStableWhileExactHandlesKeepTheirFullInputBinding() {
        assertEquals("tool:101", JdbcSessionCapabilities.toolHandle("101", null).value());
        assertEquals("tool:101:input:run:first", JdbcSessionCapabilities.toolHandle("101", new InputId("input:run:first")).value());
        assertEquals("tool:101:input:run:second", JdbcSessionCapabilities.toolHandle("101", new InputId("input:run:second")).value());
        // mountId 是权威输入；即使含分隔符也不通过剪裁字符串猜测另一个身份。
        assertEquals("tool:mount:part", JdbcSessionCapabilities.toolHandle("mount:part", null).value());
        assertThrows(IllegalArgumentException.class, () -> JdbcSessionCapabilities.toolHandle(" 101", null));
        assertThrows(IllegalArgumentException.class, () -> JdbcSessionCapabilities.toolHandle("", null));
    }

    @Test
    void agentRunBindingUsesFrozenGenerationAndOnlyFrozenNonToolMounts() {
        var binding=binding("""
                {"mounts":{"generation":7,"mounts":[
                  {"mount_id":"skill-1","revision":2,"active":true,"capability":{"type":"SKILL","capability_id":"skill","version_id":"skill-v2"}},
                  {"mount_id":"tool-1","revision":4,"active":true,"capability":{"type":"TOOL","capability_id":"tool","version_id":"tool-v4"}},
                  {"mount_id":"inactive","revision":1,"active":false,"capability":{"type":"WORKFLOW","capability_id":"workflow","version_id":"workflow-v1"}}
                ]}}
                """);
        var resolved=resolve(SessionTarget.Kind.AGENT,new InputId("child-input"),11,
                List.of(mount("skill-1",2,"SKILL","skill","skill-v2"),
                        mount("extra",9,"WORKFLOW","extra","extra-v9")),binding);

        assertEquals(7,generation(resolved));
        assertEquals(List.of("skill-1"),mounts(resolved).stream().map(value->value.mountId).toList());
    }

    @Test
    void frozenMountRequiresFrozenCapabilityIdentityAndIgnoresDraftRevision() {
        var binding=binding("""
                {"mounts":{"generation":7,"mounts":[
                  {"mount_id":"skill-1","revision":2,"active":true,"capability":{"type":"SKILL","capability_id":"skill","version_id":"skill-v2"}}
                ]}}
                """);

        assertDoesNotThrow(()->resolve(SessionTarget.Kind.AGENT,new InputId("child-input"),11,
                List.of(mount("skill-1",3,"SKILL","skill","skill-v2")),binding));
        assertThrows(IllegalArgumentException.class,()->resolve(SessionTarget.Kind.AGENT,new InputId("child-input"),11,
                List.of(mount("skill-1",2,"SKILL","skill","skill-v3")),binding));
    }

    @Test
    void unboundAgentInputAndCatalogUseCurrentMountsButTeamRequiresBinding() {
        var current=List.of(mount("skill-1",2,"SKILL","skill","skill-v2"));

        var inputCatalog=resolve(SessionTarget.Kind.AGENT,new InputId("open-platform-input"),11,current,null);
        assertEquals(11,generation(inputCatalog));
        assertEquals(current,mounts(inputCatalog));
        var catalog=resolve(SessionTarget.Kind.AGENT,null,11,current,null);
        assertEquals(11,generation(catalog));
        assertEquals(current,mounts(catalog));
        assertThrows(IllegalArgumentException.class,()->resolve(SessionTarget.Kind.TEAM,new InputId("input"),11,current,null));
        assertThrows(IllegalArgumentException.class,()->resolve(SessionTarget.Kind.TEAM,null,11,current,null));
    }

    /** GI-03（a89bffdd）允许群 Run 冻结实际 Agent，群目录仍不能直接复用其当前挂载。 */
    @Test void groupDirectoryRequiresRunBindingAndUsesOnlyItsFrozenAgentMounts() {
        var current=List.of(mount("skill-1",2,"SKILL","skill","skill-v2"),
                mount("extra",3,"SKILL","other","other-v3"));
        assertThrows(IllegalArgumentException.class,()->resolve(SessionTarget.Kind.GROUP,null,11,current,null));
        assertThrows(IllegalArgumentException.class,()->resolve(SessionTarget.Kind.GROUP,new InputId("group-input"),11,current,null));
        var binding=binding("""
                {"mounts":{"generation":7,"mounts":[
                  {"mount_id":"skill-1","revision":2,"active":true,"capability":{"type":"SKILL","capability_id":"skill","version_id":"skill-v2"}}
                ]}}
                """);
        var resolved=resolve(SessionTarget.Kind.GROUP,new InputId("group-input"),11,current,binding);
        assertEquals(7,generation(resolved));
        assertEquals(List.of("skill-1"),mounts(resolved).stream().map(value->value.mountId).toList());
        assertThrows(IllegalArgumentException.class,()->resolve(SessionTarget.Kind.GROUP,new InputId("group-input"),11,
                List.of(mount("skill-1",2,"SKILL","skill","other-version")),binding));
    }

    @Test
    void frozenMemberRejectsDuplicateMountIds() {
        var binding=binding("""
                {"mounts":{"generation":7,"mounts":[
                  {"mount_id":"same","revision":1,"active":false,"capability":{"type":"SKILL","capability_id":"skill","version_id":"v1"}},
                  {"mount_id":"same","revision":2,"active":false,"capability":{"type":"SKILL","capability_id":"skill","version_id":"v2"}}
                ]}}
                """);

        assertThrows(IllegalArgumentException.class,()->resolve(SessionTarget.Kind.AGENT,new InputId("input"),11,List.of(),binding));
    }

    @Test
    void mapperUsesExactRunBindingIdentityAndHasNoTeamVersionQuery() throws Exception {
        String xml=resource("mapper/AgentRuntimeSessionCapabilitiesMapper.xml");

        assertTrue(xml.contains("nw_runtime_agent_run_bindings"));
        assertTrue(xml.contains("b.session_id=#{owner.sessionRef.sessionId}"));
        assertTrue(xml.contains("b.target_kind=#{owner.target.kind}"));
        assertTrue(xml.contains("b.target_id=#{owner.target.id}"));
        assertTrue(xml.contains("b.end_user_id IS NOT DISTINCT FROM"));
        assertTrue(xml.contains("b.actor_agent_id=#{agent}"));
        assertTrue(xml.contains("b.input_id=#{input} OR 'input:'||b.run_id=#{input}"));
        assertFalse(xml.contains("agent_runtime_studio_run_bindings"));
        assertFalse(xml.contains("nw_agent_team_versions"));
        assertFalse(xml.contains("teamRunScope"));
        assertFalse(xml.contains("teamGeneration"));
        assertFalse(xml.contains("teamMounts"));

        var configuration=new Configuration();
        String path="mapper/AgentRuntimeSessionCapabilitiesMapper.xml";
        try(var stream=getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream,path);
            new XMLMapperBuilder(stream,configuration,path,configuration.getSqlFragments()).parse();
        }
        var statement=configuration.getMappedStatement(SessionCapabilitiesMapper.class.getName()+".runBinding");
        var tenant=new TenantId("1");
        var group=new GroupBindingRef(tenant,"group");
        var groupOwner=new SessionOwner(new SessionRef(tenant,"group-session"),SessionSubject.group(group),
                new SessionTarget(SessionTarget.Kind.GROUP,group.groupBindingId()));
        var grouped=statement.getBoundSql(Map.of("owner",groupOwner,"agent","10","input","group-input"));
        assertTrue(grouped.getSql().replaceAll("\\s+"," ").contains("b.end_user_id IS NOT DISTINCT FROM NULL"));
        var ownerParameters=List.of("owner.sessionRef.tenantId.value","owner.sessionRef.sessionId",
                "owner.target.kind","owner.target.id");
        var groupParameters=new java.util.ArrayList<>(ownerParameters);
        groupParameters.addAll(List.of("agent","input","input"));
        assertEquals(groupParameters,grouped.getParameterMappings().stream().map(value->value.getProperty()).toList());
        assertFalse(grouped.getParameterMappings().stream().anyMatch(value->value.getProperty().contains("requireUser")));
        assertEquals(List.of("1","group-session",SessionTarget.Kind.GROUP,"group","10","group-input","group-input"),
                parameterValues(configuration,grouped));

        var teamOwner=SessionOwner.user(new SessionRef(tenant,"team-session"),
                new SessionTarget(SessionTarget.Kind.TEAM,"team"),new EndUserId(20));
        var personal=statement.getBoundSql(Map.of("owner",teamOwner,"agent","10","input","team-input"));
        assertTrue(personal.getSql().replaceAll("\\s+"," ").contains("b.end_user_id IS NOT DISTINCT FROM ?"));
        var personalParameters=new java.util.ArrayList<>(ownerParameters);
        personalParameters.addAll(List.of("owner.requireUser.value","agent","input","input"));
        assertEquals(personalParameters,personal.getParameterMappings().stream().map(value->value.getProperty()).toList());
        assertEquals(List.of("1","team-session",SessionTarget.Kind.TEAM,"team",20L,"10","team-input","team-input"),
                parameterValues(configuration,personal));

        // 错 Session/target、实际执行 Agent 或输入只能成为另一组精确查询参数，不能回退原群绑定。
        var otherOwner=SessionOwner.user(new SessionRef(tenant,"other-session"),
                new SessionTarget(SessionTarget.Kind.TEAM,"other-team"),new EndUserId(21));
        var other=statement.getBoundSql(Map.of("owner",otherOwner,"agent","11","input","other-input"));
        assertEquals(List.of("1","other-session",SessionTarget.Kind.TEAM,"other-team",21L,"11","other-input","other-input"),
                parameterValues(configuration,other));
    }

    private static List<Object> parameterValues(Configuration configuration,org.apache.ibatis.mapping.BoundSql sql) {
        var parameters=configuration.newMetaObject(sql.getParameterObject());
        return sql.getParameterMappings().stream().map(value->parameters.getValue(value.getProperty())).toList();
    }

    private static RunCapabilityBinding binding(String frozenMember) {
        var result=new RunCapabilityBinding();result.frozenMember=frozenMember;return result;
    }

    private static SessionCapabilityMountRow mount(String id,long revision,String type,String capability,String version) {
        var result=new SessionCapabilityMountRow();result.mountId=id;result.revision=revision;
        result.capabilityType=type;result.capabilityId=capability;result.versionId=version;return result;
    }

    private static Object resolve(SessionTarget.Kind target,InputId input,long generation,
            List<SessionCapabilityMountRow> current,RunCapabilityBinding binding) {
        try {
            var method=JdbcSessionCapabilities.class.getDeclaredMethod("resolveCurrentOrFrozen",SessionTarget.Kind.class,
                    InputId.class,long.class,List.class,RunCapabilityBinding.class);
            method.setAccessible(true);return method.invoke(null,target,input,generation,current,binding);
        } catch(InvocationTargetException failed) {
            if(failed.getCause() instanceof RuntimeException rejected)throw rejected;
            throw new AssertionError(failed.getCause());
        } catch(ReflectiveOperationException failed) {
            throw new AssertionError(failed);
        }
    }

    private static long generation(Object resolved) {
        return (long)access(resolved,"generation");
    }

    @SuppressWarnings("unchecked")
    private static List<SessionCapabilityMountRow> mounts(Object resolved) {
        return (List<SessionCapabilityMountRow>)access(resolved,"mounts");
    }

    private static Object access(Object target,String accessor) {
        try {
            Method method=target.getClass().getDeclaredMethod(accessor);method.setAccessible(true);return method.invoke(target);
        } catch(ReflectiveOperationException failed) {
            throw new AssertionError(failed);
        }
    }

    private static String resource(String name) throws Exception {
        try(InputStream stream=JdbcSessionCapabilitiesTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream,name+" is missing");return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
