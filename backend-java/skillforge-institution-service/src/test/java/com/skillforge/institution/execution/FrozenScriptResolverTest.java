package com.skillforge.institution.execution;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.skillforge.core.capability.connector.CodeExecutionArgumentException;
import com.skillforge.core.capability.connector.CodeExecutionArguments.Dependency;
import com.skillforge.runtime.session.FrozenSkillResource;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.AgentExecutionSkills;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.core.SkillActivation;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.institution.runtime.AgentRuntimeExecutionCatalog;
import com.skillforge.institution.runtime.AgentRuntimeExecutionWorker;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FrozenScriptResolverTest {
    private static final String MANIFEST = """
            {"entry":{"language":"node","script_path":"scripts/test.mjs"},"dependencies":[
              {"name":"pptxgenjs","version":"3.12.0"},
              {"name":"sharp","version":"0.33.5"}]}
            """;
    private static final List<Dependency> FROZEN = List.of(
            new Dependency("pptxgenjs", "3.12.0"), new Dependency("sharp", "0.33.5"));

    @Test
    void frozenManifestSuppliesFullListForOmittedOrMatchingSubset() {
        assertEquals(FROZEN, script(MANIFEST).dependencies("node", List.of()));
        assertEquals(FROZEN, script(MANIFEST).dependencies("node", List.of(FROZEN.getFirst())));
    }

    @Test
    void modelCannotAddDependencyOrReplaceFrozenVersion() {
        var extra = assertThrows(CodeExecutionArgumentException.class, () -> script(MANIFEST)
                .dependencies("node", List.of(new Dependency("lodash", "4.17.21"))));
        assertEquals(CodeExecutionArgumentException.Code.DEPENDENCY_NOT_DECLARED, extra.code());
        var changed = assertThrows(CodeExecutionArgumentException.class, () -> script(MANIFEST)
                .dependencies("node", List.of(new Dependency("pptxgenjs", "4.0.1"))));
        assertEquals(CodeExecutionArgumentException.Code.DEPENDENCY_VERSION_MISMATCH, changed.code());
    }

    @Test
    void customSkillWithoutDependencyManifestUsesExplicitDeclarations() {
        assertEquals(FROZEN, new FrozenScriptResolver.Script("", "scripts/test.mjs", List.of())
                .dependencies("node", FROZEN));
        assertEquals(FROZEN, script("{}").dependencies("node", FROZEN));
        String empty = "{\"entry\":{\"language\":\"node\",\"script_path\":\"scripts/test.mjs\"},\"dependencies\":[]}";
        assertEquals(List.of(), script(empty).dependencies("node", List.of()));
        assertThrows(CodeExecutionArgumentException.class, () -> script(empty)
                .dependencies("node", FROZEN));
    }

    @Test
    void actualWebAppManifestSelectsReactDependenciesWithoutLeakingIntoPythonEntry() throws Exception {
        String manifest;
        try (var input = getClass().getClassLoader().getResourceAsStream("official-skills/web-app/manifest.json")) {
            manifest = new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        var react = script(manifest, "scripts/build_react_app.mjs").dependencies("node", List.of());
        assertEquals(6, react.size());
        org.junit.jupiter.api.Assertions.assertTrue(react.contains(new Dependency("@babel/standalone", "7.28.4")));
        org.junit.jupiter.api.Assertions.assertTrue(react.contains(new Dependency("react", "18.3.1")));
        assertEquals(react, script(manifest, "scripts/build_react_app.mjs")
                .dependencies("node", List.of(new Dependency("react", "18.3.1"))));
        assertEquals(List.of(), script(manifest, "scripts/generate_web_app.py").dependencies("python", List.of()));
        var extra = assertThrows(CodeExecutionArgumentException.class, () -> script(manifest, "scripts/build_react_app.mjs")
                .dependencies("node", List.of(new Dependency("lodash", "4.17.21"))));
        assertEquals(CodeExecutionArgumentException.Code.DEPENDENCY_NOT_DECLARED, extra.code());
        var drift = assertThrows(CodeExecutionArgumentException.class, () -> script(manifest, "scripts/build_react_app.mjs")
                .dependencies("node", List.of(new Dependency("react", "19.0.0"))));
        assertEquals(CodeExecutionArgumentException.Code.DEPENDENCY_VERSION_MISMATCH, drift.code());
        assertManifestError(manifest, "scripts/unknown.mjs", "node");
        assertManifestError(manifest, "scripts/build_react_app.mjs", "python");
    }

    @Test
    void unknownConflictingAndMalformedEntriesCannotFallBackToMainDependencies() {
        String main = "\"entry\":{\"language\":\"node\",\"script_path\":\"scripts/test.mjs\"},\"dependencies\":[]";
        for (String alternate : List.of("null", "{}", "[null]",
                "[{\"language\":\"node\",\"script_path\":\"scripts/test.mjs\",\"dependencies\":[]}]",
                "[{\"language\":\"node\",\"script_path\":\"scripts/other.mjs\"}]",
                "[{\"language\":\"python\",\"script_path\":\"scripts/other.mjs\",\"dependencies\":[]}]",
                "[{\"language\":\"node\",\"script_path\":\"scripts/../other.mjs\",\"dependencies\":[]}]",
                "[{\"language\":\"node\",\"script_path\":\"scripts/other.mjs\",\"dependencies\":[{\"name\":\"react\",\"version\":\"latest\"}]}]")) {
            assertManifestError("{" + main + ",\"alternate_entries\":" + alternate + "}", "scripts/test.mjs", "node");
        }
        assertManifestError("{\"alternate_entries\":[]}", "scripts/test.mjs", "node");
        assertManifestError("{\"dependencies\":[]}", "scripts/test.mjs", "node");
    }

    private static void assertManifestError(String manifest, String path, String language) {
        var error = assertThrows(CodeExecutionArgumentException.class,
                () -> script(manifest, path).dependencies(language, List.of()));
        assertEquals(CodeExecutionArgumentException.Code.FROZEN_MANIFEST_INVALID, error.code());
    }

    @Test
    void invalidFrozenConfigurationIsReportedAsManifestError() {
        for (String invalid : List.of("{", "[]", "null", "{\"dependencies\":null}",
                "{\"dependencies\":[{\"name\":\"pptxgenjs\",\"version\":\"latest\"}]}",
                "{\"dependencies\":[{\"name\":\"sharp\",\"version\":\"0.33.5\"},{\"name\":\"sharp\",\"version\":\"0.33.5\"}]}")) {
            var error = assertThrows(CodeExecutionArgumentException.class,
                    () -> script(invalid).dependencies("node", List.of()));
            assertEquals(CodeExecutionArgumentException.Code.FROZEN_MANIFEST_INVALID, error.code());
            assertEquals("/skill_handle", error.field());
        }
    }

    /** 整包传递保留目录和二进制模板，但不允许路径、摘要或声明长度替换。 */
    @Test
    void activatedPackageBytesAndPathsAreVerifiedBeforeEitherExecutionEntry() throws Exception {
        byte[] template={(byte)0xff,0,42};
        var text=resource("scripts/check.sh","printf ok".getBytes(StandardCharsets.UTF_8),"UTF8");
        var binary=resource("assets/template.bin",template,"BASE64");
        assertEquals(List.of(text,binary),FrozenScriptResolver.verifiedResources(List.of(text,binary)));
        for(var changed:List.of(
                new FrozenSkillResource(binary.path(),binary.encoding(),Base64.getEncoder().encodeToString(new byte[]{1,2,3}),binary.sha256(),binary.size()),
                new FrozenSkillResource(binary.path(),binary.encoding(),binary.content(),binary.sha256(),binary.size()+1),
                new FrozenSkillResource("../escape",text.encoding(),text.content(),text.sha256(),text.size()),
                new FrozenSkillResource("/tmp/escape",text.encoding(),text.content(),text.sha256(),text.size())))
            assertThrows(RuntimeException.class,()->FrozenScriptResolver.verifiedResources(List.of(changed)));
        assertThrows(IllegalStateException.class,()->FrozenScriptResolver.verifiedResources(List.of(text,text)));
    }

    /** 当前槽位与原context共同证明已提交激活，整包内容仍仅来自原Catalog。 */
    @Test
    void activeCommittedSkillsUseOriginalCatalogAndNeverRebuildExecution() throws Exception {
        var fixture=new ExecutionFixture();
        assertEquals(List.of(fixture.root,fixture.script),fixture.resolver.resolveResources(fixture.context,"skill:original"));
        verify(fixture.worker).activeAgentMaterials(fixture.identity,fixture.contextRef);
        verify(fixture.store).material(fixture.identity.runRef().tenantId(),fixture.catalogMaterial.reference());
        verifyNoMoreInteractions(fixture.worker);
        when(fixture.worker.activeAgentMaterials(fixture.identity,fixture.contextRef)).thenReturn(Optional.empty());
        assertEquals("skill_active_execution_missing",assertThrows(IllegalStateException.class,
                ()->fixture.resolver.resolveResources(fixture.context,"skill:original")).getMessage());
    }

    /** 未激活包、当前目录替换与不同Run身份均明确拒绝，不能读宿主路径或猜另一个版本。 */
    @Test
    void missingActivationChangedCatalogAndWrongRunCannotSupplyScript() throws Exception {
        var fixture=new ExecutionFixture();
        when(fixture.active.skills()).thenReturn(new AgentExecutionSkills.State(new SkillActivation.State(List.of()),List.of()));
        assertEquals("skill_not_activated",assertThrows(IllegalStateException.class,
                ()->fixture.resolver.resolveResources(fixture.context,"skill:original")).getMessage());
        when(fixture.configuration.skills()).thenReturn(List.of());
        assertEquals("skill_frozen_catalog_mismatch",assertThrows(IllegalStateException.class,
                ()->fixture.resolver.resolveResources(fixture.context,"skill:original")).getMessage());
        var wrong=new RunIdentity(fixture.identity.owner(), new AgentId((fixture.identity.owner()).target().id()), new InputId("other-input"), fixture.identity.runRef(), fixture.identity.trace(), fixture.identity.causationIdentityRef(), new RunRequester.EndUser((fixture.identity.owner()).requireUser()), java.util.Optional.empty(), new RunOriginRef((fixture.identity.owner()).sessionRef().tenantId(), "input:" + (new InputId("other-input")).value()));
        when(fixture.run.identity()).thenReturn(wrong);
        assertEquals("skill_run_mismatch",assertThrows(IllegalStateException.class,
                ()->fixture.resolver.resolveResources(fixture.context,"skill:original")).getMessage());
    }

    /** Program child没有Agent M，资源只能取真实原父当前已提交激活，不能猜子目录或重建执行上下文。 */
    @Test void programChildConsumesParentCommittedActivationWithoutBorrowingChildAgentMaterials() throws Exception {
        var f=new ExecutionFixture();var origin=f.identity;
        var child=new RunIdentity(origin.owner(), new AgentId((origin.owner()).target().id()), new InputId("program-input"), new RunRef(origin.runRef().tenantId(),"program-child"), origin.trace(), origin.causationIdentityRef(), new RunRequester.EndUser((origin.owner()).requireUser()), java.util.Optional.empty(), new RunOriginRef((origin.owner()).sessionRef().tenantId(), "input:" + (new InputId("program-input")).value()));
        var childRun=mock(ExecutionStore.Run.class);when(childRun.identity()).thenReturn(child);when(f.store.load(child.runRef())).thenReturn(Optional.of(childRun));
        var programTools=f.origins.getObject();
        doReturn(origin).when(programTools).resourceOrigin(child);
        var context=new PlatformToolExecutionContext(new PortCallContext(child,f.context.callContext().identity(),f.context.callContext().control()),"child-call","child-attempt",true);
        assertEquals(List.of(f.root,f.script),f.resolver.resolveResources(context,"skill:original"));
        verify(f.worker).activeAgentMaterials(origin,f.contextRef);verifyNoMoreInteractions(f.worker);
        verify(f.store,never()).material(child.runRef().tenantId(),AgentRuntimeExecutionCatalog.reference(child.runRef()));
        when(f.worker.activeAgentMaterials(origin,f.contextRef)).thenReturn(Optional.empty());
        assertEquals("skill_active_execution_missing",assertThrows(IllegalStateException.class,()->f.resolver.resolveResources(context,"skill:original")).getMessage());
    }

    /** 实际包、激活状态与准入材料分别拥有来源；fixture只替换I/O，不伪造旧checkpoint。 */
    private static final class ExecutionFixture {
        final ExecutionStore store=mock(ExecutionStore.class);
        final AgentRuntimeExecutionCatalog catalogs=mock(AgentRuntimeExecutionCatalog.class);
        final AgentRuntimeExecutionWorker worker=mock(AgentRuntimeExecutionWorker.class);
        final ObjectProvider<com.skillforge.institution.runtime.AgentRuntimeProgramTools> origins=com.skillforge.institution.runtime.ProgramResourceOriginFixture.ordinary(store);
        final AgentExecutionProgram.Materials active=mock(AgentExecutionProgram.Materials.class);
        final AgentExecutionProgram.Configuration configuration=mock(AgentExecutionProgram.Configuration.class);
        final ExecutionStore.Run run=mock(ExecutionStore.Run.class);
        final ResultRef contextRef=new ResultRef("committed-context");
        final RunIdentity identity;
        final PlatformToolExecutionContext context;
        final ExecutionStore.Material catalogMaterial;
        final FrozenSkillResource root=resource("SKILL.md","original skill".getBytes(StandardCharsets.UTF_8),"UTF8");
        final FrozenSkillResource script=resource("scripts/check.py","print('original')".getBytes(StandardCharsets.UTF_8),"UTF8");
        final FrozenScriptResolver resolver;

        ExecutionFixture() throws Exception {
            var tenant=new TenantId("tenant");var input=new InputId("input");
            var owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("agent"),new EndUserId(9));
            identity=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"run"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("origin"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
            var actor=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant,owner.requireUser()),owner,Set.of(),1,new ResultRef("verified"));
            context=new PlatformToolExecutionContext(new PortCallContext(identity,actor,new InvocationControl(java.time.Instant.now().plusSeconds(60),()->false)),"invocation","attempt");
            var capability=new CapabilityRef(CapabilityType.SKILL,"skill","version");
            var resources=List.of(root,script).stream().map(value->new SkillActivation.Resource(value.path(),"text/plain",value.encoding(),value.content(),value.sha256(),value.size())).toList();
            var mount=new SkillActivation.Mount("skill:original",capability,SkillActivation.Mode.ON_DEMAND,1000,
                    new SkillActivation.Descriptor(true,true,SkillActivation.Mode.ON_DEMAND,new SkillActivation.Metadata("skill","original",List.of()),resources,Optional.empty(),List.of(),1));
            catalogMaterial=new ExecutionStore.Material(AgentRuntimeExecutionCatalog.reference(identity.runRef()),new TypedPayload("fixture.catalog","{}"));
            var catalog=mock(AgentRuntimeExecutionCatalog.FrozenCatalog.class);
            when(run.identity()).thenReturn(identity);when(run.progress()).thenReturn(new ExecutionStore.Progress(1,0,0,contextRef));
            when(store.load(identity.runRef())).thenReturn(Optional.of(run));
            when(worker.activeAgentMaterials(identity,contextRef)).thenReturn(Optional.of(active));
            when(store.material(tenant,catalogMaterial.reference())).thenReturn(Optional.of(catalogMaterial));
            when(catalogs.read(catalogMaterial,identity)).thenReturn(catalog);
            when(catalog.skills()).thenReturn(List.of(mount));when(catalog.availableCapabilities()).thenReturn(Set.of(capability));
            when(active.configuration()).thenReturn(configuration);when(configuration.skills()).thenReturn(List.of(mount));
            when(configuration.availableCapabilities()).thenReturn(Set.of(capability));
            when(active.skills()).thenReturn(new AgentExecutionSkills.State(new SkillActivation.State(List.of(mount.handle())),List.of()));
            @SuppressWarnings("unchecked") var provider=(ObjectProvider<AgentRuntimeExecutionWorker>)mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(worker);resolver=new FrozenScriptResolver(store,catalogs,provider,origins);
        }
    }

    /** 测试材料声明与真实原始字节相同的冻结摘要。 */
    private static FrozenSkillResource resource(String path,byte[] content,String encoding) throws Exception {
        return new FrozenSkillResource(path,encoding,"UTF8".equals(encoding)?new String(content,StandardCharsets.UTF_8)
                :Base64.getEncoder().encodeToString(content),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),content.length);
    }

    private static FrozenScriptResolver.Script script(String manifest) {
        return script(manifest, "scripts/test.mjs");
    }

    private static FrozenScriptResolver.Script script(String manifest, String path) {
        // resolve 已校验资源散列；这里只验证随后清单到 Runner 依赖的契约。
        return new FrozenScriptResolver.Script("", path, List.of(
                new FrozenSkillResource("manifest.json", "UTF8", manifest, "verified", manifest.length())));
    }
}
