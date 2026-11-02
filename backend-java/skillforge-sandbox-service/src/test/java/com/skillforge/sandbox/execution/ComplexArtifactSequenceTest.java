package com.skillforge.sandbox.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 多目录72行业务材料的完整输入、输出及损坏清理序列；不调用外部服务。 */
class ComplexArtifactSequenceTest {
    @TempDir Path root;
    private final RequestPolicy policy = new RequestPolicy(new ObjectMapper());

    @Test void verifiesTwentyFourNestedInputsThenPublishesOnlyDeclaredFinalFiles() throws Exception {
        var payloads = payloads();
        var facts = facts(payloads);
        var workspace = new TaskWorkspace(TestSandboxProperties.create(root));
        try (var archive = archive(payloads)) { workspace.prepare(policy.validate(request("complex-input"), facts), archive); }
        for (var entry : payloads.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(root.resolve("workspaces/complex-input/input/" + entry.getKey())));
        Path output = root.resolve("workspaces/complex-input/output");
        Files.createDirectories(output.resolve("assets"));
        Files.writeString(output.resolve("report.md"), rows("md"));
        Files.writeString(output.resolve("index.html"), "<!doctype html><link href='assets/data.csv'><p>72条</p>");
        Files.writeString(output.resolve("assets/data.csv"), rows("csv"));
        Files.writeString(output.resolve("private-check.json"), "{\"temporary\":true}");
        var collector = new ArtifactCollector(workspace);
        var collected = collector.collect("complex-input", 2048, List.of("report.md", "index.html", "assets/"));
        assertEquals(List.of("assets/data.csv", "index.html", "report.md"), collected.stream().map(ArtifactFile::path).toList());
        var bytes = new ByteArrayOutputStream(); collector.streamZip("complex-input", collected, bytes);
        int count = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            for (var file = zip.getNextEntry(); file != null; file = zip.getNextEntry()) {
                byte[] observed = zip.readAllBytes();
                String entryName = file.getName();
                var expected = collected.stream().filter(value -> value.path().equals(entryName)).findFirst().orElseThrow();
                assertEquals(expected.size(), observed.length); assertEquals(expected.sha256(), sha(observed)); count++;
            }
        }
        assertEquals(3, count); workspace.delete("complex-input"); assertFalse(workspace.exists("complex-input"));
    }

    @Test void corruptMiddleFileAndLateUnlistedFileLeaveNoReusablePartialWorkspace() throws Exception {
        var original = payloads(); var expected = facts(original);
        var workspace = new TaskWorkspace(TestSandboxProperties.create(root));
        for (String fault : List.of("corrupt-middle", "unlisted-late", "missing-last")) {
            var broken = new LinkedHashMap<>(original);
            if (fault.equals("corrupt-middle")) broken.put("data/dept-3/month-2/records.json", new byte[]{1, 2, 3});
            if (fault.equals("unlisted-late")) broken.put("data/unlisted.csv", rows("csv").getBytes(StandardCharsets.UTF_8));
            if (fault.equals("missing-last")) broken.remove("data/dept-5/month-3/records.json");
            try (var zip = archive(broken)) { assertThrows(IllegalStateException.class, () -> workspace.prepare(policy.validate(request(fault), expected), zip)); }
            assertFalse(workspace.exists(fault), fault);
        }
    }

    @Test void exactRequestDigestBindsAllFilesAndAnUpdatedRevision() throws Exception {
        var original = payloads(); var files = facts(original);
        var frozenRequest = request("revision-input");
        var first = policy.validate(frozenRequest, files);
        // 复用完整请求，连同绝对 deadline 一起冻结；仅改变文件内容的负控也使用该请求。
        assertEquals(first.digest(), policy.validate(frozenRequest, files).digest());
        var updated = new LinkedHashMap<>(original);
        updated.put("data/dept-0/month-0/records.csv", (rows("csv") + "revision-2,待审批\n").getBytes(StandardCharsets.UTF_8));
        assertNotEquals(first.digest(), policy.validate(frozenRequest, facts(updated)).digest());
        var workspace = new TaskWorkspace(TestSandboxProperties.create(root));
        try (var zip = archive(updated)) { workspace.prepare(policy.validate(request("revision-2"), facts(updated)), zip); }
        assertTrue(Files.readString(root.resolve("workspaces/revision-2/input/data/dept-0/month-0/records.csv")).endsWith("revision-2,待审批\n"));
        workspace.delete("revision-2"); assertFalse(workspace.exists("revision-2"));
    }

    private ExecuteRequest request(String task) {
        return new ExecuteRequest(task, Language.PYTHON, "assert sum(range(1,73)) == 2628", List.of(), List.of(), null, false,
                Instant.now().plusSeconds(120), TestSandboxProperties.policy(), List.of(), ExecutionProfile.STANDARD, null, null);
    }
    private LinkedHashMap<String, byte[]> payloads() {
        var result = new LinkedHashMap<String, byte[]>();
        for (int number = 0; number < 24; number++) result.put("data/dept-" + number % 6 + "/month-" + number / 6 + "/records." + (number % 2 == 0 ? "csv" : "json"), rows(number % 2 == 0 ? "csv" : "json").getBytes(StandardCharsets.UTF_8));
        return result;
    }
    private String rows(String format) {
        var text = new StringBuilder(format.equals("json") ? "[" : "record_id,amount_cents,department\n");
        for (int row = 1; row <= 72; row++) text.append(format.equals("json") ? (row > 1 ? "," : "") + "{\"id\":\"sale-" + row + "\",\"cents\":" + row * 125 + ",\"department\":\"部门" + row % 6 + "\"}" : "sale-" + row + "," + row * 125 + ",部门" + row % 6 + "\n");
        return text.append(format.equals("json") ? "]" : "").toString();
    }
    private List<ArtifactFile> facts(Map<String, byte[]> files) throws Exception {
        var result = new ArrayList<ArtifactFile>();
        for (var file : files.entrySet()) result.add(new ArtifactFile(file.getKey(), file.getValue().length, sha(file.getValue())));
        return result;
    }
    private String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private ZipInputStream archive(Map<String, byte[]> files) throws Exception {
        var content = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(content)) {
            for (var file : files.entrySet()) { zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue()); zip.closeEntry(); }
        }
        return new ZipInputStream(new ByteArrayInputStream(content.toByteArray()));
    }
}
