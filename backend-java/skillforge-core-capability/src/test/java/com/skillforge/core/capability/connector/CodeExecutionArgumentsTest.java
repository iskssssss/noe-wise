package com.skillforge.core.capability.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.execution.ExecutionLimits;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeExecutionArgumentsTest {
    @Test
    void fixedBusinessResultHasNoSelectorAndCannotBeCollectedAsArtifact() {
        var args = new java.util.HashMap<String,Object>(Map.of("mode","inline","language","python","code","assert True"));
        assertEquals(List.of(), CodeExecutionArguments.parse(args).outputFiles());
        args.put("public_result_file", "result.json");
        var legacy = assertThrows(CodeExecutionArgumentException.class, () -> CodeExecutionArguments.parse(args));
        assertEquals(CodeExecutionArgumentException.Code.UNKNOWN_FIELD, legacy.code());
        args.remove("public_result_file");
        for (String reserved : List.of("result.json", "result.json/", "result.json/nested.txt")) {
            args.put("output_files", List.of(reserved));
            var failure = assertThrows(CodeExecutionArgumentException.class, () -> CodeExecutionArguments.parse(args));
            assertEquals(CodeExecutionArgumentException.Code.UNSAFE_PATH, failure.code());
            assertEquals("/output_files/0", failure.field());
        }
        args.put("output_files", List.of("reports/result.json"));
        assertEquals(List.of("reports/result.json"), CodeExecutionArguments.parse(args).outputFiles());
    }

    @Test
    void workspaceSelectionDefaultsToFalseAndAcceptsOnlyBooleanValues() {
        var arguments = new java.util.HashMap<String, Object>(Map.of(
                "mode", "inline", "language", "python", "code", "print(1)"));
        assertFalse(CodeExecutionArguments.parse(arguments).useWorkspace());
        arguments.put("use_workspace", true);
        assertTrue(CodeExecutionArguments.parse(arguments).useWorkspace());
        arguments.put("use_workspace", false);
        assertFalse(CodeExecutionArguments.parse(arguments).useWorkspace());
        for (Object invalid : new Object[] { null, "true", 1 }) {
            arguments.put("use_workspace", invalid);
            var failure = assertThrows(CodeExecutionArgumentException.class,
                    () -> CodeExecutionArguments.parse(arguments));
            assertEquals(CodeExecutionArgumentException.Code.INVALID_TYPE, failure.code());
            assertEquals("/use_workspace", failure.field());
        }
    }

    @Test
    void workspaceOptionDoesNotAcceptShellLanguageOrCallerOwnedIdentity() {
        assertThrows(CodeExecutionArgumentException.class, () -> CodeExecutionArguments.parse(Map.of(
                "mode", "inline", "language", "shell", "code", "ls", "use_workspace", true)));
        for (String identity : List.of("workspace_id", "tenant_id", "session_id")) {
            var arguments = new java.util.HashMap<String, Object>(Map.of(
                    "mode", "inline", "language", "node", "code", "console.log(1)", "use_workspace", true));
            arguments.put(identity, "forged-identity");
            var failure = assertThrows(CodeExecutionArgumentException.class,
                    () -> CodeExecutionArguments.parse(arguments));
            assertEquals(CodeExecutionArgumentException.Code.UNKNOWN_FIELD, failure.code());
            assertFalse(failure.diagnostic().toString().contains("forged-identity"));
        }
    }

    @Test
    void parsesBoundedCanonicalAttachmentIdsAsImmutableList() {
        String first = "12345678-1234-1234-1234-123456789abc";
        String second = "87654321-4321-4321-4321-cba987654321";
        CodeExecutionArguments parsed = CodeExecutionArguments.parse(Map.of(
                "mode", "inline", "language", "python", "code", "print(1)",
                "attachment_ids", List.of(first, second)));

        assertEquals(List.of(first, second), parsed.attachmentIds());
        assertThrows(UnsupportedOperationException.class, () -> parsed.attachmentIds().add(first));
        assertEquals(List.of(), CodeExecutionArguments.parse(Map.of(
                "mode", "inline", "language", "python", "code", "print(1)"))
                .attachmentIds());
    }

    @Test
    void rejectsInvalidDuplicateOrExcessiveAttachmentIds() {
        var args = new java.util.HashMap<String, Object>(Map.of(
                "mode", "inline", "language", "python", "code", "print(1)"));
        for (Object invalid : List.of(
                List.of("not-a-uuid"),
                List.of("12345678-1234-1234-1234-123456789ABC"),
                List.of(123),
                List.of("12345678-1234-1234-1234-123456789abc", "12345678-1234-1234-1234-123456789abc"),
                java.util.stream.IntStream.rangeClosed(1, ExecutionLimits.MAX_ATTACHMENT_INPUTS + 1)
                        .mapToObj(index -> "00000000-0000-0000-0000-%012d".formatted(index)).toList())) {
            args.put("attachment_ids", invalid);
            assertThrows(IllegalArgumentException.class, () -> CodeExecutionArguments.parse(args));
        }
        args.put("attachment_ids", "12345678-1234-1234-1234-123456789abc");
        assertThrows(IllegalArgumentException.class, () -> CodeExecutionArguments.parse(args));
    }

    @Test
    void separatesTransientInputsFromExplicitCandidateOutputs() {
        var args = new java.util.HashMap<String,Object>(Map.of("mode", "inline", "language", "python", "code", "pass"));
        assertEquals(List.of(), CodeExecutionArguments.parse(args).outputFiles());
        args.put("input_files", Map.of("document.json", "{}"));
        args.put("output_files", List.of("document.docx", "assets/"));
        var parsed = CodeExecutionArguments.parse(args);
        assertEquals(Map.of("document.json", "{}"), parsed.inputFiles());
        assertEquals(List.of("assets/", "document.docx"), parsed.outputFiles());
        for (String path : List.of("../secret", "/absolute", "./", "C:/secret", "a//b")) {
            args.put("output_files", List.of(path));
            assertThrows(IllegalArgumentException.class, () -> CodeExecutionArguments.parse(args));
        }
        args.put("output_files", List.of());
        args.put("input_files", Map.of("../main.py", "pass"));
        assertThrows(IllegalArgumentException.class, () -> CodeExecutionArguments.parse(args));
    }

    @Test
    void acceptsIntermediateCandidatesAndReusesTheirRealArtifactReferences() {
        String id = "12345678-1234-1234-1234-123456789abc";
        var outputs = List.of("parts/", "source-data.json");
        var first = CodeExecutionArguments.parse(Map.of(
                "mode", "inline", "language", "python", "code", "pass", "output_files", outputs));
        assertEquals(outputs, first.outputFiles());
        var second = CodeExecutionArguments.parse(Map.of(
                "mode", "inline", "language", "python", "code", "pass",
                "artifact_inputs", List.of(Map.of("artifact_id", id, "path", "parts/section.html"))));
        assertEquals("artifacts/" + id + "/parts/section.html", second.artifactInputs().getFirst().inputPath());

        var rejected = assertThrows(CodeExecutionArgumentException.class, () -> CodeExecutionArguments.parse(Map.of(
                "mode", "inline", "language", "python", "code", "pass",
                "artifact_inputs", List.of(Map.of("artifact_id", "not-a-uuid", "path", "parts/section.html")))));
        assertEquals(CodeExecutionArgumentException.Code.INVALID_FORMAT, rejected.code());
        assertTrue(rejected.diagnostic().get("hint").toString().contains("ready 候选"));
        assertTrue(rejected.diagnostic().get("hint").toString().contains("无需先正式发布"));
    }

    @Test void acceptsOnlyExplicitSafeArtifactReferences() {
        String id="12345678-1234-1234-1234-123456789abc";
        var args=new java.util.HashMap<String,Object>(Map.of("mode","inline","language","python","code","print('ok')"));
        args.put("artifact_inputs",List.of(Map.of("artifact_id",id,"path","data/records.json")));
        assertEquals("artifacts/"+id+"/data/records.json",CodeExecutionArguments.parse(args).artifactInputs().getFirst().inputPath());
        for(String path:List.of("../secret","/etc/passwd","data/../secret","data\\secret","file://secret","data//x")) {
            args.put("artifact_inputs",List.of(Map.of("artifact_id",id,"path",path)));
            assertThrows(IllegalArgumentException.class,()->CodeExecutionArguments.parse(args));
        }
        args.put("artifact_inputs",List.of(Map.of("artifact_id",id,"path","a"),Map.of("artifact_id",id,"path","a")));
        assertThrows(IllegalArgumentException.class,()->CodeExecutionArguments.parse(args));
    }

    @Test
    void parsesInlineCodeWithoutRequiringSkill() {
        CodeExecutionArguments parsed = CodeExecutionArguments.parse(Map.of(
                "mode", "inline",
                "language", "python",
                "code", "print('ok')\n",
                "dependencies", List.of(Map.of("name", "openpyxl", "version", "3.1.5"))));

        assertEquals("inline", parsed.mode());
        assertEquals("python", parsed.language());
        assertEquals("print('ok')\n", parsed.code());
        assertNull(parsed.skillHandle());
        assertNull(parsed.scriptPath());
        assertEquals(List.of(new CodeExecutionArguments.Dependency("openpyxl", "3.1.5")),
                parsed.dependencies());
    }

    @Test
    void parsesFrozenSkillScriptReference() {
        CodeExecutionArguments parsed = CodeExecutionArguments.parse(Map.of(
                "mode", "skill",
                "language", "node",
                "skill_handle", "skill:opaque-version-handle",
                "script_path", "scripts/reports/build.mjs",
                "dependencies", List.of(Map.of("name", "@types/node", "version", "24.3.1"))));

        assertEquals("skill", parsed.mode());
        assertEquals("node", parsed.language());
        assertNull(parsed.code());
        assertEquals("skill:opaque-version-handle", parsed.skillHandle());
        assertEquals("scripts/reports/build.mjs", parsed.scriptPath());
    }

    @Test
    void rejectsMixedModesAndUnknownFields() {
        assertThrows(IllegalArgumentException.class, () -> CodeExecutionArguments.parse(Map.of(
                "mode", "inline", "language", "python", "code", "print(1)",
                "skill_handle", "skill:forged")));
        assertThrows(IllegalArgumentException.class, () -> CodeExecutionArguments.parse(Map.of(
                "mode", "skill", "language", "python", "skill_handle", "skill:1",
                "script_path", "scripts/run.py", "code", "print(1)")));
        assertThrows(IllegalArgumentException.class, () -> CodeExecutionArguments.parse(Map.of(
                "mode", "inline", "language", "python", "code", "print(1)",
                "output_directory", "/tmp/forged")));
    }

    @Test
    void skillModeConflictIdentifiesCodeAndRequiresDeletingTheFieldEvenWhenEmptyOrNull() {
        var args = new java.util.HashMap<String,Object>(Map.of(
                "mode", "skill", "language", "node", "skill_handle", "skill:active",
                "script_path", "scripts/generate.mjs"));
        for (Object code : new Object[] { "sensitive-code-secret", "", null }) {
            args.put("code", code);
            var failure = assertThrows(CodeExecutionArgumentException.class, () -> CodeExecutionArguments.parse(args));
            assertEquals(CodeExecutionArgumentException.Code.FIELD_FORBIDDEN_FOR_MODE, failure.code());
            assertEquals("/code", failure.field());
            assertEquals("skill 模式禁止 code。", failure.getMessage());
            assertTrue(failure.hint().contains("删除整个 code 字段"));
            assertFalse(failure.diagnostic().toString().contains("sensitive-code-secret"));
        }
        args.remove("code");
        assertNull(CodeExecutionArguments.parse(args).code());
    }

    @Test
    void diagnosticsIdentifyNestedDependencyFieldsWithoutEchoingUntrustedNamesOrVersions() {
        var args = new java.util.HashMap<String,Object>(Map.of("mode", "inline", "language", "node", "code", "pass"));
        args.put("dependencies", List.of(Map.of("name", "package", "version", "sensitive-version")));
        var version = assertThrows(CodeExecutionArgumentException.class, () -> CodeExecutionArguments.parse(args));
        assertEquals(CodeExecutionArgumentException.Code.INVALID_FORMAT, version.code());
        assertEquals("/dependencies/0/version", version.field());
        assertFalse(version.diagnostic().toString().contains("sensitive-version"));

        args.put("dependencies", List.of(Map.of("name", "package", "version", "1.2.3", "sensitive-key", "sensitive-value")));
        var unknown = assertThrows(CodeExecutionArgumentException.class, () -> CodeExecutionArguments.parse(args));
        assertEquals(CodeExecutionArgumentException.Code.UNKNOWN_FIELD, unknown.code());
        assertEquals("/dependencies/0", unknown.field());
        assertFalse(unknown.diagnostic().toString().contains("sensitive-key"));
        assertFalse(unknown.diagnostic().toString().contains("sensitive-value"));
    }

    @Test
    void malformedArgumentFamiliesExposeTypedSafeDiagnostics() {
        var base = Map.<String,Object>of("mode", "inline", "language", "python", "code", "sensitive-code");
        var cases = List.of(
                Map.of("mode", "sensitive-mode"), Map.of("language", "sensitive-language"),
                Map.of("input_files", Map.of("../sensitive-path", "sensitive-content")),
                Map.of("output_files", List.of("../sensitive-path")), Map.of("output_files", 42),
                Map.of("attachment_ids", List.of("sensitive-uuid")),
                Map.of("artifact_inputs", List.of(Map.of("artifact_id", "sensitive-id", "path", "x"))),
                Map.of("dependencies", "sensitive-dependencies"));
        for (var changed : cases) {
            var args = new java.util.HashMap<String,Object>(base);
            args.putAll(changed);
            var rejected = assertThrows(CodeExecutionArgumentException.class, () -> CodeExecutionArguments.parse(args));
            assertEquals("available", rejected.diagnostic().get("status"));
            assertTrue(rejected.field().startsWith("/"));
            assertFalse(rejected.diagnostic().toString().contains("sensitive-"));
        }
        assertEquals(CodeExecutionArgumentException.Code.ARGUMENTS_REQUIRED,
                assertThrows(CodeExecutionArgumentException.class, () -> CodeExecutionArguments.parse(null)).code());
        var missing = assertThrows(CodeExecutionArgumentException.class, () -> CodeExecutionArguments.parse(Map.of()));
        assertEquals(CodeExecutionArgumentException.Code.REQUIRED_FIELD, missing.code());
        assertEquals("/mode", missing.field());
    }

    @Test
    void rejectsMissingAndUnsafeSkillReferences() {
        assertThrows(IllegalArgumentException.class, () -> CodeExecutionArguments.parse(Map.of(
                "mode", "skill", "language", "python", "script_path", "scripts/run.py")));
        assertThrows(IllegalArgumentException.class, () -> CodeExecutionArguments.parse(Map.of(
                "mode", "skill", "language", "python", "skill_handle", "skill:1",
                "script_path", "scripts/../secret.py")));
        assertThrows(IllegalArgumentException.class, () -> CodeExecutionArguments.parse(Map.of(
                "mode", "skill", "language", "python", "skill_handle", "skill:1",
                "script_path", "scripts/run.js")));
        assertThrows(IllegalArgumentException.class, () -> CodeExecutionArguments.parse(Map.of(
                "mode", "skill", "language", "node", "skill_handle", "skill:1",
                "script_path", "scripts\\run.js")));
    }

    @Test
    void rejectsCommandsRangesAndUnknownDependencyFields() {
        assertThrows(IllegalArgumentException.class, () -> inlineWithDependency(
                Map.of("name", "requests;rm", "version", "2.32.5")));
        assertThrows(IllegalArgumentException.class, () -> inlineWithDependency(
                Map.of("name", "requests", "version", ">=2.32.5")));
        assertThrows(IllegalArgumentException.class, () -> inlineWithDependency(
                Map.of("name", "@scope/package", "version", "1.2.3")));
        assertThrows(IllegalArgumentException.class, () -> inlineWithDependency(
                Map.of("name", "requests", "version", "2.32.5", "repository", "https://example.test")));
        assertThrows(IllegalArgumentException.class, () -> CodeExecutionArguments.parse(Map.of(
                "mode", "inline", "language", "python", "code", "print(1)",
                "dependencies", List.of(
                        Map.of("name", "requests", "version", "2.32.5"),
                        Map.of("name", "requests", "version", "2.32.5")))));
    }

    @Test
    void nodeRequiresAnExactThreePartSemver() {
        assertThrows(IllegalArgumentException.class, () -> nodeWithDependency("package", "1"));
        assertThrows(IllegalArgumentException.class, () -> nodeWithDependency("package", "1.2"));
        assertThrows(IllegalArgumentException.class, () -> nodeWithDependency("package", "^1.2.3"));

        CodeExecutionArguments parsed = nodeWithDependency("@scope/package", "1.2.3-rc.1+build.7");
        assertEquals("1.2.3-rc.1+build.7", parsed.dependencies().getFirst().version());
        assertEquals("1.0rc1", inlineWithDependency(
                Map.of("name", "Package_Name", "version", "1.0rc1"))
                .dependencies().getFirst().version());
    }

    private static CodeExecutionArguments inlineWithDependency(Map<String, Object> dependency) {
        return CodeExecutionArguments.parse(Map.of(
                "mode", "inline", "language", "python", "code", "print(1)",
                "dependencies", List.of(dependency)));
    }

    private static CodeExecutionArguments nodeWithDependency(String name, String version) {
        return CodeExecutionArguments.parse(Map.of(
                "mode", "inline", "language", "node", "code", "console.log(1)",
                "dependencies", List.of(Map.of("name", name, "version", version))));
    }
}
