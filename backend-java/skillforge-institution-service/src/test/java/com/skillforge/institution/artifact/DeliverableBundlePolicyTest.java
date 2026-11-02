package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class DeliverableBundlePolicyTest {
    @Test
    void acceptsExactManifestAndReturnsEntry() throws Exception {
        byte[] html = "<h1>完成</h1>".getBytes(StandardCharsets.UTF_8);
        byte[] css = "h1{color:green}".getBytes(StandardCharsets.UTF_8);
        String manifest = manifest(List.of(file("index.html", html), file("assets/site.css", css)), "index.html");

        var result = DeliverableBundlePolicy.validate(zip(Map.of(
                "manifest.json", manifest.getBytes(StandardCharsets.UTF_8),
                "index.html", html,
                "assets/site.css", css)));

        assertEquals("index.html", result.entry());
        assertTrue(result.includesOriginals());
        assertEquals(2, result.files().size());
    }

    @Test
    void rejectsPathTraversalAndDuplicatePaths() throws Exception {
        assertCode("BUNDLE_PATH_TRAVERSAL", zip(Map.of("../secret.txt", bytes("secret"))));

        byte[] archive = zip(List.of(entry("sameA.txt", bytes("a")), entry("sameB.txt", bytes("b"))));
        replaceAscii(archive, "sameB.txt", "sameA.txt");
        assertCode("BUNDLE_DUPLICATE_PATH", archive);
    }

    @Test
    void rejectsNfkcPathConflicts() throws Exception {
        assertCode("BUNDLE_NFKC_PATH_CONFLICT", zip(Map.of(
                "caf\u00e9.txt", bytes("a"),
                "cafe\u0301.txt", bytes("b"))));
    }

    @Test
    void rejectsMoreThan256ContentFiles() throws Exception {
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index <= DeliverableBundlePolicy.MAX_FILES; index++) {
            entries.add(entry("file-" + index + ".txt", bytes("x")));
        }
        assertCode("BUNDLE_FILE_LIMIT_EXCEEDED", zip(entries));
    }

    @Test
    void rejectsUncompressedContentOver100MiB() throws Exception {
        byte[] archive;
        try (ByteArrayOutputStream target = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(target, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("large.bin"));
            byte[] chunk = new byte[1024 * 1024];
            for (int index = 0; index <= 100; index++) {
                zip.write(chunk);
            }
            zip.closeEntry();
            zip.finish();
            archive = target.toByteArray();
        }
        assertCode("BUNDLE_UNCOMPRESSED_LIMIT_EXCEEDED", archive);
    }

    @Test
    void rejectsCompressionRatioOver100() throws Exception {
        assertCode("BUNDLE_COMPRESSION_RATIO_EXCEEDED", zip(Map.of("repetitive.bin", new byte[1024 * 1024])));
    }

    @Test
    void rejectsMissingAndMismatchedManifest() throws Exception {
        byte[] content = bytes("payload");
        assertCode("BUNDLE_MANIFEST_REQUIRED", zip(Map.of("data.txt", content)));

        String wrong = "{\"files\":[{\"path\":\"data.txt\",\"sha256\":\""
                + "0".repeat(64) + "\",\"bytes\":7}],\"entry\":null,\"includes_originals\":true}";
        assertCode("BUNDLE_MANIFEST_MISMATCH", zip(Map.of(
                "manifest.json", bytes(wrong),
                "data.txt", content)));
    }

    @Test
    void rejectsManifestEntryThatIsNotADeclaredFile() throws Exception {
        byte[] content = bytes("payload");
        String manifest = manifest(List.of(file("data.txt", content)), "missing.txt");

        assertCode("BUNDLE_ENTRY_NOT_FOUND", zip(Map.of(
                "manifest.json", bytes(manifest),
                "data.txt", content)));
    }

    private static String manifest(List<String> files, String entry) {
        return "{\"files\":[" + String.join(",", files) + "],\"entry\":"
                + (entry == null ? "null" : "\"" + entry + "\"") + ",\"includes_originals\":true}";
    }

    private static String file(String path, byte[] content) throws Exception {
        return "{\"path\":\"" + path + "\",\"sha256\":\""
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
                + "\",\"bytes\":" + content.length + "}";
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        return zip(entries.entrySet().stream().map(item -> entry(item.getKey(), item.getValue())).toList());
    }

    private static byte[] zip(List<Entry> entries) throws Exception {
        try (ByteArrayOutputStream target = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(target, StandardCharsets.UTF_8)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.path()));
                zip.write(entry.content());
                zip.closeEntry();
            }
            zip.finish();
            return target.toByteArray();
        }
    }

    private static Entry entry(String path, byte[] content) {
        return new Entry(path, content);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void replaceAscii(byte[] archive, String from, String to) {
        byte[] source = bytes(from);
        byte[] replacement = bytes(to);
        assertEquals(source.length, replacement.length);
        int replacements = 0;
        for (int index = 0; index <= archive.length - source.length; index++) {
            boolean matches = true;
            for (int offset = 0; offset < source.length; offset++) {
                if (archive[index + offset] != source[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                System.arraycopy(replacement, 0, archive, index, replacement.length);
                replacements++;
            }
        }
        assertEquals(2, replacements);
    }

    private static void assertCode(String expected, byte[] archive) {
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> DeliverableBundlePolicy.validate(archive));
        assertEquals(expected, rejected.getMessage());
    }

    private record Entry(String path, byte[] content) {
    }
}
