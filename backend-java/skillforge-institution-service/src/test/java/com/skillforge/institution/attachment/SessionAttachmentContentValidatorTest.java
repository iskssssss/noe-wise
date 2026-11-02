package com.skillforge.institution.attachment;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.shared.exception.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

class SessionAttachmentContentValidatorTest {
    @Test void acceptsParsedPdfAndRejectsRenamedOrPasswordProtectedPdf() throws Exception {
        SessionAttachmentContentValidator.validate("pdf", pdf(false, 1));
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("pdf", bytes("renamed")));
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("pdf", bytes("%PDF-1.7 malformed")));
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("pdf", pdf(true, 1)));
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("pdf", pdf(false, 101)));
    }

    @Test void actualImageTypeAndPixelLimitAreCheckedBeforeDecoding() throws Exception {
        var image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        SessionAttachmentContentValidator.validate("png", output.toByteArray());
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("jpg", output.toByteArray()));
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("png", bytes("fake image")));
        byte[] invalidDimensions = output.toByteArray().clone();
        // PNG IHDR 声明超大宽度；必须在像素分配之前拒绝，无需构造大图片。
        java.nio.ByteBuffer.wrap(invalidDimensions, 16, 4).putInt(4_000_001);
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("png", invalidDimensions));
    }

    @Test void officeChecksActualPackageTypeAndRejectsEntitiesMacrosAndExternalMedia() throws Exception {
        var contents = new java.util.LinkedHashMap<String, String>();
        contents.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");
        contents.put("word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body/></w:document>");
        SessionAttachmentContentValidator.validate("docx", zip(contents));
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("xlsx", zip(contents)));
        contents.put("word/_rels/document.xml.rels", "<Relationships><Relationship TargetMode=\"External\" Type=\"image\" Target=\"https://external.invalid/image.png\"/></Relationships>");
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("docx", zip(contents)));
        contents.remove("word/_rels/document.xml.rels");
        contents.put("word/document.xml", "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>");
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("docx", zip(contents)));
        contents.put("word/document.xml", "<document/>");
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("docx", zip(contents)));
        contents.put("word/vbaProject.bin", "macro");
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("docx", zip(contents)));
    }

    @Test void officeRejectsZipTraversalAndExpandedResourceBomb() throws Exception {
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("docx",
                zip(Map.of("../outside", "bad"))));
        assertThrows(ApiException.class, () -> SessionAttachmentContentValidator.validate("docx",
                zip(Map.of("word/document.xml", "a".repeat(9 * 1024 * 1024)))));
    }

    static byte[] pdf(boolean encrypted, int pages) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) document.addPage(new PDPage());
            if (encrypted) document.protect(new StandardProtectionPolicy("owner-secret", "password", new AccessPermission()));
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] zip(Map<String, String> contents) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(bytes(entry.getValue()));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
