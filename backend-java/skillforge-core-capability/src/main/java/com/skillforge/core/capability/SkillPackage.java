package com.skillforge.core.capability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Agent Skills 标准目录包；文件内容与摘要共同属于不可变 SkillVersion。 */
public record SkillPackage(
        String entrypoint,
        Metadata metadata,
        List<FileEntry> files) {

    public enum Encoding { UTF8, BASE64 }

    public record Metadata(
            String name,
            String description,
            Optional<String> license,
            Optional<String> compatibility,
            Map<String, String> extensions,
            List<String> allowedTools) {
        public Metadata {
            requireText(name, "skill package name");
            requireText(description, "skill package description");
            license = Objects.requireNonNull(license, "license");
            compatibility = Objects.requireNonNull(compatibility, "compatibility");
            extensions = Map.copyOf(new LinkedHashMap<>(
                    Objects.requireNonNull(extensions, "extensions")));
            String planRequirement = extensions.getOrDefault("skillforge-plan", "optional");
            if (!"required".equals(planRequirement) && !"optional".equals(planRequirement)) {
                throw new IllegalArgumentException("SKILL.md metadata.skillforge-plan 仅接受 required 或 optional");
            }
            allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        }

        /** 仅消费发布包冻结的显式声明，正文内容不决定计划要求。 */
        public String planRequirement() {
            return extensions.getOrDefault("skillforge-plan", "optional");
        }

        public boolean requiresPlan() {
            return "required".equals(planRequirement());
        }
    }

    public record FileEntry(
            String path,
            String mediaType,
            Encoding encoding,
            String content,
            String sha256,
            long size) {
        public FileEntry {
            requireText(path, "skill file path");
            requireText(mediaType, "skill file mediaType");
            Objects.requireNonNull(encoding, "encoding");
            Objects.requireNonNull(content, "content");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("skill file sha256 must be lowercase hex");
            }
            if (size < 0) {
                throw new IllegalArgumentException("skill file size must not be negative");
            }
        }
    }

    public SkillPackage {
        if (!"SKILL.md".equals(entrypoint)) {
            throw new IllegalArgumentException("skill package entrypoint must be SKILL.md");
        }
        Objects.requireNonNull(metadata, "metadata");
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (files.isEmpty() || files.stream().noneMatch(file -> entrypoint.equals(file.path()))) {
            throw new IllegalArgumentException("skill package requires SKILL.md");
        }
        if (files.stream().map(FileEntry::path).distinct().count() != files.size()) {
            throw new IllegalArgumentException("skill package file paths must be unique");
        }
    }

    public FileEntry requireFile(String path) {
        return files.stream().filter(file -> file.path().equals(path)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("skill package file not found"));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
