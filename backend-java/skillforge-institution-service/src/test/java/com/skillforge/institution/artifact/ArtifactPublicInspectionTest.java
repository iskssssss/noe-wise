package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/** 对真实文件字节提取，验证业务文本保留、首次公开拒绝以及无 OCR 的媒体边界。 */
class ArtifactPublicInspectionTest {
    private static final String PRIVATE = "private-artifact-6b51a6ad";
    private final PublicOutputGuard.Scope scope = new PublicOutputGuard.Scope(List.of(PRIVATE));
    private final ArtifactPublicInspection inspection = new ArtifactPublicInspection(mock(PublicOutputGuard.class));

    @Test void businessUuidCodeAndApiFieldNamesAreKept() {
        String code = "// business API\nconst request = { run_id: 'order-2026-09', artifact_id: '"
                + "c6c95a02-2967-440b-9bfa-210e1e2e167d', amount: 120 };";
        assertDoesNotThrow(() -> inspect(code.getBytes(StandardCharsets.UTF_8), "order.ts"));
    }

    @Test void rejectsPrivateValuesSplitAcrossStreamReadBoundaries() {
        String prefix = "x".repeat(8192 - 8);
        assertThrows(ArtifactPublicInspection.Rejection.class,
                () -> inspect((prefix + PRIVATE).getBytes(StandardCharsets.UTF_8), "report.txt"));
    }

    @Test void rejectsActualPrivateTextAndNameWithoutRewriting() {
        var rejected = assertThrows(ArtifactPublicInspection.Rejection.class,
                () -> inspect(("业务正文 " + PRIVATE).getBytes(StandardCharsets.UTF_8), "report.md"));
        assertEquals(PublicOutputGuard.REJECTION_CODE, rejected.getMessage());
        assertThrows(ArtifactPublicInspection.Rejection.class,
                () -> inspect("safe".getBytes(StandardCharsets.UTF_8), PRIVATE + ".txt"));
    }

    @Test void decodesJsonStringsAndKeysAndHtmlAttributesBeforeChecking() {
        String escaped = PRIVATE.replace("private", "\\u0070rivate");
        for (String json : List.of("{\"value\":\"" + escaped + "\"}", "{\"" + escaped + "\":1}"))
            assertThrows(ArtifactPublicInspection.Rejection.class,
                    () -> inspect(json.getBytes(StandardCharsets.UTF_8), "report.json"));
        String html = "<a href=\"" + PRIVATE.replace("private", "&#112;rivate") + "\">业务链接</a>";
        assertThrows(ArtifactPublicInspection.Rejection.class,
                () -> inspect(html.getBytes(StandardCharsets.UTF_8), "index.html"));
    }

    @Test void officeBusinessContentPassesAndActualPrivateBodyOrMetadataRejects() throws Exception {
        for (var file : officeFiles("业务订单 B20260930", "业务结果"))
            assertDoesNotThrow(() -> inspect(file.bytes(), file.name()), file.name());
        for (var file : officeFiles(PRIVATE, "业务结果"))
            assertThrows(ArtifactPublicInspection.Rejection.class,
                    () -> inspect(file.bytes(), file.name()), file.name());
        for (var file : officeFiles("业务订单 B20260930", PRIVATE))
            assertThrows(ArtifactPublicInspection.Rejection.class,
                    () -> inspect(file.bytes(), file.name()), file.name());
    }

    @Test void pdfTextAndMetadataAreExtracted() throws Exception {
        assertDoesNotThrow(() -> inspect(pdf("order-20260930", "Business result"), "report.pdf"));
        assertThrows(ArtifactPublicInspection.Rejection.class,
                () -> inspect(pdf(PRIVATE, "Business result"), "report.pdf"));
        assertThrows(ArtifactPublicInspection.Rejection.class,
                () -> inspect(pdf("order-20260930", PRIVATE), "report.pdf"));
    }

    @Test void officeTextSplitAcrossRunsIsCheckedAsReadableContent() throws Exception {
        byte[] bytes;
        try(var doc=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            var paragraph=doc.createParagraph();
            paragraph.createRun().setText(PRIVATE.substring(0,10));
            paragraph.createRun().setText(PRIVATE.substring(10));
            doc.write(output);bytes=output.toByteArray();
        }
        assertThrows(ArtifactPublicInspection.Rejection.class,()->inspect(bytes,"report.docx"));
    }

    @Test void imagePixelsAreNotOcrButImageMetadataIsChecked() throws Exception {
        assertDoesNotThrow(() -> inspect(png(PRIVATE, "Business result"), "result.png"));
        assertThrows(ArtifactPublicInspection.Rejection.class,
                () -> inspect(png("Business result", PRIVATE), "result.png"));
    }

    @Test void mediaExtensionCannotHideAPlainTextPrivateValue() {
        assertThrows(ArtifactPublicInspection.Rejection.class,
                () -> inspect(PRIVATE.getBytes(StandardCharsets.UTF_8), "result.png"));
    }

