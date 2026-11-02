package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.*;
import java.util.*;

/** 只读取原发布的封闭 Skill schema；错误不回显指令。 */
final class SkillDescriptorDecoder {
    private SkillDescriptorDecoder() { }
    static SkillActivation.Descriptor decode(TypedPayload payload, CodecLimits limits) {
        try { return read(payload, limits); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private static SkillActivation.Descriptor read(TypedPayload payload, CodecLimits limits) {
        Objects.requireNonNull(payload); Objects.requireNonNull(limits);
        if (!payload.schemaId().equals(SkillActivation.DESCRIPTOR_SCHEMA)) throw invalid();
        String source = payload.canonicalJson();
        if (source.length() > limits.maxCodeUnits()) throw invalid();
        // 唯一合法数字位于 estimated_tokens；保留数字来源计数，拒绝用同形对象伪造数字。
        var bridged = new StringBuilder(source.length());
        boolean quoted = false, escaped = false;
        int numbers = 0;
        for (int index = 0; index < source.length(); index++) {
            char ch = source.charAt(index);
            if (quoted) {
                bridged.append(ch);
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') { quoted = true; bridged.append(ch); }
            else if (ch >= '0' && ch <= '9') {
                int start = index;
                while (index + 1 < source.length() && source.charAt(index + 1) >= '0' && source.charAt(index + 1) <= '9') index++;
                String number = source.substring(start, index + 1);
                if (++numbers != 1 || number.length() > 19 || number.charAt(0) == '0') throw invalid();
                bridged.append("{\"$number\":\"").append(number).append("\"}");
            } else { bridged.append(ch); }
        }
        if (numbers != 1) throw invalid();
        // 桥仅增加一个对象节点、一层深度和固定字符数，业务原文额度不改变。
        var parsed = CanonicalJson.parseDocument(bridged.toString(), new CodecLimits(
                Math.addExact(limits.maxCodeUnits(), 16), Math.addExact(limits.maxNodes(), 1),
                Math.min(128, Math.addExact(limits.maxDepth(), 1))));
        parsed.requireFields("model_invocable", "user_invocable", "activation_mode", "package",
                "conflict_group", "required_capabilities", "estimated_tokens");
        var skillPackage = parsed.field("package").requireFields("entrypoint", "metadata", "files");
        if (!skillPackage.field("entrypoint").stringValue().equals("SKILL.md")) throw invalid();
        var packageMetadata = skillPackage.field("metadata").requireFields(
                "name", "description", "license", "compatibility", "extensions", "allowed_tools");
        packageMetadata.field("license"); packageMetadata.field("compatibility");
        for (var field : packageMetadata.field("extensions").objectFields()) field.value().stringValue();
        var planRequirement = packageMetadata.field("extensions").optionalField("skillforge-plan").map(CanonicalJson.Value::stringValue).orElse("optional");
        if (!Set.of("required", "optional").contains(planRequirement)) throw invalid();
        var allowedTools = packageMetadata.field("allowed_tools").arrayValue().stream()
                .map(CanonicalJson.Value::stringValue).toList();
        var metadata = new SkillActivation.Metadata(packageMetadata.field("name").stringValue(),
                packageMetadata.field("description").stringValue(), allowedTools, "required".equals(planRequirement));
        var resources = skillPackage.field("files").arrayValue().stream().map(value -> {
            value.requireFields("path", "media_type", "encoding", "content", "sha256", "size");
            var resource = new SkillActivation.Resource(value.field("path").stringValue(),
                    value.field("media_type").stringValue(), value.field("encoding").stringValue(),
                    value.field("content").stringValue(), value.field("sha256").stringValue(),
                    value.field("size").longValue());
            byte[] bytes;
            try {
                bytes = resource.encoding().equals("UTF8")
                        ? resource.content().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        : java.util.Base64.getDecoder().decode(resource.content());
            } catch (IllegalArgumentException exception) { throw invalid(); }
            if (bytes.length != resource.size() || !sha256(bytes).equals(resource.sha256())) throw invalid();
            return resource;
        }).toList();
        var required = parsed.field("required_capabilities").arrayValue().stream().map(value -> {
            value.requireFields("type", "capability_id", "version_id");
            return new CapabilityRef(CapabilityType.valueOf(value.field("type").stringValue()),
                    value.field("capability_id").stringValue(), value.field("version_id").stringValue());
        }).toList();
        var group = parsed.field("conflict_group");
        long estimate = parsed.field("estimated_tokens").requireFields("$number").field("$number").longValue();
        return new SkillActivation.Descriptor(parsed.field("model_invocable").booleanValue(),
                parsed.field("user_invocable").booleanValue(), SkillActivation.Mode.valueOf(parsed.field("activation_mode").stringValue()),
                metadata, resources, group.isNull() ? Optional.empty() : Optional.of(group.stringValue()), required, estimate);
    }
    private static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("skill_descriptor_rejected"); }
}
