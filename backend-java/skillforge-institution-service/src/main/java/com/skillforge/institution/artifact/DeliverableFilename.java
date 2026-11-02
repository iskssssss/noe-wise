package com.skillforge.institution.artifact;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 交付物下载名的纯规则。该类只产出安全的单段文件名，不负责 Content-Disposition 的中文编码。
 */
public final class DeliverableFilename {
    private static final int MAX_CODE_POINTS = 200;
    private static final int MAX_UTF8_BYTES = 255;
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private DeliverableFilename() {
    }

    /**
     * 在已有同目录文件名中确定性追加 {@code _1}、{@code _2} 等后缀；比较使用 NFKC 与大小写无关的文件名。
     */
    public static String deduplicate(String preferredFilename, Collection<String> existingFilenames) {
        String preferred = validateFilename(preferredFilename);
        Set<String> occupied = new HashSet<>();
        if (existingFilenames != null) {
            for (String filename : existingFilenames) {
                if (filename != null) {
                    occupied.add(comparisonKey(normalize(filename)));
                }
            }
        }
        if (!occupied.contains(comparisonKey(preferred))) {
            return preferred;
        }
        NameParts parts = splitExtension(preferred);
        for (long sequence = 1; sequence <= Integer.MAX_VALUE; sequence++) {
            String candidate = validateFilename(parts.stem() + "_" + sequence + parts.extension());
            if (!occupied.contains(comparisonKey(candidate))) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("没有可用的交付物文件名");
    }

    /**
     * 为历史版本生成带 {@code vN} 的文件名，例如 {@code 报告-v3.pdf}。
     */
    public static String legacyVersion(String filename, long version) {
        if (version < 1) {
            throw new IllegalArgumentException("历史版本必须大于零");
        }
        NameParts parts = splitExtension(validateFilename(filename));
        return validateFilename(parts.stem() + "-v" + version + parts.extension());
    }

    /** 校验已有文件名仍是一个可安全下载或存储的单段名称，并返回其 NFKC 规范形式。 */
    public static String validateFilename(String filename) {
        String normalized = normalizePart(filename, "文件名");
        if (normalized.endsWith(".") || normalized.endsWith(" ")) {
            throw new IllegalArgumentException("文件名不能以点或空格结束");
        }
        String stem = trimWindowsSuffix(splitExtension(normalized).stem());
        if (WINDOWS_RESERVED.contains(stem.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("文件名不能使用 Windows 保留名");
        }
        requireLimits(normalized);
        return normalized;
    }

    /** 格式独立于展示标题校验，不用于改写实际文件名。 */
    public static String validateFormat(String format) {
        String normalized = normalizePart(format, "格式");
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("格式必须是无点的安全扩展名");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizePart(String value, String label) {
        Objects.requireNonNull(value, label + "不能为空");
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isISOControl(codePoint) || "\\/:*?\"<>|".indexOf(codePoint) >= 0) {
                throw new IllegalArgumentException(label + "包含路径或控制字符");
            }
            offset += Character.charCount(codePoint);
        }
        requireLimits(normalized);
        return normalized;
    }

    private static void requireLimits(String value) {
        if (value.codePointCount(0, value.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("文件名最多包含 " + MAX_CODE_POINTS + " 个码点");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("文件名 UTF-8 编码最多 " + MAX_UTF8_BYTES + " 字节");
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC);
    }

    private static String comparisonKey(String filename) {
        return filename.toLowerCase(Locale.ROOT);
    }

    private static String trimWindowsSuffix(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '.' || value.charAt(end - 1) == ' ')) {
            end--;
        }
        return value.substring(0, end);
    }

    private static NameParts splitExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return new NameParts(filename, "");
        }
        return new NameParts(filename.substring(0, dot), filename.substring(dot));
    }

    private record NameParts(String stem, String extension) {
    }
}
