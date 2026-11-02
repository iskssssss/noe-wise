package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.CapabilityCatalogErrors;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityDefinitionView;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.capability.SkillActivationMode;
import com.skillforge.core.capability.SkillDraftContent;
import com.skillforge.core.capability.SkillDraftPublication;
import com.skillforge.core.capability.SkillDraftService;
import com.skillforge.core.capability.SkillDraftView;
import com.skillforge.core.capability.SkillPackage;
import com.skillforge.core.capability.SkillPackageDescriptorMapper;
import com.skillforge.core.capability.SkillPackageValidator;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;

/** 技能维护台编辑、发布、隔离与数据库约束的真实 PostgreSQL 验收。 */
class SkillDraftIntegrationTest extends AbstractIntegrationTest {

    private static final SkillPackageValidator PACKAGES = new SkillPackageValidator();

    @Autowired
    private SkillDraftService drafts;

    @Autowired
    private CapabilityCatalogService catalog;

    @Autowired
    private com.skillforge.institution.skill.SkillPublishService skillPublication;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private PlanMapper planMapper;

    @Autowired
    private DataSource dataSource;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void packageVersionLinksAcrossZipSavePublishAndHistoricalDraft() throws Exception {
        TenantSkill skill = tenantSkill("linked-version");
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            var created = drafts.create(skill.scope(), skill.definition().id(), null);
            String root = "---\nname: linked-skill\ndescription: Linked version\nmetadata:\n  version: v2\n---\nInstructions";
            var archive = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(archive)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("SKILL.md"));
                zip.write(bytes(root));
                zip.closeEntry();
            }
            var imported = drafts.importZip(skill.scope(), created.id(), created.revision(), archive.toByteArray());
            assertEquals("v2", imported.versionKey());
            assertTrue(drafts.validate(skill.scope(), imported.id(), imported.revision()).valid());
            var published = drafts.publish(skill.scope(), imported.id(), imported.revision(), skill.definition().revision());
            assertEquals("v2", catalog.getVersion(skill.scope(), published.versionId()).versionKey());
            var next = drafts.create(skill.scope(), skill.definition().id(), published.versionId());
            assertEquals("v2", next.versionKey());
            var saved = drafts.save(skill.scope(), next.id(), next.revision(), rawContent("manual-ignored",
                    Map.of("SKILL.md", bytes(root.replace("version: v2", "version: v3")))));
            assertEquals("v3", saved.versionKey());
            assertEquals("v2", catalog.getVersion(skill.scope(), published.versionId()).versionKey());
        }
    }

    @Test
    void linkedMetadataCannotRenumberExistingCatalogDraft() {
        TenantSkill skill = tenantSkill("fixed-version");
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            var base = createCatalogDraft(skill, validContent("v1", skill.skillName(), "Instructions", Map.of()));
            var draft = drafts.create(skill.scope(), skill.definition().id(), base.id());
            var saved = drafts.save(skill.scope(), draft.id(), draft.revision(), rawContent("v1", Map.of("SKILL.md",
                    bytes("---\nname: linked-skill\ndescription: Test\nmetadata:\n  version: v2\n---\nInstructions"))));
            assertEquals("v1", saved.versionKey());
            assertEquals("SKILL_VERSION_MISMATCH", drafts.validate(skill.scope(), saved.id(), saved.revision()).issues().getFirst().code());
            assertError(CapabilityCatalogErrors.DESCRIPTOR_INVALID,
                    () -> drafts.publish(skill.scope(), saved.id(), saved.revision(), skill.definition().revision()));
            assertEquals("DRAFT", catalog.getVersion(skill.scope(), base.id()).status());
        }
    }

    @Test
    void incompleteDraftsRoundTripAndCannotCreateRuntimeVersions() {
        TenantSkill skill = tenantSkill("unfinished");
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            SkillDraftView created = drafts.create(skill.scope(), skill.definition().id(), null);

            SkillDraftContent missingEntrypoint = new SkillDraftContent(
                    "v1", Map.of(), true, true, SkillActivationMode.ON_DEMAND, null, List.of());
            SkillDraftView missing = drafts.save(
                    skill.scope(), created.id(), created.revision(), missingEntrypoint);
            assertEquals(missingEntrypoint, missing.content());
            assertFalse(drafts.validate(skill.scope(), missing.id(), missing.revision()).valid());
            assertError(CapabilityCatalogErrors.DESCRIPTOR_INVALID,
                    () -> drafts.publish(skill.scope(), missing.id(), missing.revision(), 1));
            assertEquals(missingEntrypoint, drafts.get(skill.scope(), missing.id()).content());
            assertTrue(catalog.listVersions(skill.scope(), skill.definition().id(), null).isEmpty());

            String brokenYaml = "---\nname: [broken\ndescription: Still editing\n---\nBody";
            SkillDraftContent broken = rawContent("v1", Map.of("SKILL.md", bytes(brokenYaml)));
            SkillDraftView saved = drafts.save(skill.scope(), missing.id(), missing.revision(), broken);
            assertEquals(broken, drafts.get(skill.scope(), saved.id()).content());
            var validation = drafts.validate(skill.scope(), saved.id(), saved.revision());
            assertFalse(validation.valid());
            assertEquals("SKILL.md", validation.issues().getFirst().path());
            assertNotNull(validation.issues().getFirst().line());
            assertError(CapabilityCatalogErrors.DESCRIPTOR_INVALID,
                    () -> drafts.publish(skill.scope(), saved.id(), saved.revision(), 1));
            assertEquals(broken, drafts.get(skill.scope(), saved.id()).content());
            assertTrue(catalog.listVersions(skill.scope(), skill.definition().id(), null).isEmpty());
        }
    }

    @Test
    void publishReceiptIsIdempotentAndLaterPublicationAdvancesCurrentWithoutMutatingHistory() {
        TenantSkill skill = tenantSkill("immutable");
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            SkillDraftView firstDraft = drafts.create(skill.scope(), skill.definition().id(), null);
            SkillDraftView firstSaved = drafts.save(skill.scope(), firstDraft.id(), firstDraft.revision(),
                    validContent("v1", skill.skillName(), "Original instructions", Map.of()));
            SkillDraftPublication first = drafts.publish(
                    skill.scope(), firstSaved.id(), firstSaved.revision(), 1);

            assertEquals(first, drafts.publish(
                    skill.scope(), firstSaved.id(), firstSaved.revision(), 1));
            SkillDraftView original = drafts.version(skill.scope(), first.versionId());
            CapabilityDefinitionView withDefault = catalog.setDefaultSkillVersion(
                    skill.scope(), skill.definition().id(), first.versionId(), first.definitionRevision());
            assertEquals(first.versionId(), withDefault.defaultVersionId());

            SkillDraftView secondDraft = drafts.create(
                    skill.scope(), skill.definition().id(), first.versionId());
            assertEquals(firstSaved.id(), secondDraft.id());
            assertEquals(firstSaved.revision() + 1, secondDraft.revision());
            assertEquals("v1", secondDraft.versionKey());
            SkillDraftView secondSaved = drafts.save(skill.scope(), secondDraft.id(), secondDraft.revision(),
                    validContent("v2", skill.skillName(), "New instructions", Map.of()));
            assertEquals(first.versionId(), catalog.getDefaultSkillVersion(
                    skill.scope(), skill.definition().id()).id());
            SkillDraftPublication second = drafts.publish(skill.scope(), secondSaved.id(),
                    secondSaved.revision(), withDefault.revision());

            assertNotEquals(first.versionId(), second.versionId());
            assertEquals(original, drafts.version(skill.scope(), first.versionId()));
            assertEquals(second.versionId(), catalog.getDefaultSkillVersion(
                    skill.scope(), skill.definition().id()).id());
            assertEquals(2, catalog.listVersions(
                    skill.scope(), skill.definition().id(), "PUBLISHED").size());
        }
    }

    @Test
    void binaryFilesRoundTripThroughDraftAndPublishedDescriptor() {
        TenantSkill skill = tenantSkill("binary");
        byte[] binary = new byte[]{0, -1, 17, 127, -128, 42};
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            SkillDraftView draft = drafts.create(skill.scope(), skill.definition().id(), null);
            SkillDraftView saved = drafts.save(skill.scope(), draft.id(), draft.revision(),
                    validContent("v1", skill.skillName(), "Read the binary asset",
                            Map.of("assets/payload.bin", binary)));
            assertArrayEquals(binary, decode(saved.files().get("assets/payload.bin")));

            SkillDraftPublication publication = drafts.publish(
                    skill.scope(), saved.id(), saved.revision(), 1);
            SkillDraftView published = drafts.version(skill.scope(), publication.versionId());
            assertArrayEquals(binary, decode(published.files().get("assets/payload.bin")));
            assertArrayEquals(bytes(skillMd(skill.skillName(), "Read the binary asset")),
                    decode(published.files().get("SKILL.md")));
        }
    }

    @Test
    void tenantsAreIsolatedAndOnlyPublishedPlatformPackagesAreReadable() {
        TenantSkill tenantA = tenantSkill("tenant-a");
        TenantSkill tenantB = tenantSkill("tenant-b");
        SkillDraftView tenantDraft;
        try (TenantBinding ignored = tenant(tenantA.tenantId())) {
            tenantDraft = drafts.create(tenantA.scope(), tenantA.definition().id(), null);
        }
        try (TenantBinding ignored = tenant(tenantB.tenantId())) {
            assertError(CapabilityCatalogErrors.NOT_FOUND,
                    () -> drafts.get(tenantB.scope(), tenantDraft.id()));
            assertError(CapabilityCatalogErrors.NOT_FOUND,
                    () -> drafts.create(tenantB.scope(), tenantA.definition().id(), null));
        }

        TenantContext.clear();
        CapabilityCatalogScope platform = CapabilityCatalogScope.platform();
        String platformName = uniqueName("platform");
        CapabilityDefinitionView platformDefinition = catalog.createDefinition(
                platform, CapabilityType.SKILL, uniqueKey("platform"), "Platform skill", "");
        SkillDraftView platformDraft = drafts.create(platform, platformDefinition.id(), null);
        SkillDraftView platformSaved = drafts.save(platform, platformDraft.id(), platformDraft.revision(),
                validContent("v1", platformName, "Public platform instructions", Map.of()));

        try (TenantBinding ignored = tenant(tenantA.tenantId())) {
            assertError(CapabilityCatalogErrors.NOT_FOUND,
                    () -> catalog.getDefinition(tenantA.scope(), platformDefinition.id()));
            assertError(CapabilityCatalogErrors.NOT_FOUND,
                    () -> drafts.get(tenantA.scope(), platformDraft.id()));
        }

        TenantContext.clear();
        SkillDraftPublication platformPublication = drafts.publish(
                platform, platformSaved.id(), platformSaved.revision(), platformDefinition.revision());
        try (TenantBinding ignored = tenant(tenantA.tenantId())) {
            SkillDraftView publicPackage = drafts.version(tenantA.scope(), platformPublication.versionId());
            assertEquals("Public platform instructions", body(publicPackage));
            assertError(CapabilityCatalogErrors.NOT_FOUND,
                    () -> drafts.create(tenantA.scope(), platformDefinition.id(), platformPublication.versionId()));
            assertError(CapabilityCatalogErrors.NOT_FOUND,
                    () -> drafts.save(tenantA.scope(), platformDraft.id(), platformSaved.revision(),
                            validContent("v2", platformName, "Tenant rewrite", Map.of())));
        }
    }

    @Test
    void staleRevisionDoesNotOverwriteLatestDraft() {
        TenantSkill skill = tenantSkill("revision");
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            SkillDraftView draft = drafts.create(skill.scope(), skill.definition().id(), null);
            SkillDraftContent winner = validContent(
                    "v1", skill.skillName(), "Committed edit", Map.of());
            SkillDraftView saved = drafts.save(skill.scope(), draft.id(), draft.revision(), winner);
            SkillDraftContent stale = validContent(
                    "v1", skill.skillName(), "Stale edit", Map.of());

            assertError(CapabilityCatalogErrors.REVISION_CONFLICT,
                    () -> drafts.save(skill.scope(), draft.id(), draft.revision(), stale));
            SkillDraftView recovered = drafts.get(skill.scope(), draft.id());
            assertEquals(saved.revision(), recovered.revision());
            assertEquals(winner, recovered.content());
        }
    }

    @Test
    void existingCatalogDraftIsUpdatedAndPublishedInPlace() {
        TenantSkill skill = tenantSkill("existing-draft");
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            SkillDraftContent original = validContent(
                    "v1", skill.skillName(), "Original catalog draft", Map.of());
            CapabilityVersionView base = createCatalogDraft(skill, original);
            SkillDraftView editing = drafts.create(
                    skill.scope(), skill.definition().id(), base.id());
            assertEquals(base.id(), editing.baseVersionId());
            assertEquals("v1", editing.versionKey());

            SkillDraftContent changed = validContent(
                    "v1", skill.skillName(), "Updated before publication", Map.of());
            SkillDraftView saved = drafts.save(
                    skill.scope(), editing.id(), editing.revision(), changed);
            SkillDraftPublication publication = drafts.publish(
                    skill.scope(), saved.id(), saved.revision(), 1);

            assertEquals(base.id(), publication.versionId());
            assertEquals(3, publication.definitionRevision());
            CapabilityVersionView published = catalog.getVersion(skill.scope(), base.id());
            assertEquals("PUBLISHED", published.status());
            assertEquals("Updated before publication", body(drafts.version(skill.scope(), base.id())));
            assertEquals(1, catalog.listVersions(skill.scope(), skill.definition().id(), null).size());
        }
    }

    @Test
    void reusingHistoricalVersionKeyCreatesAnIndependentPublicationWithoutMutatingHistory() {
        TenantSkill skill = tenantSkill("historical-key");
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            SkillDraftView firstDraft = drafts.create(skill.scope(), skill.definition().id(), null);
            SkillDraftView firstSaved = drafts.save(skill.scope(), firstDraft.id(), firstDraft.revision(),
                    validContent("v1", skill.skillName(), "Released body", Map.of()));
            SkillDraftPublication first = drafts.publish(
                    skill.scope(), firstSaved.id(), firstSaved.revision(), 1);
            SkillDraftView original = drafts.version(skill.scope(), first.versionId());

            SkillDraftView restored = drafts.create(
                    skill.scope(), skill.definition().id(), first.versionId());
            assertEquals(firstDraft.id(), restored.id());
            assertEquals(firstSaved.revision() + 1, restored.revision());
            SkillDraftContent nextContent = validContent(
                    "v1", skill.skillName(), "New body with historical display key", Map.of());
            SkillDraftView nextSaved = drafts.save(skill.scope(), restored.id(),
                    restored.revision(), nextContent);
            assertEquals(first.versionId(), catalog.getDefaultSkillVersion(
                    skill.scope(), skill.definition().id()).id());
            SkillDraftPublication next = drafts.publish(skill.scope(), nextSaved.id(),
                    nextSaved.revision(), first.definitionRevision());

            assertNotEquals(first.versionId(), next.versionId());
            assertEquals(original, drafts.version(skill.scope(), first.versionId()));
            assertEquals("v1", catalog.getVersion(skill.scope(), first.versionId()).versionKey());
            assertEquals("revision-" + next.versionId(),
                    catalog.getVersion(skill.scope(), next.versionId()).versionKey());
            assertEquals("New body with historical display key", body(drafts.version(skill.scope(), next.versionId())));
            assertEquals(next.versionId(), catalog.getDefaultSkillVersion(
                    skill.scope(), skill.definition().id()).id());
            SkillDraftView working = drafts.active(skill.scope(), skill.definition().id()).orElseThrow();
            assertEquals(firstDraft.id(), working.id());
            assertEquals(nextSaved.revision(), working.revision());
            assertEquals(nextContent, working.content());
            assertEquals(next.versionId(), working.publishedVersionId());
            assertEquals(2, catalog.listVersions(skill.scope(), skill.definition().id(), "PUBLISHED").size());
        }
    }

    @Test
    void receiptFailureRollsBackAlreadyPublishedVersionAndDefinition() throws Exception {
        TenantSkill skill = tenantSkill("receipt-rollback");
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            var created = drafts.create(skill.scope(), skill.definition().id(), null);
            var saved = drafts.save(skill.scope(), created.id(), created.revision(),
                    validContent("v1", skill.skillName(), "Atomic publication", Map.of()));
            String constraint = "skill_draft_receipt_failure_" + saved.id();
            // 仅在本次隔离数据库中，对本草稿的最后一步写入制造真实约束失败。
            try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE nw_skill_editing_drafts ADD CONSTRAINT " + constraint
                        + " CHECK (id <> " + saved.id() + " OR published_version_id IS NULL) NOT VALID");
            }
            try {
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                        () -> drafts.publish(skill.scope(), saved.id(), saved.revision(), 1));
                assertTrue(catalog.listVersions(skill.scope(), skill.definition().id(), null).isEmpty());
                assertEquals(1, catalog.getDefinition(skill.scope(), skill.definition().id()).revision());
                assertEquals("DRAFT", catalog.getDefinition(skill.scope(), skill.definition().id()).status());
                var recovered = drafts.active(skill.scope(), skill.definition().id()).orElseThrow();
                assertEquals(saved.content(), recovered.content());
                assertEquals(saved.revision(), recovered.revision());
                assertNull(recovered.publishedVersionId());
            } finally {
                try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE nw_skill_editing_drafts DROP CONSTRAINT " + constraint);
                }
            }
            assertNotNull(drafts.publish(skill.scope(), saved.id(), saved.revision(), 1).versionId());
        }
    }

    @Test
    void restrictedRoleEnforcesDraftRlsAndOneActiveDraftConstraint() throws Exception {
        TenantSkill tenantA = tenantSkill("rls-a");
        TenantSkill tenantB = tenantSkill("rls-b");
        SkillDraftView draftA;
        SkillDraftView draftB;
        try (TenantBinding ignored = tenant(tenantA.tenantId())) {
            draftA = drafts.create(tenantA.scope(), tenantA.definition().id(), null);
        }
        try (TenantBinding ignored = tenant(tenantB.tenantId())) {
            draftB = drafts.create(tenantB.scope(), tenantB.definition().id(), null);
        }
        TenantContext.clear();
        CapabilityDefinitionView platformDefinition = catalog.createDefinition(
                CapabilityCatalogScope.platform(), CapabilityType.SKILL,
                uniqueKey("rls-platform"), "RLS platform skill", "");
        SkillDraftView platformDraft = drafts.create(
                CapabilityCatalogScope.platform(), platformDefinition.id(), null);

        try (Connection connection = tenantConnection(tenantA.tenantId())) {
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM nw_skill_editing_drafts WHERE id IN (?, ?, ?)",
                    draftA.id(), draftB.id(), platformDraft.id()));
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO nw_skill_editing_drafts
                        (id, definition_id, tenant_id, content)
                    VALUES (?, ?, ?, '{}'::jsonb)
                    """)) {
                statement.setLong(1, SnowflakeIds.nextId());
                statement.setLong(2, tenantA.definition().id());
                statement.setLong(3, tenantA.tenantId());
                assertThrows(SQLException.class, statement::executeUpdate);
                connection.rollback();
            }
        }
        try (Connection connection = tenantConnection(tenantB.tenantId())) {
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM nw_skill_editing_drafts WHERE id IN (?, ?, ?)",
                    draftA.id(), draftB.id(), platformDraft.id()));
        }
        try (Connection connection = platformConnection()) {
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM nw_skill_editing_drafts WHERE id IN (?, ?, ?)",
                    draftA.id(), draftB.id(), platformDraft.id()));
        }
        try (Connection connection = restrictedConnection()) {
            setContext(connection, "", false);
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM nw_skill_editing_drafts WHERE id IN (?, ?, ?)",
                    draftA.id(), draftB.id(), platformDraft.id()));
        }
    }

    @Test
    void concurrentSavesSerializeAndExactlyOneRevisionWins() throws Exception {
        TenantSkill skill = tenantSkill("concurrent");
        SkillDraftView draft;
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            draft = drafts.create(skill.scope(), skill.definition().id(), null);
        }
        SkillDraftContent left = validContent("v1", skill.skillName(), "Left edit", Map.of());
        SkillDraftContent right = validContent("v1", skill.skillName(), "Right edit", Map.of());
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SaveOutcome> leftFuture = executor.submit(
                    () -> concurrentSave(skill, draft, left, start));
            Future<SaveOutcome> rightFuture = executor.submit(
                    () -> concurrentSave(skill, draft, right, start));
            start.await(5, TimeUnit.SECONDS);

            SaveOutcome leftOutcome = leftFuture.get(10, TimeUnit.SECONDS);
            SaveOutcome rightOutcome = rightFuture.get(10, TimeUnit.SECONDS);
            assertEquals(1, List.of(leftOutcome, rightOutcome).stream()
                    .filter(outcome -> outcome.saved() != null).count());
            SaveOutcome loser = leftOutcome.failure() == null ? rightOutcome : leftOutcome;
            assertNotNull(loser.failure());
            assertEquals(CapabilityCatalogErrors.REVISION_CONFLICT,
                    loser.failure().getCode());

            try (TenantBinding ignored = tenant(skill.tenantId())) {
                SkillDraftView recovered = drafts.get(skill.scope(), draft.id());
                assertEquals(draft.revision() + 1, recovered.revision());
                SkillDraftContent winner = leftOutcome.saved() != null ? left : right;
                assertEquals(winner, recovered.content());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void identityOnlyDependencySaveWorksThroughAuthenticatedHttpAndPostgres() throws Exception {
        TenantSkill owner = tenantSkill("dependency-http");
        String username = uniqueKey("http-admin"), password = "SkillHttpTest123";
        String slug;
        long draftId, dependencyId, expectedVersion;
        try (TenantBinding ignored = tenant(owner.tenantId())) {
            slug = tenantMapper.selectById(owner.tenantId()).getSlug();
            createTestTenantAdministrator(Long.toString(owner.tenantId()), username, password);
            String dependencyName = uniqueName("http-dependency");
            var dependency = catalog.createDefinition(owner.scope(), CapabilityType.SKILL, uniqueKey("http-dep"), dependencyName, "");
            var source = drafts.create(owner.scope(), dependency.id(), null);
            var sourceSaved = drafts.save(owner.scope(), source.id(), source.revision(), validContent("v1", dependencyName, "instructions", Map.of()));
            expectedVersion = drafts.publish(owner.scope(), sourceSaved.id(), sourceSaved.revision(), dependency.revision()).versionId();
            dependencyId = dependency.id();
            draftId = drafts.create(owner.scope(), owner.definition().id(), null).id();
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var http = java.net.http.HttpClient.newHttpClient();
        var root = "http://127.0.0.1:" + port + "/api/v1";
        var login = java.net.http.HttpRequest.newBuilder(java.net.URI.create(root + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "tenant_slug", slug, "username", username, "password", password)))).build();
        var loginResponse = http.send(login, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginResponse.statusCode(), loginResponse.body());
        String token = json.readTree(loginResponse.body()).path("data").path("access_token").asText();
        assertFalse(token.isBlank());
        var body = Map.of("draft_id", Long.toString(draftId), "version_key", "v1", "files", Map.of(),
                "model_invocable", true, "user_invocable", true, "activation_mode", "ON_DEMAND",
                "required_capabilities", List.of(Map.of("type", "SKILL", "capability_id", Long.toString(dependencyId))));
        var preview = java.net.http.HttpRequest.newBuilder(java.net.URI.create(root + "/capabilities/skill-drafts/validate-content"))
                .header("Authorization", "Bearer " + token).header("If-Match", "1").header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var previewResponse = http.send(preview, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, previewResponse.statusCode(), previewResponse.body());
        assertFalse(json.readTree(previewResponse.body()).path("data").path("valid").asBoolean());
        try (TenantBinding ignored = tenant(owner.tenantId())) {
            var unchanged = drafts.get(owner.scope(), draftId);
            assertEquals(1, unchanged.revision());
            assertTrue(unchanged.requiredCapabilities().isEmpty());
        }
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(root + "/capabilities/skill-drafts"))
                .header("Authorization", "Bearer " + token).header("If-Match", "1").header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(Long.toString(expectedVersion), json.readTree(response.body()).path("data").path("required_capabilities").get(0).path("version_id").asText());
        try (TenantBinding ignored = tenant(owner.tenantId())) {
            assertEquals(Long.toString(expectedVersion), drafts.get(owner.scope(), draftId).requiredCapabilities().getFirst().versionId());
        }
    }

    @Test
    void authenticatedPublishReturnsValidationAndPersistsVersion() throws Exception {
        TenantSkill owner = tenantSkill("publish-http");
        String username = uniqueKey("http-admin"), password = "SkillHttpTest123";
        String slug;
        SkillDraftView saved;
        try (TenantBinding ignored = tenant(owner.tenantId())) {
            slug = tenantMapper.selectById(owner.tenantId()).getSlug();
            createTestTenantAdministrator(Long.toString(owner.tenantId()), username, password);
            var draft = drafts.create(owner.scope(), owner.definition().id(), null);
            saved = drafts.save(owner.scope(), draft.id(), draft.revision(), validContent("v1", owner.skillName(), "instructions", Map.of()));
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var http = java.net.http.HttpClient.newHttpClient();
        var root = "http://127.0.0.1:" + port + "/api/v1";
        var login = java.net.http.HttpRequest.newBuilder(java.net.URI.create(root + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "tenant_slug", slug, "username", username, "password", password)))).build();
        var loginResponse = http.send(login, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginResponse.statusCode(), loginResponse.body());
        String token = json.readTree(loginResponse.body()).path("data").path("access_token").asText();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(root + "/capabilities/skill-drafts/publish"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "draft_id", Long.toString(saved.id()), "revision", saved.revision(),
                        "definition_revision", owner.definition().revision())))).build();
        var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var data = json.readTree(response.body()).path("data");
        assertTrue(data.path("validation").path("valid").asBoolean());
        try (TenantBinding ignored = tenant(owner.tenantId())) {
            assertEquals(data.path("version_id").asText(), Long.toString(drafts.get(owner.scope(), saved.id()).publishedVersionId()));
        }
    }

    @Test
    void dependencySelectionResolvesCurrentPublicationAndRejectsForeignTenantWithoutWriting() {
        TenantSkill owner = tenantSkill("dependency-owner");
        TenantSkill other = tenantSkill("dependency-foreign");
        try (TenantBinding ignored = tenant(other.tenantId())) {
            var foreignDraft = drafts.create(other.scope(), other.definition().id(), null);
            var foreignSaved = drafts.save(other.scope(), foreignDraft.id(), foreignDraft.revision(),
                    validContent("v1", other.skillName(), "foreign", Map.of()));
            drafts.publish(other.scope(), foreignSaved.id(), foreignSaved.revision(), other.definition().revision());
        }
        try (TenantBinding ignored = tenant(owner.tenantId())) {
            String dependencyName = uniqueName("dependency");
            var dependency = catalog.createDefinition(owner.scope(), CapabilityType.SKILL,
                    uniqueKey("dependency"), dependencyName, "");
            var dependencyDraft = drafts.create(owner.scope(), dependency.id(), null);
            var firstSaved = drafts.save(owner.scope(), dependencyDraft.id(), dependencyDraft.revision(),
                    validContent("v1", dependencyName, "first", Map.of()));
            var first = drafts.publish(owner.scope(), firstSaved.id(), firstSaved.revision(), dependency.revision());
            var restored = drafts.create(owner.scope(), dependency.id(), first.versionId());
            var secondSaved = drafts.save(owner.scope(), restored.id(), restored.revision(),
                    validContent("v2", dependencyName, "second", Map.of()));
            var second = drafts.publish(owner.scope(), secondSaved.id(), secondSaved.revision(), first.definitionRevision());
            var draft = drafts.create(owner.scope(), owner.definition().id(), null);
            var input = validContent("v1", owner.skillName(), "owner", Map.of());
            var selected = List.of(new com.skillforge.core.capability.SkillDependencySelection(CapabilityType.SKILL, dependency.id().toString()));
            var saved = drafts.saveSelected(owner.scope(), draft.id(), draft.revision(), input, selected);
            assertEquals(second.versionId().toString(), saved.requiredCapabilities().getFirst().versionId());
            assertNotEquals(first.versionId(), second.versionId());
            var foreign = List.of(new com.skillforge.core.capability.SkillDependencySelection(CapabilityType.SKILL, other.definition().id().toString()));
            assertThrows(ApiException.class, () -> drafts.saveSelected(owner.scope(), saved.id(), saved.revision(), input, foreign));
            assertEquals(saved, drafts.get(owner.scope(), saved.id()));
        }
    }

    @Test
    void combinedPublicationKeepsOriginalReceiptAndRollsBackRejectedContent() {
        TenantSkill skill = tenantSkill("combined");
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            long member = createTestTenantAdministrator(Long.toString(skill.tenantId()), uniqueKey("admin"), "test-password");
            var draft = drafts.create(skill.scope(), skill.definition().id(), null);
            var invalid = command(java.util.UUID.randomUUID(), draft, skill.definition().revision(),
                    new SkillDraftContent("v1", Map.of(), true, true, SkillActivationMode.ON_DEMAND, null, List.of()));
            var rejected = withAuthenticatedMember(member, () -> skillPublication.publish(skill.tenantId(), invalid));
            assertEquals("REJECTED", rejected.status());
            assertFalse(rejected.validation().valid());
            assertNotNull(rejected.errorMessage());
            assertEquals(draft, drafts.get(skill.scope(), draft.id()));
            assertTrue(catalog.listVersions(skill.scope(), skill.definition().id(), null).isEmpty());
            assertEquals(rejected, withAuthenticatedMember(member, () -> skillPublication.result(skill.tenantId(), invalid.requestId())));

            var content = validContent("v1", skill.skillName(), "Combined instructions", Map.of());
            var command = command(java.util.UUID.randomUUID(), draft, skill.definition().revision(), content);
            var published = withAuthenticatedMember(member, () -> skillPublication.publish(skill.tenantId(), command));
            assertEquals("SUCCEEDED", published.status());
            assertEquals(draft.revision() + 1, published.revision());
            assertEquals(content, drafts.get(skill.scope(), draft.id()).content());
            assertEquals(published, withAuthenticatedMember(member, () -> skillPublication.publish(skill.tenantId(), command)));
            assertEquals(published, withAuthenticatedMember(member, () -> skillPublication.result(skill.tenantId(), command.requestId())));
            var changed = command(command.requestId(), draft, skill.definition().revision(),
                    validContent("v2", skill.skillName(), "Changed", Map.of()));
            assertThrows(ApiException.class, () -> withAuthenticatedMember(member,
                    () -> skillPublication.publish(skill.tenantId(), changed)));
            assertEquals(1, catalog.listVersions(skill.scope(), skill.definition().id(), "PUBLISHED").size());
        }
    }

    @Test
    void combinedSuccessReceiptFailureRollsBackAllPublicationChanges() throws Exception {
        TenantSkill skill = tenantSkill("combined-rollback");
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            long member = createTestTenantAdministrator(Long.toString(skill.tenantId()), uniqueKey("admin"), "test-password");
            var draft = drafts.create(skill.scope(), skill.definition().id(), null);
            var command = command(java.util.UUID.randomUUID(), draft, skill.definition().revision(),
                    validContent("v1", skill.skillName(), "Atomic", Map.of()));
            String constraint = "skill_combined_receipt_" + draft.id();
            try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE nw_skill_publish_receipts ADD CONSTRAINT " + constraint
                        + " CHECK (request_id <> '" + command.requestId() + "'::uuid OR status <> 'SUCCEEDED') NOT VALID");
            }
            try {
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> withAuthenticatedMember(member,
                        () -> skillPublication.publish(skill.tenantId(), command)));
                assertEquals(draft, drafts.get(skill.scope(), draft.id()));
                assertTrue(catalog.listVersions(skill.scope(), skill.definition().id(), null).isEmpty());
                assertEquals("PROCESSING", withAuthenticatedMember(member,
                        () -> skillPublication.result(skill.tenantId(), command.requestId())).status());
            } finally {
                try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE nw_skill_publish_receipts DROP CONSTRAINT " + constraint);
                }
            }
        }
    }

    private static com.skillforge.institution.skill.SkillPublishService.Command command(java.util.UUID requestId,
            SkillDraftView draft, long definitionRevision, SkillDraftContent content) {
        return new com.skillforge.institution.skill.SkillPublishService.Command(requestId, draft.id(), draft.revision(),
                definitionRevision, content.versionKey(), content.files(), content.modelInvocable(), content.userInvocable(),
                content.activationMode(), content.conflictGroup(), content.requiredCapabilities().stream()
                        .map(ref -> new com.skillforge.core.capability.SkillDependencySelection(ref.type(), ref.capabilityId())).toList());
    }

    private SaveOutcome concurrentSave(
            TenantSkill skill, SkillDraftView draft, SkillDraftContent content, CyclicBarrier start)
            throws Exception {
        try (TenantBinding ignored = tenant(skill.tenantId())) {
            start.await(5, TimeUnit.SECONDS);
            try {
                return new SaveOutcome(drafts.save(
                        skill.scope(), draft.id(), draft.revision(), content), null);
            } catch (ApiException exception) {
                return new SaveOutcome(null, exception);
            }
        }
    }

    private TenantSkill tenantSkill(String prefix) {
        long tenantId = Long.parseLong(createTenant(
                tenantMapper, planMapper, prefix + "-" + SnowflakeIds.nextId()));
        CapabilityCatalogScope scope = CapabilityCatalogScope.tenant(tenantId);
        String skillName = uniqueName(prefix);
        try (TenantBinding ignored = tenant(tenantId)) {
            CapabilityDefinitionView definition = catalog.createDefinition(
                    scope, CapabilityType.SKILL, uniqueKey(prefix), skillName, "");
            return new TenantSkill(tenantId, scope, definition, skillName);
        }
    }

    private CapabilityVersionView createCatalogDraft(TenantSkill skill, SkillDraftContent content) {
        Map<String, byte[]> files = decodeFiles(content.files());
        SkillPackage skillPackage = PACKAGES.fromDirectory(files);
        return catalog.createDraftVersion(skill.scope(), skill.definition().id(), content.versionKey(),
                skillPackage.metadata().name(), skillPackage.metadata().description(),
                PACKAGES.packageSha256(skillPackage),
                SkillPackageDescriptorMapper.descriptor(skillPackage,
                        content.modelInvocable(), content.userInvocable(), content.activationMode(), content.conflictGroup(), content.requiredCapabilities()));
    }

    private Connection restrictedConnection() throws SQLException {
        ensureRestrictedRole();
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SET LOCAL ROLE sf_skill_draft_test")) {
            statement.execute();
        }
        return connection;
    }

    private Connection tenantConnection(long tenantId) throws SQLException {
        Connection connection = restrictedConnection();
        setContext(connection, Long.toString(tenantId), false);
        return connection;
    }

    private Connection platformConnection() throws SQLException {
        Connection connection = restrictedConnection();
        setContext(connection, "", true);
        return connection;
    }

    private void ensureRestrictedRole() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DO $$
                     BEGIN
                         IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sf_skill_draft_test') THEN
                             CREATE ROLE sf_skill_draft_test NOLOGIN NOBYPASSRLS;
                         END IF;
                     END $$;
                     GRANT USAGE ON SCHEMA public TO sf_skill_draft_test;
                     GRANT SELECT, INSERT, UPDATE, DELETE
                         ON nw_capability_definitions, nw_capability_versions, nw_skill_editing_drafts
                         TO sf_skill_draft_test
                     """)) {
            statement.execute();
        }
    }

    private static void setContext(
            Connection connection, String tenantId, boolean platformAdmin) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true), "
                        + "set_config('app.platform_admin', ?, true)")) {
            statement.setString(1, tenantId);
            statement.setString(2, Boolean.toString(platformAdmin));
            statement.execute();
        }
    }

    private static long count(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static ApiException assertError(String expectedCode, Executable executable) {
        ApiException exception = assertThrows(ApiException.class, executable);
        assertEquals(expectedCode, exception.getCode());
        return exception;
    }

    private static SkillDraftContent validContent(
            String versionKey, String skillName, String body, Map<String, byte[]> extraFiles) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", bytes(skillMd(skillName, body)));
        files.putAll(extraFiles);
        return rawContent(versionKey, files);
    }

    private static SkillDraftContent rawContent(String versionKey, Map<String, byte[]> files) {
        Map<String, String> encoded = new LinkedHashMap<>();
        files.forEach((path, content) -> encoded.put(
                path, Base64.getEncoder().encodeToString(content)));
        return new SkillDraftContent(versionKey, encoded, true, true,
                SkillActivationMode.ON_DEMAND, null, List.of());
    }

    private static Map<String, byte[]> decodeFiles(Map<String, String> files) {
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        files.forEach((path, content) -> decoded.put(path, decode(content)));
        return decoded;
    }

    private static String body(SkillDraftView view) {
        String root = new String(decode(view.files().get("SKILL.md")), StandardCharsets.UTF_8);
        return root.substring(root.indexOf("\n---\n") + 5);
    }

    private static String skillMd(String skillName, String body) {
        return "---\nname: " + skillName + "\ndescription: Integration skill\n---\n" + body;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + SnowflakeIds.nextId();
    }

    private static String uniqueKey(String prefix) {
        return prefix + "-" + SnowflakeIds.nextId();
    }

    private static TenantBinding tenant(long tenantId) {
        TenantContext.setTenantId(Long.toString(tenantId));
        return new TenantBinding();
    }

    private record TenantSkill(
            long tenantId,
            CapabilityCatalogScope scope,
            CapabilityDefinitionView definition,
            String skillName) {
    }

    private record SaveOutcome(SkillDraftView saved, ApiException failure) {
    }

    private static final class TenantBinding implements AutoCloseable {
        @Override
        public void close() {
            TenantContext.clear();
        }
    }
}
