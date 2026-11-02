package com.skillforge.institution.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP 成果包的纯校验策略。校验过程只读取内存字节，不解压到文件系统；拒绝时异常消息是稳定错误码。
 */
public final class DeliverableBundlePolicy {
    public static final int MAX_FILES = 256;
    public static final long MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024;
    public static final int MAX_COMPRESSION_RATIO = 100;

    private static final String MANIFEST_PATH = "manifest.json";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private DeliverableBundlePolicy() {
    }

    /**
     * 校验 ZIP 结构与根 manifest，并返回已核验的声明。所有拒绝均抛出消息为 {@link ErrorCode} 名称的
     * {@link IllegalArgumentException}。
     */
    public static Manifest validate(byte[] archive) {
        if (archive == null || archive.length == 0) {
            throw rejected(ErrorCode.BUNDLE_ZIP_INVALID);
        }
        try {
            return validateArchive(archive);
        } catch (Rejected rejected) {
            throw rejected;
        } catch (IOException | RuntimeException invalid) {
            throw rejected(ErrorCode.BUNDLE_ZIP_INVALID, invalid);
        }
    }

    private static Manifest validateArchive(byte[] archive) throws IOException {
        Map<String, ActualFile> actualFiles = new LinkedHashMap<>();
        Map<String, String> normalizedPaths = new HashMap<>();
        Map<String, Boolean> seenPaths = new HashMap<>();
        byte[] manifestBytes = null;
        long totalBytes = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String path = entry.getName();
                registerPath(path, entry.isDirectory(), seenPaths, normalizedPaths);
                MessageDigest digest = sha256();
                ByteArrayOutputStream manifest = MANIFEST_PATH.equals(path) && !entry.isDirectory()
                        ? new ByteArrayOutputStream()
                        : null;
                long entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    entryBytes += read;
                    totalBytes += read;
                    if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                        throw rejected(ErrorCode.BUNDLE_UNCOMPRESSED_LIMIT_EXCEEDED);
                    }
                    digest.update(buffer, 0, read);
                    if (manifest != null) {
                        manifest.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
                enforceCompressionRatio(entryBytes, entry.getCompressedSize());

                if (entry.isDirectory()) {
                    if (entryBytes != 0) {
                        throw rejected(ErrorCode.BUNDLE_ZIP_INVALID);
                    }
                    continue;
                }
                if (MANIFEST_PATH.equals(path)) {
                    manifestBytes = manifest.toByteArray();
                    continue;
                }
                if (actualFiles.size() >= MAX_FILES) {
                    throw rejected(ErrorCode.BUNDLE_FILE_LIMIT_EXCEEDED);
                }
                actualFiles.put(path, new ActualFile(entryBytes, HexFormat.of().formatHex(digest.digest())));
            }
        }

        if (manifestBytes == null) {
            throw rejected(ErrorCode.BUNDLE_MANIFEST_REQUIRED);
        }
        Manifest declared = parseManifest(manifestBytes);
        verifyManifest(declared, actualFiles);
        return declared;
    }

    private static Manifest parseManifest(byte[] bytes) {
        try {
            JsonNode root = JSON.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw rejected(ErrorCode.BUNDLE_MANIFEST_INVALID);
            }
            JsonNode filesNode = root.get("files");
            JsonNode entryNode = root.get("entry");
            JsonNode originalsNode = root.get("includes_originals");
            if (filesNode == null || !filesNode.isArray() || entryNode == null
                    || (!entryNode.isNull() && !entryNode.isTextual())
                    || originalsNode == null || !originalsNode.isBoolean() || !originalsNode.booleanValue()) {
                throw rejected(ErrorCode.BUNDLE_MANIFEST_INVALID);
            }

            List<BundleFile> files = new ArrayList<>();
            Map<String, Boolean> seenPaths = new HashMap<>();
            Map<String, String> normalizedPaths = new HashMap<>();
            for (JsonNode fileNode : filesNode) {
                if (!fileNode.isObject()) {
                    throw rejected(ErrorCode.BUNDLE_MANIFEST_INVALID);
                }
                JsonNode pathNode = fileNode.get("path");
                JsonNode shaNode = fileNode.get("sha256");
                JsonNode bytesNode = fileNode.get("bytes");
                if (pathNode == null || !pathNode.isTextual() || shaNode == null || !shaNode.isTextual()
                        || bytesNode == null || !bytesNode.isIntegralNumber() || !bytesNode.canConvertToLong()
                        || bytesNode.longValue() < 0 || !SHA256.matcher(shaNode.textValue()).matches()) {
                    throw rejected(ErrorCode.BUNDLE_MANIFEST_INVALID);
                }
                String path = pathNode.textValue();
                registerPath(path, false, seenPaths, normalizedPaths);
                files.add(new BundleFile(path, shaNode.textValue(), bytesNode.longValue()));
                if (files.size() > MAX_FILES) {
                    throw rejected(ErrorCode.BUNDLE_FILE_LIMIT_EXCEEDED);
                }
            }
            String entry = entryNode.isNull() ? null : entryNode.textValue();
            if (entry != null) {
                validatePath(entry, false);
            }
            return new Manifest(files, entry, true);
        } catch (Rejected rejected) {
            throw rejected;
        } catch (IOException | RuntimeException invalid) {
            throw rejected(ErrorCode.BUNDLE_MANIFEST_INVALID, invalid);
        }
    }

    private static void verifyManifest(Manifest manifest, Map<String, ActualFile> actualFiles) {
        if (manifest.files().size() != actualFiles.size()) {
            throw rejected(ErrorCode.BUNDLE_MANIFEST_MISMATCH);
        }
        for (BundleFile declared : manifest.files()) {
            ActualFile actual = actualFiles.get(declared.path());
            if (actual == null || actual.bytes() != declared.bytes() || !actual.sha256().equals(declared.sha256())) {
                throw rejected(ErrorCode.BUNDLE_MANIFEST_MISMATCH);
            }
        }
        if (manifest.entry() != null
                && manifest.files().stream().noneMatch(file -> file.path().equals(manifest.entry()))) {
            throw rejected(ErrorCode.BUNDLE_ENTRY_NOT_FOUND);
        }
    }

    private static void registerPath(String path, boolean directory, Map<String, Boolean> seenPaths,
            Map<String, String> normalizedPaths) {
        validatePath(path, directory);
        if (seenPaths.putIfAbsent(path, Boolean.TRUE) != null) {
            throw rejected(ErrorCode.BUNDLE_DUPLICATE_PATH);
        }
        String normalized = Normalizer.normalize(path, Normalizer.Form.NFKC);
        validatePath(normalized, directory);
        String existing = normalizedPaths.putIfAbsent(normalized, path);
        if (existing != null && !existing.equals(path)) {
            throw rejected(ErrorCode.BUNDLE_NFKC_PATH_CONFLICT);
        }
    }

    private static void validatePath(String path, boolean directory) {
        if (path == null || path.isEmpty() || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || path.startsWith("/") || isDriveAbsolute(path)
                || path.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw rejected(ErrorCode.BUNDLE_PATH_TRAVERSAL);
        }
        String checked = directory && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (checked.isEmpty() || (!directory && path.endsWith("/"))) {
            throw rejected(ErrorCode.BUNDLE_PATH_TRAVERSAL);
        }
        for (String part : checked.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw rejected(ErrorCode.BUNDLE_PATH_TRAVERSAL);
            }
        }
    }

    private static boolean isDriveAbsolute(String path) {
        return path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }

    private static void enforceCompressionRatio(long uncompressed, long compressed) {
        if (uncompressed == 0) {
            return;
        }
        if (compressed <= 0 || uncompressed > compressed * (long) MAX_COMPRESSION_RATIO) {
            throw rejected(ErrorCode.BUNDLE_COMPRESSION_RATIO_EXCEEDED);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("缺少 SHA-256 算法", impossible);
        }
    }

    private static Rejected rejected(ErrorCode code) {
        return new Rejected(code, null);
    }

    private static Rejected rejected(ErrorCode code, Throwable cause) {
        return new Rejected(code, cause);
    }

    public enum ErrorCode {
        BUNDLE_ZIP_INVALID,
        BUNDLE_PATH_TRAVERSAL,
        BUNDLE_DUPLICATE_PATH,
        BUNDLE_NFKC_PATH_CONFLICT,
        BUNDLE_FILE_LIMIT_EXCEEDED,
        BUNDLE_UNCOMPRESSED_LIMIT_EXCEEDED,
        BUNDLE_COMPRESSION_RATIO_EXCEEDED,
        BUNDLE_MANIFEST_REQUIRED,
        BUNDLE_MANIFEST_INVALID,
        BUNDLE_MANIFEST_MISMATCH,
        BUNDLE_ENTRY_NOT_FOUND
    }

    public record Manifest(List<BundleFile> files, String entry, boolean includesOriginals) {
        public Manifest {
            files = List.copyOf(files);
        }
    }

    public record BundleFile(String path, String sha256, long bytes) {
    }

    private record ActualFile(long bytes, String sha256) {
    }

    private static final class Rejected extends IllegalArgumentException {
        private Rejected(ErrorCode code, Throwable cause) {
            super(code.name(), cause);
        }
    }
}
