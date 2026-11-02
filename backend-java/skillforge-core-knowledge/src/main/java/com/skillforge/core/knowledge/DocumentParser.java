package com.skillforge.core.knowledge;

import com.skillforge.core.shared.exception.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传文档文本提取器。
 *
 * <p>txt / md 按 UTF-8 直读；pdf / docx 走 Apache Tika
 * 解析；解析失败抛 422 并附带可读原因。
 */
public final class DocumentParser {

    /** 支持的文件扩展名白名单。 */
    private static final Set<String> SUPPORTED = Set.of("txt", "md", "markdown", "pdf", "docx");

    private DocumentParser() {
    }

    /**
     * 解析上传文件为纯文本。
     *
     * @param file 上传文件
     * @return 解析结果（文本与元数据）
     */
    public static ParsedDocument parse(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        try {
            return parseBytes(file.getBytes(), filename);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "无法解析文件: " + filename + "（" + e.getMessage() + "）", e);
        }
    }

    /**
     * 解析字节内容为纯文本。
     *
     * @param bytes    文件字节
     * @param filename 原始文件名（决定扩展名与解析方式）
     * @return 解析结果（文本与元数据）
     */
    public static ParsedDocument parseBytes(byte[] bytes, String filename) {
        String extension = extensionOf(filename);
        if (!SUPPORTED.contains(extension)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "不支持的文件类型: " + extension + "（支持 txt / md / pdf / docx）");
        }
        try {
            if ("txt".equals(extension) || "md".equals(extension) || "markdown".equals(extension)) {
                return new ParsedDocument(decodeText(bytes), filename, extension, (long) bytes.length, "direct");
            }
            return new ParsedDocument(parseWithTika(bytes), filename, extension, (long) bytes.length, "tika");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "无法解析文件: " + filename + "（" + e.getMessage() + "）", e);
        }
    }

    /**
     * 文本文件按 UTF-8 严格解码。
     *
     * @param bytes 文件字节
     * @return 解码文本
     * @throws IOException 解码失败
     */
    private static String decodeText(byte[] bytes) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * 通过 Tika 解析 PDF / DOCX。
     *
     * @param bytes 文件字节
     * @return 提取的纯文本
     * @throws Exception 解析异常
     */
    private static String parseWithTika(byte[] bytes) throws Exception {
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            new AutoDetectParser().parse(input, handler, new Metadata(), new ParseContext());
            return handler.toString();
        }
    }

    /**
     * 取文件扩展名（小写）。
     *
     * @param filename 文件名
     * @return 扩展名；无扩展名返回空串
     */
    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 解析结果。
     *
     * @param content          提取文本
     * @param originalFilename 原始文件名
     * @param extension        文件扩展名
     * @param sizeBytes        文件大小（字节）
     * @param parsedBy         解析方式：direct 直读 / tika 解析
     */
    public record ParsedDocument(
            String content, String originalFilename, String extension, Long sizeBytes, String parsedBy) {
    }
}