    @Test void recursivelyChecksArchivesAndRejectsDepthAndEntryOverflow() throws Exception {
        byte[] privateZip = zip(List.of(new FileBytes("report.txt", PRIVATE.getBytes(StandardCharsets.UTF_8))));
        byte[] nested = zip(List.of(new FileBytes("nested.zip", privateZip)));
        assertThrows(ArtifactPublicInspection.Rejection.class, () -> inspect(nested, "result.zip"));
        byte[] deep = zip(List.of(new FileBytes("safe.txt", "safe".getBytes(StandardCharsets.UTF_8))));
        for (int i = 0; i < 5; i++) deep = zip(List.of(new FileBytes("nested.zip", deep)));
        byte[] tooDeep = deep;
        assertThrows(ArtifactPublicInspection.Rejection.class, () -> inspect(tooDeep, "result.zip"));
        var entries = new ArrayList<FileBytes>();
        for (int i = 0; i <= ArtifactPublicInspection.MAX_ENTRIES; i++) entries.add(new FileBytes("safe-" + i + ".txt", new byte[0]));
        byte[] crowded = zip(entries);
        assertThrows(ArtifactPublicInspection.Rejection.class, () -> inspect(crowded, "result.zip"));
    }

    @Test void rejectsCompressionExpansionTextBudgetAndUnsafeEntryPaths() throws Exception {
        byte[] compressed = zip(List.of(new FileBytes("safe.txt", "x".repeat(1024 * 1024).getBytes(StandardCharsets.UTF_8))));
        assertThrows(ArtifactPublicInspection.Rejection.class, () -> inspect(compressed, "result.zip"));
        byte[] text = "x".repeat(ArtifactPublicInspection.MAX_TEXT + 1).getBytes(StandardCharsets.UTF_8);
        assertThrows(ArtifactPublicInspection.Rejection.class, () -> inspect(text, "result.txt"));
        byte[] unsafe = zip(List.of(new FileBytes("../outside.txt", "safe".getBytes(StandardCharsets.UTF_8))));
        assertThrows(ArtifactPublicInspection.Rejection.class, () -> inspect(unsafe, "result.zip"));
    }

    @Test void unknownBinaryMalformedTextAndExpiredDeadlineFailClosed() {
        assertThrows(ArtifactPublicInspection.Rejection.class, () -> inspect(new byte[]{(byte) 0xff, 0, 7, 3}, "unknown.bin"));
        assertThrows(ArtifactPublicInspection.Rejection.class, () -> inspect(new byte[]{(byte) 0xc3, 0x28}, "report.txt"));
        assertThrows(ArtifactPublicInspection.Rejection.class, () -> inspect(new byte[]{1, 2, 3}, "broken.zip"));
        assertThrows(ArtifactPublicInspection.Rejection.class, () -> inspection.inspect(
                "safe".getBytes(StandardCharsets.UTF_8), "report.txt", scope, Instant.now().minusSeconds(1), () -> { }));
    }

    private void inspect(byte[] bytes, String name) throws Exception {
        inspection.inspect(bytes, name, scope, Instant.now().plusSeconds(30), () -> { });
    }

    private static List<FileBytes> officeFiles(String body, String title) throws Exception {
        var files = new ArrayList<FileBytes>();
        try (var doc = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(body);
            doc.getProperties().getCoreProperties().setTitle(title);
            doc.write(bytes); files.add(new FileBytes("report.docx", bytes.toByteArray()));
        }
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            workbook.createSheet("Orders").createRow(0).createCell(0).setCellValue(body);
            workbook.getProperties().getCoreProperties().setTitle(title);
            workbook.write(bytes); files.add(new FileBytes("report.xlsx", bytes.toByteArray()));
        }
        try (var slides = new XMLSlideShow(); var bytes = new ByteArrayOutputStream()) {
            slides.createSlide().createTextBox().setText(body);
            slides.getProperties().getCoreProperties().setTitle(title);
            slides.write(bytes); files.add(new FileBytes("report.pptx", bytes.toByteArray()));
        }
        return files;
    }

    private static byte[] pdf(String body, String title) throws Exception {
        try (var doc = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage(); doc.addPage(page);
            doc.getDocumentInformation().setTitle(title);
            try (var text = new PDPageContentStream(doc, page)) {
                text.beginText(); text.setFont(PDType1Font.HELVETICA, 12);
                text.newLineAtOffset(30, 700); text.showText(body); text.endText();
            }
            doc.save(bytes); return bytes.toByteArray();
        }
    }

    private static byte[] png(String pixels, String comment) throws Exception {
        var image = new BufferedImage(400, 50, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,image.getWidth(),image.getHeight());
            graphics.setColor(java.awt.Color.BLACK);graphics.drawString(pixels,5,25);
        } finally { graphics.dispose(); }
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        try (var bytes = new ByteArrayOutputStream(); var output = new MemoryCacheImageOutputStream(bytes)) {
            var metadata = writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), null);
            var root = new IIOMetadataNode("javax_imageio_png_1.0");
            var text = new IIOMetadataNode("tEXt"); var entry = new IIOMetadataNode("tEXtEntry");
            entry.setAttribute("keyword", "Comment"); entry.setAttribute("value", comment);
            text.appendChild(entry); root.appendChild(text); metadata.mergeTree("javax_imageio_png_1.0", root);
            writer.setOutput(output); writer.write(null, new javax.imageio.IIOImage(image, null, metadata), null);
            output.flush(); return bytes.toByteArray();
        } finally { writer.dispose(); }
    }

    private static byte[] zip(List<FileBytes> files) throws Exception {
        try (var bytes = new ByteArrayOutputStream(); var archive = new ZipOutputStream(bytes)) {
            for (var file : files) {
                archive.putNextEntry(new ZipEntry(file.name())); archive.write(file.bytes()); archive.closeEntry();
            }
            archive.finish(); return bytes.toByteArray();
        }
    }

    private record FileBytes(String name, byte[] bytes) { }
}
