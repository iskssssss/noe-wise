package com.skillforge.core.capability;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Skill 标准包摄取与不可变 Catalog Version 创建入口。注入预算由 SKILL.md 估算，不接受客户端手填。 */
@Service
public final class SkillPackageCatalogService {
    private final CapabilityCatalogService catalog;
    private final SkillPackageValidator validator;
    private final CapabilityDescriptorCodec descriptorCodec;

    @Autowired
    public SkillPackageCatalogService(
            CapabilityCatalogService catalog,
            CapabilityDescriptorCodec descriptorCodec) {
        this(catalog, new SkillPackageValidator(), descriptorCodec);
    }

    SkillPackageCatalogService(
            CapabilityCatalogService catalog,
            SkillPackageValidator validator,
            CapabilityDescriptorCodec descriptorCodec) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.descriptorCodec = Objects.requireNonNull(descriptorCodec, "descriptorCodec");
    }

    public CapabilityVersionView importDirectory(
            CapabilityCatalogScope scope,
            Long definitionId,
            SkillPackageImportRequest request) {
        if (request.files() == null || request.files().isEmpty()) {
            throw invalid("skill directory files are required", null);
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        try {
            request.files().forEach((path, content) ->
                    files.put(path, Base64.getDecoder().decode(content)));
        } catch (IllegalArgumentException exception) {
            throw invalid("skill directory file content must be base64", exception);
        }
        try {
            return create(scope, definitionId, request, validator.fromDirectory(files));
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage(), exception);
        }
    }

    public CapabilityVersionView importZip(
            CapabilityCatalogScope scope,
            Long definitionId,
            SkillPackageImportRequest request,
            byte[] archive) {
        try {
            return create(scope, definitionId, request, validator.fromZip(archive));
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage(), exception);
        }
    }

    public SkillPackage getPackage(CapabilityCatalogScope scope, Long versionId) {
        CapabilityVersionView version = catalog.getVersion(scope, versionId);
        if (version.capabilityType() != CapabilityType.SKILL) {
            throw invalid("capability version is not a skill package", null);
        }
        CapabilityDescriptor descriptor = descriptorCodec.decode(
                new CapabilityRef(CapabilityType.SKILL, Long.toString(version.definitionId()),
                        Long.toString(version.id())),
                version.tenantId(), version.name(), version.description(), version.trustLevel(),
                version.status(), version.contentHash(), version.descriptor());
        return ((SkillDescriptor) descriptor).skillPackage();
    }

    public List<SkillFileView> listFiles(CapabilityCatalogScope scope, Long versionId) {
        return getPackage(scope, versionId).files().stream()
                .map(file -> new SkillFileView(file.path(), file.mediaType(), file.encoding(),
                        file.sha256(), file.size()))
                .toList();
    }

    public SkillPackage.FileEntry getFile(
            CapabilityCatalogScope scope, Long versionId, String path) {
        return getPackage(scope, versionId).requireFile(path);
    }

    public byte[] download(CapabilityCatalogScope scope, Long versionId) {
        SkillPackage skillPackage = getPackage(scope, versionId);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (SkillPackage.FileEntry file : skillPackage.files()) {
                zip.putNextEntry(new ZipEntry(file.path()));
                zip.write(bytes(file));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("skill package download failed", exception);
        }
    }

    public record SkillFileView(
            String path,
            String mediaType,
            SkillPackage.Encoding encoding,
            String sha256,
            long size) {
    }

    private CapabilityVersionView create(
            CapabilityCatalogScope scope,
            Long definitionId,
            SkillPackageImportRequest request,
            SkillPackage skillPackage) {
        Map<String, Object> descriptor = SkillPackageDescriptorMapper.descriptor(
                skillPackage, request.modelInvocable(), request.userInvocable(),
                request.activationMode(), request.conflictGroup(),
                request.requiredCapabilities());
        return catalog.createDraftVersion(scope, definitionId, request.versionKey(),
                skillPackage.metadata().name(), skillPackage.metadata().description(),
                validator.packageSha256(skillPackage), descriptor);
    }

    private static RuntimeException invalid(String message, Exception cause) {
        return CapabilityCatalogErrors.exception(HttpStatus.UNPROCESSABLE_ENTITY,
                CapabilityCatalogErrors.DESCRIPTOR_INVALID, message, cause);
    }

    private static byte[] bytes(SkillPackage.FileEntry file) {
        return file.encoding() == SkillPackage.Encoding.UTF8
                ? file.content().getBytes(StandardCharsets.UTF_8)
                : Base64.getDecoder().decode(file.content());
    }
}
