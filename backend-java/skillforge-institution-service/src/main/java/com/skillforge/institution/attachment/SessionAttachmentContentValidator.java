package com.skillforge.institution.attachment;

import com.skillforge.core.chat.contract.ChatAttachmentLimits;
import com.skillforge.core.shared.exception.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.HttpStatus;

/** 上传和传输采用同一实际类型校验；只读材料，不执行宏、不解析外部 XML 实体。 */
final class SessionAttachmentContentValidator {
    private static final Set<String> TEXT = Set.of("csv", "tsv", "txt", "md", "json");
    private static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 32 * 1024 * 1024;
    private static final int MAX_XML_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 4096;

    static void validate(String extension, byte[] bytes) throws IOException {
        if (TEXT.contains(extension)) validateText(bytes);
        else switch (extension) {
            case "pdf" -> validatePdf(bytes);
            case "docx", "pptx", "xlsx" -> validateOffice(extension, bytes);
            case "png", "jpg", "jpeg" -> validateImage(extension, bytes);
            default -> throw invalid("不支持该文件类型");
        }
    }

    private static void validateText(byte[] bytes) throws IOException {
        try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8
                .newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] buffer = new char[8192]; int count;
            while ((count = reader.read(buffer)) != -1)
                for (int i = 0; i < count; i++) if (buffer[i] == 0) throw invalid("文本必须为无空字节的 UTF-8");
        } catch (java.nio.charset.CharacterCodingException malformed) {
            throw invalid("文本必须为 UTF-8");
        }
    }

    private static void validatePdf(byte[] bytes) {
        if (bytes.length < 8 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
            throw invalid("PDF 内容与扩展名不符");
        try (var pdf = PDDocument.load(new ByteArrayInputStream(bytes), MemoryUsageSetting.setupMixed(16L * 1024 * 1024))) {
            if (pdf.isEncrypted() || pdf.getNumberOfPages() < 1
                    || pdf.getNumberOfPages() > ChatAttachmentLimits.MAX_DOCUMENT_PAGES)
                throw invalid("PDF 不支持加密且不能超过100页");
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid("PDF 无法解析或需要密码");
        }
    }

    private static void validateImage(String extension, byte[] bytes) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("图片无法解码");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String actual = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(extension.equals("png") ? actual.equals("png") : Set.of("jpeg", "jpg").contains(actual)))
                    throw invalid("图片内容与扩展名不符");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > ChatAttachmentLimits.MAX_IMAGE_PIXELS)
                    throw invalid("图片不能超过4000万像素");
                var decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height)
                    throw invalid("图片无法完整解码");
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid("图片无法解码");
        }
    }

    private static void validateOffice(String extension, byte[] bytes) throws IOException {
        String main = switch (extension) {
            case "docx" -> "word/document.xml";
            case "pptx" -> "ppt/presentation.xml";
            case "xlsx" -> "xl/workbook.xml";
            default -> throw new IllegalStateException();
        };
        Set<String> names = new HashSet<>();
        long total = 0; int slides = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName(), lower = name.toLowerCase(Locale.ROOT);
                if (names.size() >= MAX_ZIP_ENTRIES || !names.add(name) || name.startsWith("/")
                        || name.contains("\\") || name.contains(":") || java.util.Arrays.asList(name.split("/")).contains(".."))
                    throw invalid("Office 包路径或文件数量不合法");
                if (lower.contains("vbaproject") || lower.contains("/embeddings/") || lower.contains("/activex/"))
                    throw invalid("Office 不支持宏、嵌入对象或 ActiveX");
                boolean xml = lower.endsWith(".xml") || lower.endsWith(".rels");
                int limit = xml ? MAX_XML_BYTES : MAX_ENTRY_BYTES;
                byte[] content = zip.readNBytes(limit + 1);
                total += content.length;
                if (content.length > limit || total > MAX_EXPANDED_BYTES)
                    throw invalid("Office 解压后内容超出容量");
                if (entry.getCompressedSize() > 0 && content.length > 1024 * 1024
                        && content.length > entry.getCompressedSize() * 200L)
                    throw invalid("Office 压缩率超出容量");
                if (xml) validateXml(name, content);
                if (name.matches("ppt/slides/slide[0-9]+\\.xml") && ++slides > ChatAttachmentLimits.MAX_DOCUMENT_PAGES)
                    throw invalid("PPTX 不能超过100页");
                zip.closeEntry();
            }
        } catch (IOException malformed) { throw invalid("Office ZIP 包损坏"); }
        if (!names.contains("[Content_Types].xml") || !names.contains(main))
            throw invalid("Office 内容与扩展名不符");
    }

    private static void validateXml(String name, byte[] content) {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("external_entity_forbidden");
        });
        try {
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(content));
            try {
                boolean root = true;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
                        throw invalid("Office XML 不支持 DTD 或实体");
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (root) {
                            root = false;
                            String expected = switch (name) {
                                case "word/document.xml" -> "document";
                                case "ppt/presentation.xml" -> "presentation";
                                case "xl/workbook.xml" -> "workbook";
                                case "[Content_Types].xml" -> "Types";
                                default -> null;
                            };
                            if (expected != null && !expected.equals(reader.getLocalName()))
                                throw invalid("Office 主文档结构与扩展名不符");
                            String namespace = switch (name) {
                                case "word/document.xml" -> "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
                                case "ppt/presentation.xml" -> "http://schemas.openxmlformats.org/presentationml/2006/main";
                                case "xl/workbook.xml" -> "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
                                case "[Content_Types].xml" -> "http://schemas.openxmlformats.org/package/2006/content-types";
                                default -> null;
                            };
                            if (namespace != null && !namespace.equals(reader.getNamespaceURI()))
                                throw invalid("Office 主文档命名空间与扩展名不符");
                        }
                        if (name.endsWith(".rels") && "External".equals(reader.getAttributeValue(null, "TargetMode"))) {
                            String type = reader.getAttributeValue(null, "Type");
                            if (type == null || !type.endsWith("/hyperlink")) throw invalid("Office 不支持外链媒体或连接");
                        }
                        if (name.equals("[Content_Types].xml")) {
                            String type = reader.getAttributeValue(null, "ContentType");
                            if (type != null && type.toLowerCase(Locale.ROOT).contains("macroenabled"))
                                throw invalid("Office 不支持宏内容类型");
                        }
                    }
                }
            } finally { reader.close(); }
        } catch (XMLStreamException malformed) { throw invalid("Office XML 不是有效安全文档"); }
    }

    private static ApiException invalid(String reason) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "附件内容不合法：" + reason);
    }
    private SessionAttachmentContentValidator() { }
}
