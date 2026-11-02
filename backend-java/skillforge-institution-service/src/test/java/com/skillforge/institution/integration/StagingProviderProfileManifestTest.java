package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StagingProviderProfileManifestTest {

    @Test
    void generatedRunIdLeavesRoomForDatabaseResourceSuffixes() {
        String first = StagingRuntimeE2E.runId("provider-alias-" + "x".repeat(500));
        String second = StagingRuntimeE2E.runId("provider-alias-" + "x".repeat(500));

        assertTrue(first.length() + "-agent".length() <= 64);
        assertNotEquals(first, second);
    }

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesExternalManifestAndResolvesReferencedEnvironmentValues() throws IOException {
        Path repository = createRepositoryRoot();
        Path manifestPath = writeManifest("valid.json", """
                {
                  "profiles": [
                    {"alias": "openai", "tenant_slug": "tenant-a", "username_env": "USER_A", "password_env": "PASS_A"},
                    {"alias": "deepseek", "tenant_slug": "tenant-b", "username_env": "USER_B", "password_env": "PASS_B"}
                  ]
                }
                """);

        List<StagingRuntimeE2E.ResolvedProfile> profiles = StagingRuntimeE2E.ProfileManifest
                .load(manifestPath, repository)
                .resolve(Map.of(
                        "USER_A", "用户甲", "PASS_A", "口令甲",
                        "USER_B", "用户乙", "PASS_B", "口令乙"));

        assertEquals(2, profiles.size());
        assertEquals("openai", profiles.get(0).definition().alias());
        assertEquals("用户甲", profiles.get(0).username());
        assertEquals(3, profiles.get(0).password().length());
        assertEquals("ResolvedProfile[alias=openai]", profiles.get(0).toString());
    }

    @Test
    void rejectsMissingOrRepositoryLocalManifestBeforeParsing() throws IOException {
        Path repository = createRepositoryRoot();
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> StagingRuntimeE2E.ProfileManifest.load(
                        temporaryDirectory.resolve("missing.json"), repository));
        assertTrue(missing.getMessage().contains("STAGING_PROVIDER_PROFILES_FILE"));

        Path localManifest = repository.resolve("profiles.json");
        Files.writeString(localManifest, validManifest(), StandardCharsets.UTF_8);
        IllegalStateException local = assertThrows(IllegalStateException.class,
                () -> StagingRuntimeE2E.ProfileManifest.load(localManifest, repository));
        assertTrue(local.getMessage().contains("STAGING_PROVIDER_PROFILES_FILE"));
    }

    @Test
    void rejectsTooFewProfilesDuplicateAliasesIllegalEnvironmentNamesAndSecrets() throws IOException {
        Path repository = createRepositoryRoot();
        IllegalStateException tooFew = assertInvalid(repository, "too-few.json", """
                {"profiles": [{"alias": "only", "tenant_slug": "tenant", "username_env": "USER", "password_env": "PASS"}]}
                """, Map.of());
        assertTrue(tooFew.getMessage().contains("profiles"));

        IllegalStateException duplicateAlias = assertInvalid(repository, "duplicate.json", """
                {"profiles": [
                  {"alias": "same", "tenant_slug": "tenant-a", "username_env": "USER_A", "password_env": "PASS_A"},
                  {"alias": "same", "tenant_slug": "tenant-b", "username_env": "USER_B", "password_env": "PASS_B"}
                ]}
                """, Map.of());
        assertTrue(duplicateAlias.getMessage().contains("same"));
        assertTrue(duplicateAlias.getMessage().contains("alias"));

        IllegalStateException illegalEnvironment = assertInvalid(repository, "illegal-env.json", """
                {"profiles": [
                  {"alias": "one", "tenant_slug": "tenant-a", "username_env": "USER-A", "password_env": "PASS_A"},
                  {"alias": "two", "tenant_slug": "tenant-b", "username_env": "USER_B", "password_env": "PASS_B"}
                ]}
                """, Map.of());
        assertTrue(illegalEnvironment.getMessage().contains("username_env"));

        IllegalStateException secretField = assertInvalid(repository, "secret-field.json", """
                {"profiles": [
                  {"alias": "one", "tenant_slug": "tenant-a", "username_env": "USER_A", "password_env": "PASS_A", "password": "秘密"},
                  {"alias": "two", "tenant_slug": "tenant-b", "username_env": "USER_B", "password_env": "PASS_B"}
                ]}
                """, Map.of());
        assertTrue(secretField.getMessage().contains("password"));
        assertFalse(secretField.getMessage().contains("秘密"));
    }

    @Test
    void rejectsMissingReferencedCredentialEnvironment() throws IOException {
        Path repository = createRepositoryRoot();
        Path manifestPath = writeManifest("missing-credential.json", validManifest());
        StagingRuntimeE2E.ProfileManifest manifest = StagingRuntimeE2E.ProfileManifest
                .load(manifestPath, repository);

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> manifest.resolve(Map.of("USER_A", "用户甲", "PASS_A", "口令甲", "USER_B", "用户乙")));
        assertTrue(missing.getMessage().contains("deepseek"));
        assertTrue(missing.getMessage().contains("password_env"));
        assertFalse(missing.getMessage().contains("口令甲"));
    }

    private Path createRepositoryRoot() throws IOException {
        Path repository = temporaryDirectory.resolve("repository");
        Files.createDirectories(repository.resolve(".git"));
        return repository;
    }

    private Path writeManifest(String name, String content) throws IOException {
        Path manifestPath = temporaryDirectory.resolve(name);
        Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
        return manifestPath;
    }

    private IllegalStateException assertInvalid(
            Path repository, String name, String content, Map<String, String> environment)
            throws IOException {
        Path manifestPath = writeManifest(name, content);
        return assertThrows(IllegalStateException.class,
                () -> StagingRuntimeE2E.ProfileManifest.load(manifestPath, repository)
                        .resolve(environment));
    }

    private String validManifest() {
        return """
                {
                  "profiles": [
                    {"alias": "openai", "tenant_slug": "tenant-a", "username_env": "USER_A", "password_env": "PASS_A"},
                    {"alias": "deepseek", "tenant_slug": "tenant-b", "username_env": "USER_B", "password_env": "PASS_B"}
                  ]
                }
                """;
    }
}
