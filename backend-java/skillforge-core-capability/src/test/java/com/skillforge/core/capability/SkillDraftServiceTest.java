package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.shared.exception.ApiException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** 纯编辑校验、来源与幂等分支；事务/RLS 另由真实数据库验收。 */
class SkillDraftServiceTest {
    private final CapabilityCatalogScope scope = CapabilityCatalogScope.tenant(7L);
    private final CapabilityCatalogService catalog = mock(CapabilityCatalogService.class);
    private final CapabilityCatalogDatabaseContext context = mock(CapabilityCatalogDatabaseContext.class);
    private final SkillDraftRepository repository = mock(SkillDraftRepository.class);
    private final CapabilityDescriptorCodec codec = new CapabilityDescriptorCodec(new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    private final SkillDraftService service = new SkillDraftService(catalog, context, codec, repository);

    @Test
    void createSkillDelegatesMetadataToSkillDefinitionAndReturnsItsInitialDraft() {
        var onboarding = spy(service);
        var draft = SkillDraftView.of(12L, 11L, null, 1,
                new SkillDraftContent("", Map.of(), true, true, SkillActivationMode.ON_DEMAND, null, List.of()),
                null, Instant.EPOCH, null);
        when(catalog.createDefinition(scope, CapabilityType.SKILL, "sample", "示例技能", "技能说明"))
                .thenReturn(definition(7L));
        doReturn(draft).when(onboarding).create(scope, 11L, null);

        assertSame(draft, onboarding.createSkill(scope, "sample", "示例技能", "技能说明"));
        assertNull(draft.requiresPlan(), "编辑草稿未解析，不能宣称按需计划");

        var order = inOrder(catalog, onboarding);
        order.verify(catalog).createDefinition(scope, CapabilityType.SKILL, "sample", "示例技能", "技能说明");
        order.verify(onboarding).create(scope, 11L, null);
        verifyNoMoreInteractions(catalog);
        verifyNoInteractions(repository, context);
    }

    @Test
    void createSkillPropagatesDefinitionFailureWithoutStartingADraft() {
        var onboarding = spy(service);
        var rejected = new IllegalArgumentException("技能标识已存在");
        when(catalog.createDefinition(scope, CapabilityType.SKILL, "sample", "示例技能", null))
                .thenThrow(rejected);

        assertSame(rejected, assertThrows(IllegalArgumentException.class,
                () -> onboarding.createSkill(scope, "sample", "示例技能", null)));

        verify(onboarding, never()).create(any(), any(), any());
        verifyNoInteractions(repository, context);
    }

    /** 纯单测只证明异常向外层事务传播；Definition 与 draft 的整体回滚由真实 PG 验收。 */
    @Test
    void createSkillPropagatesDraftFailureInsteadOfReturningAPartialDefinition() {
        var onboarding = spy(service);
        var failed = new IllegalStateException("草稿写入失败");
        when(catalog.createDefinition(scope, CapabilityType.SKILL, "sample", "示例技能", "技能说明"))
                .thenReturn(definition(7L));
        doThrow(failed).when(onboarding).create(scope, 11L, null);

        assertSame(failed, assertThrows(IllegalStateException.class,
                () -> onboarding.createSkill(scope, "sample", "示例技能", "技能说明")));

        verify(catalog).createDefinition(scope, CapabilityType.SKILL, "sample", "示例技能", "技能说明");
        verifyNoMoreInteractions(catalog);
        verifyNoInteractions(repository, context);
    }

    @Test
    void saveKeepsIndependentPlatformVersionAndAllowsIncompleteFiles() {
        var content = content("---\nname: sample\ndescription: Test\nmetadata:\n  version: \"2.0.0\"\n---\nInstructions");
        stub(content, null);
        when(repository.save(eq(scope), eq(12L), eq(2L), any())).thenReturn(true);
        service.save(scope, 12L, 2, content);
        verify(repository).save(eq(scope), eq(12L), eq(2L), argThat(saved ->
                saved.versionKey().equals("v1") && saved.files().equals(content.files())));
        var incomplete = content("---\nname: [unfinished");
        service.save(scope, 12L, 2, incomplete);
        verify(repository).save(eq(scope), eq(12L), eq(2L), argThat(saved ->
                saved.versionKey().equals("v1") && saved.files().equals(incomplete.files())));
    }

    @Test
    void saveResolvesCurrentPublishedDependenciesWithinScopeAndRejectsUnavailable() {
        var input = new SkillDraftContent("v1", Map.of(), true, true, SkillActivationMode.ON_DEMAND, null,
                List.of(new CapabilityRef(CapabilityType.TOOL, "21", "old"),
                        new CapabilityRef(CapabilityType.TOOL, "22", "old2")));
        stub(input, null);
        when(catalog.listRequirementOptions(scope, CapabilityType.TOOL)).thenReturn(List.of(
                new CapabilityRequirementOption(21L, "one", 31L, "v2"),
                new CapabilityRequirementOption(22L, "two", 32L, "v3")));
        when(repository.save(eq(scope), eq(12L), eq(2L), any())).thenReturn(true);
        service.save(scope, 12L, 2, input);
        verify(catalog, times(1)).listRequirementOptions(scope, CapabilityType.TOOL);
        verify(repository).save(eq(scope), eq(12L), eq(2L), argThat(saved -> saved.requiredCapabilities().equals(
                List.of(new CapabilityRef(CapabilityType.TOOL, "21", "31"),
                        new CapabilityRef(CapabilityType.TOOL, "22", "32")))));
        when(catalog.listRequirementOptions(scope, CapabilityType.TOOL)).thenReturn(List.of());
        assertThrows(ApiException.class, () -> service.save(scope, 12L, 2, input));
        verify(repository, times(1)).save(eq(scope), eq(12L), eq(2L), any());
    }

    @Test
    void upstreamVersionMayDifferFromPlatformVersionWithoutOverwritingStoredFiles() {
        stub(content("---\nname: sample\ndescription: Test\nmetadata:\n  version: v2\n---\nInstructions"), 33L);
        var result = service.validate(scope, 12L, 2);
        assertTrue(result.valid());
        when(repository.lockDefinition(scope, 11L)).thenReturn(true);
        assertEquals(new SkillDraftPublication(33L, 5), service.publish(scope, 12L, 2, 4));
        verify(repository, never()).save(any(), anyLong(), anyLong(), any());
        verify(catalog, never()).createDraftVersion(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void validatesUnsavedContentAndResolvesDependenciesWithoutWritingDraft() {
        var existing = content("unfinished");
        stub(existing, null);
        var edited = content("---\nname: sample\ndescription: Test\n---\nInstructions");
        when(catalog.listRequirementOptions(scope, CapabilityType.TOOL)).thenReturn(List.of(
                new CapabilityRequirementOption(21L, "tool", 31L, "v1")));
        assertTrue(service.validateSelected(scope, 12L, 2, edited,
                List.of(new SkillDependencySelection(CapabilityType.TOOL, "21"))).valid());
        verify(repository, never()).save(any(), anyLong(), anyLong(), any());
        assertThrows(ApiException.class, () -> service.validateSelected(scope, 12L, 1, edited, List.of()));
        assertThrows(ApiException.class, () -> service.validateSelected(scope, 12L, 2, edited,
                List.of(new SkillDependencySelection(CapabilityType.TOOL, "99"))));
    }

    @Test
    void importZipKeepsPlatformVersionAndPreservesUpstreamBytes() throws Exception {
        var initial = content("unfinished");
        stub(initial, null);
        when(repository.save(eq(scope), eq(12L), eq(2L), any())).thenReturn(true);
        String original = "---\r\nname: sample\r\ndescription: Test\r\nmetadata:\r\n  version: upstream-v9\r\n"
                + "homepage: https://example.com\r\n---\r\nInstructions  \r\n";
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("sample/SKILL.md"));
            zip.write(original.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        service.importZip(scope, 12L, 2, output.toByteArray());
        verify(repository).save(eq(scope), eq(12L), eq(2L), argThat(saved ->
                saved.versionKey().equals("v1") && new String(Base64.getDecoder().decode(
                        saved.files().get("SKILL.md")), StandardCharsets.UTF_8).equals(original)));
    }

    @Test
    void readsCatalogVersionIndependentlyAndClearsOnlyPlatformVersionWhenRestoringPublishedPackage() {
        var source = content("---\nname: sample\ndescription: Test\nmetadata:\n  version: upstream-v9\n---\nInstructions");
        stub(source, null);
        var skillPackage = new SkillPackageValidator().fromDirectory(Map.of("SKILL.md",
                Base64.getDecoder().decode(source.files().get("SKILL.md"))));
        var descriptor = SkillPackageDescriptorMapper.descriptor(skillPackage, true, true,
                SkillActivationMode.ON_DEMAND, null, List.of());
        var version = new CapabilityVersionView(33L, 11L, 7L, CapabilityType.SKILL, "platform-v3", "sample", "Test",
                "TENANT", descriptor, new SkillPackageValidator().packageSha256(skillPackage), "PUBLISHED",
                Instant.EPOCH, Instant.EPOCH);
        when(catalog.getVersion(scope, 33L)).thenReturn(version);
        assertEquals("platform-v3", service.version(scope, 33L).versionKey());
        var current = SkillDraftView.of(12L, 11L, null, 2, source, null, Instant.EPOCH, null);
        when(repository.lockDefinition(scope, 11L)).thenReturn(true);
        when(repository.active(scope, 11L)).thenReturn(Optional.of(new SkillDraftRepository.Stored(current, null)));
        when(repository.restore(eq(scope), eq(12L), eq(2L), eq(33L), any())).thenReturn(true);
        service.create(scope, 11L, 33L);
        verify(repository).restore(eq(scope), eq(12L), eq(2L), eq(33L), argThat(restored ->
                restored.versionKey().isEmpty() && restored.files().equals(source.files())));
    }

    @Test
    void exactVersionReturnsPlanDeclarationFromItsSingleDecodedPackage() {
        var observedCodec = spy(new CapabilityDescriptorCodec(new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)));
        var reader = new SkillDraftService(catalog, context, observedCodec, repository);
        long id = 33L;
        for (var requirement : List.of("required", "optional")) {
            String source = "---\nname: sample\ndescription: Test\nmetadata:\n  skillforge-plan: " + requirement + "\n---\nInstructions";
            var skillPackage = new SkillPackageValidator().fromDirectory(Map.of("SKILL.md", source.getBytes(StandardCharsets.UTF_8)));
            var descriptor = SkillPackageDescriptorMapper.descriptor(skillPackage, true, true,
                    SkillActivationMode.ON_DEMAND, null, List.of());
            var version = new CapabilityVersionView(id, 11L, 7L, CapabilityType.SKILL, "v" + id,
                    "sample", "Test", "TENANT", descriptor, new SkillPackageValidator().packageSha256(skillPackage),
                    "PUBLISHED", Instant.EPOCH, Instant.EPOCH);
            when(catalog.getVersion(scope, id)).thenReturn(version);
            var result = reader.version(scope, id);
            assertEquals(Boolean.valueOf("required".equals(requirement)), result.requiresPlan());
            assertEquals(source, new String(Base64.getDecoder().decode(result.files().get("SKILL.md")), StandardCharsets.UTF_8));
            verify(catalog).getVersion(scope, id);
            id++;
        }
        verify(observedCodec, times(2)).decode(any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(repository);
    }

    @Test
    void unsupportedExtensionsAreVisibleWarningsRatherThanPackageErrorsOrPermissions() {
        var content = content("---\nname: sample\ndescription: Test\nmodel: external-model\n"
                + "allowed-tools: [Bash]\ncompatibility: Requires an external API\n---\nInstructions");
        stub(content, null);
        var result = service.validate(scope, 12L, 2);
        assertTrue(result.valid());
        assertEquals(List.of("SKILL_EXECUTION_EXTENSION_UNSUPPORTED", "SKILL_COMPATIBILITY_DECLARED",
                "SKILL_TOOL_DECLARATIONS_ONLY"), result.issues().stream().map(SkillDraftValidation.Issue::code).toList());
        assertEquals(List.of(SkillDraftValidation.Severity.WARNING, SkillDraftValidation.Severity.INFO,
                SkillDraftValidation.Severity.INFO), result.issues().stream().map(SkillDraftValidation.Issue::severity).toList());
        verify(repository, never()).save(any(), anyLong(), anyLong(), any());
        verify(catalog, never()).createDraftVersion(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unfinishedYamlReportsRealFileAndParserLineWithoutCreatingVersion() {
        stub(content("---\nname: [broken\ndescription: text\n---\n"), null);
        var result = service.validate(scope, 12L, 2);
        assertFalse(result.valid());
        assertEquals("SKILL.md", result.issues().getFirst().path());
        assertNotNull(result.issues().getFirst().line());
        assertEquals(SkillDraftValidation.Severity.ERROR, result.issues().getFirst().severity());
        verify(catalog, never()).createDraftVersion(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingEntrypointDoesNotBecomeRuntimeVersion() {
        stub(new SkillDraftContent("v1", Map.of(), true, true, SkillActivationMode.ON_DEMAND, null, List.of()), null);
        assertFalse(service.validate(scope, 12L, 2).valid());
        when(repository.lockDefinition(scope, 11L)).thenReturn(true);
        assertThrows(ApiException.class, () -> service.publish(scope, 12L, 2, 4));
        verify(catalog, never()).createDraftVersion(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void completePackageValidatesAndPublishedRetryReturnsOriginalReceipt() {
        stub(content("---\nname: sample\ndescription: Test skill\n---\nDo work"), 33L);
        assertTrue(service.validate(scope, 12L, 2).valid());
        when(repository.lockDefinition(scope, 11L)).thenReturn(true);
        assertEquals(new SkillDraftPublication(33L, 5), service.publish(scope, 12L, 2, 4));
        assertThrows(ApiException.class, () -> service.publish(scope, 12L, 1, 4));
        verify(catalog, never()).publishVersion(any(), any(), any(), anyLong());
    }

    @Test
    void foreignDefinitionCannotSeedAnEditingDraft() {
        when(repository.lockDefinition(scope, 11L)).thenReturn(true);
        when(catalog.getDefinition(scope, 11L)).thenReturn(definition(null));
        assertThrows(ApiException.class, () -> service.create(scope, 11L, 4L));
        verify(repository, never()).insert(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void staleSaveDoesNotTouchTheStoredContent() {
        var content = content("unfinished instructions");
        stub(content, null);
        assertThrows(ApiException.class, () -> service.save(scope, 12L, 1, content));
        verify(repository, never()).save(any(), anyLong(), anyLong(), any());
    }

    private void stub(SkillDraftContent content, Long published) {
        var view = SkillDraftView.of(12L, 11L, null, 2, content, published, Instant.EPOCH, null);
        var stored = new SkillDraftRepository.Stored(view, published == null ? null : 5L);
        when(repository.find(eq(scope), eq(12L), anyBoolean())).thenReturn(Optional.of(stored));
        when(catalog.getDefinition(scope, 11L)).thenReturn(definition(7L));
    }

    private CapabilityDefinitionView definition(Long tenantId) {
        return new CapabilityDefinitionView(11L, tenantId, CapabilityType.SKILL, "sample", "Sample", "",
                tenantId == null ? "PLATFORM" : "TENANT", "PUBLISHED", null, 4L, Instant.EPOCH, Instant.EPOCH);
    }

    private static SkillDraftContent content(String root) {
        return new SkillDraftContent("v1", Map.of("SKILL.md", Base64.getEncoder().encodeToString(root.getBytes(StandardCharsets.UTF_8))),
                true, true, SkillActivationMode.ON_DEMAND, null, List.of());
    }
}
