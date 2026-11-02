package com.skillforge.institution.artifact;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.runtime.api.port.RunIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** 生成文件首次公开检查：有界流式读取，不改写文件，不把正文或命中值写入日志。 */
@Component
public final class ArtifactPublicInspection {
    private static final String EXTRACTOR_POLICY_VERSION = "tika-2.9.2-stream-v2";
    public static final String POLICY_VERSION = PublicOutputGuard.POLICY_VERSION + "/" + EXTRACTOR_POLICY_VERSION;
    public static final int MAX_INPUT_BYTES = 256 * 1024 * 1024;
    public static final long MAX_BATCH_BYTES = 1024L * 1024 * 1024;
    public static final int INSPECTION_TIMEOUT_SECONDS = 120;
    public static final Duration MAX_SCAN_DURATION = Duration.ofSeconds(INSPECTION_TIMEOUT_SECONDS);
    public static final int MAX_TEXT = 32 * 1024 * 1024;
    public static final int MAX_ENTRIES = 16_384;
    private static final int MAX_DEPTH = 4;
    private static final int MAX_COMPRESSION_RATIO = 100;
    private static final int READ_BUFFER_BYTES = 8192;
    private static final String ZIP_EXTENSION = "zip";
    private static final String JSON_EXTENSION = "json";
    private static final String UNKNOWN_CONTENT_TYPE = "application/octet-stream";
    private static final String UNNAMED_EMBEDDED_FILE = "embedded.bin";
    private static final Set<String> MARKUP = Set.of("html", "htm", "svg", "xml");
    private static final Set<String> MEDIA_TYPES = Set.of("image", "audio", "video");
    private static final Set<String> TEXT = Set.of("txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml",
            "html", "htm", "svg", "js", "mjs", "cjs", "ts", "tsx", "jsx", "css", "scss", "java", "py", "sql",
            "sh", "ps1", "bat", "cmd", "c", "h", "cpp", "hpp", "go", "rs", "rb", "php", "toml", "ini", "log");
    private static final Set<String> ARCHIVES = Set.of("zip", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub");
    private final PublicOutputGuard guard;

    public ArtifactPublicInspection(PublicOutputGuard guard) { this.guard = guard; }

    public PublicOutputGuard.Scope scope(RunIdentity run) {
        return guard.capture(run);
    }

    /** 同批文件共享时间、提取字符和展开预算；检查结束后才允许调用方发布。 */
    public void inspectBatch(List<Candidate> files, PublicOutputGuard.Scope scope, Instant deadline, Runnable control)
            throws IOException {
        var budget = new Budget(deadline, control);
        long total = 0;
        for (var file : files) {
            budget.check();
            total = Math.addExact(total, Files.size(file.file()));
            if (total > MAX_BATCH_BYTES) throw rejected();
            inspect(file.file(), file.name(), scope, budget, 0);
        }
    }

    public record Candidate(Path file, String name) { }

    public void inspect(Path file, String name, PublicOutputGuard.Scope scope, Instant deadline, Runnable control)
            throws IOException {
        inspectBatch(List.of(new Candidate(file, name)), scope, deadline, control);
    }

    public void inspect(byte[] bytes, String name, PublicOutputGuard.Scope scope, Instant deadline, Runnable control)
            throws IOException {
        if (bytes.length > MAX_INPUT_BYTES) throw rejected();
        Path temporary = Files.createTempFile("skillforge-public-inspection-", ".scan");
        try {
            Files.write(temporary, bytes);
            inspect(temporary, name, scope, deadline, control);
        } finally { Files.deleteIfExists(temporary); }
    }

    private void inspect(Path file, String name, PublicOutputGuard.Scope scope, Budget budget, int depth)
            throws IOException {
        budget.check();
        if (Files.size(file) > MAX_INPUT_BYTES || depth > MAX_DEPTH || scope.rejects(name)) throw rejected();
        String extension = extension(name);
        if (ARCHIVES.contains(extension)) {
            inspectArchive(file, scope, budget, depth);
            if (ZIP_EXTENSION.equals(extension)) return;
        }
        if (TEXT.contains(extension) || name.startsWith(".") || !name.contains(".")) {
            inspectText(file, scope, budget);
            if (JSON_EXTENSION.equals(extension)) inspectJson(file, scope, budget);
            else if (MARKUP.contains(extension)) parse(file, name, scope, budget, depth);
            return;
        }
        parse(file, name, scope, budget, depth);
    }

    private void inspectArchive(Path file, PublicOutputGuard.Scope scope, Budget budget, int depth) throws IOException {
        try (var header = Files.newInputStream(file)) {
            byte[] signature = header.readNBytes(4);
            if (signature.length < 4 || signature[0] != 'P' || signature[1] != 'K'
                    || !((signature[2] == 3 && signature[3] == 4) || (signature[2] == 5 && signature[3] == 6)))
                throw rejected();
        }
        try (var archive = new ZipInputStream(Files.newInputStream(file))) {
            java.util.zip.ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                budget.check();
                if (++budget.entries > MAX_ENTRIES) throw rejected();
                String path = ArtifactStorage.safePath(entry.isDirectory() ? entry.getName().replaceAll("/$", "") : entry.getName());
                if (scope.rejects(path)) throw rejected();
                if (entry.isDirectory()) continue;
                Path temporary = Files.createTempFile("skillforge-public-member-", ".scan");
                try {
                    long size = spool(archive, temporary, budget);
                    if (entry.getCompressedSize() > 0
                            && size > entry.getCompressedSize() * (long) MAX_COMPRESSION_RATIO) throw rejected();
                    inspect(temporary, path, scope, budget, depth + 1);
                } finally { Files.deleteIfExists(temporary); }
            }
        } catch (Rejection rejection) { throw rejection; }
        catch (IOException | RuntimeException invalid) { throw rejected(invalid); }
    }

    private static void inspectText(Path file, PublicOutputGuard.Scope scope, Budget budget) throws IOException {
        var scanner = new TextScanner(scope, budget);
        try (var reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] chars = new char[READ_BUFFER_BYTES]; int count;
            while ((count = reader.read(chars)) != -1) scanner.accept(new String(chars, 0, count));
        } catch (IOException invalid) { throw rejected(invalid); }
    }

    private static void inspectJson(Path file, PublicOutputGuard.Scope scope, Budget budget) throws IOException {
        try (var input = Files.newInputStream(file); var parser = new JsonFactory().createParser(input)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                budget.check();
                if (token == JsonToken.VALUE_STRING || token == JsonToken.FIELD_NAME)
                    checkText(parser.getText(), scope, budget);
            }
        } catch (IOException | RuntimeException invalid) { throw rejected(invalid); }
    }

    private void parse(Path file, String name, PublicOutputGuard.Scope scope, Budget budget, int depth) throws IOException {
        Metadata metadata = new Metadata(); metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
        var parser = new AutoDetectParser();
        final boolean onlyMetadata;
        try (var input = TikaInputStream.get(file)) {
            onlyMetadata = MEDIA_TYPES.contains(parser.getDetector().detect(input, metadata).getType());
        } catch (Exception invalid) { throw rejected(invalid); }
        var scanner = new TextScanner(scope, budget);
        var handler = new DefaultHandler() {
            @Override public void characters(char[] chars, int start, int length) throws SAXException {
                try {
                    budget.check();
                    if (!onlyMetadata) scanner.accept(new String(chars, start, length));
                } catch (IOException rejection) { throw new SAXException(rejection); }
            }
            @Override public void startElement(String uri, String local, String qName, Attributes attributes) throws SAXException {
                try {
                    budget.check();
                    for (int index = 0; index < attributes.getLength(); index++)
                        checkText(attributes.getValue(index), scope, budget);
                } catch (IOException rejection) { throw new SAXException(rejection); }
            }
        };
        ParseContext context = new ParseContext();
        var pdf = new PDFParserConfig(); pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR); context.set(PDFParserConfig.class, pdf);
        var ocr = new TesseractOCRConfig(); ocr.setSkipOcr(true); context.set(TesseractOCRConfig.class, ocr);
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override public boolean shouldParseEmbedded(Metadata embedded) { return true; }
            @Override public void parseEmbedded(InputStream input, ContentHandler ignored, Metadata embedded, boolean outputHtml)
                    throws SAXException, IOException {
                budget.check();
                if (++budget.entries > MAX_ENTRIES || depth >= MAX_DEPTH) throw rejected();
                checkMetadata(embedded, scope, budget);
                if (onlyMetadata) return;
                Path temporary = Files.createTempFile("skillforge-public-embedded-", ".scan");
                try {
                    spool(input, temporary, budget);
                    String embeddedName = embedded.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                    inspect(temporary, embeddedName == null ? UNNAMED_EMBEDDED_FILE : embeddedName, scope, budget, depth + 1);
                } finally { Files.deleteIfExists(temporary); }
            }
        });
        try (var input = TikaInputStream.get(file)) {
            parser.parse(input, handler, metadata, context);
            budget.check();
            if (!onlyMetadata && UNKNOWN_CONTENT_TYPE.equals(metadata.get(Metadata.CONTENT_TYPE))) throw rejected();
            checkMetadata(metadata, scope, budget);
        } catch (Rejection rejection) { throw rejection; }
        catch (Exception invalid) { throw rejected(invalid); }
    }

    private static long spool(InputStream input, Path target, Budget budget) throws IOException {
        long size = 0;
        try (var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[READ_BUFFER_BYTES]; int count;
            while ((count = input.read(buffer)) != -1) {
                budget.check(); size = Math.addExact(size, count); budget.expanded = Math.addExact(budget.expanded, count);
                if (size > MAX_INPUT_BYTES || budget.expanded > MAX_BATCH_BYTES) throw rejected();
                output.write(buffer, 0, count);
            }
        }
        return size;
    }

    private static void checkMetadata(Metadata metadata, PublicOutputGuard.Scope scope, Budget budget) throws IOException {
        for (String key : metadata.names()) {
            checkText(key, scope, budget);
            for (String value : metadata.getValues(key)) {
                // 加密文档即使解析器只返回元数据，也不能被记为全文检查通过。
                if (key.toLowerCase(Locale.ROOT).contains("encrypted") && Boolean.parseBoolean(value)) throw rejected();
                checkText(value, scope, budget);
            }
        }
    }

    private static void checkText(String text, PublicOutputGuard.Scope scope, Budget budget) throws IOException {
        budget.check(); budget.characters = Math.addExact(budget.characters, text.length());
        if (budget.characters > MAX_TEXT || scope.rejects(text)) throw rejected();
    }

    /** 同一文字流跨 SAX/read 边界保留私有值长度的尾部，但只计新读取字符。 */
    private static final class TextScanner {
        private final PublicOutputGuard.Scope scope;
        private final Budget budget;
        private final int overlap;
        private String tail = "";
        private TextScanner(PublicOutputGuard.Scope scope, Budget budget) {
            this.scope = scope; this.budget = budget; this.overlap = scope.inspectionOverlapCharacters();
        }
        private void accept(String text) throws IOException {
            checkText(text, scope, budget);
            String combined = tail + text;
            if (scope.rejects(combined)) throw rejected();
            tail = combined.substring(Math.max(0, combined.length() - overlap));
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    public static Rejection rejected() { return new Rejection(null); }
    private static Rejection rejected(Throwable cause) { return new Rejection(cause); }
    public static final class Rejection extends IOException {
        private Rejection(Throwable cause) { super(PublicOutputGuard.REJECTION_CODE, cause); }
    }
    private static final class Budget {
        private final Instant deadline;
        private final Runnable control;
        private long expanded;
        private long characters;
        private int entries;
        private Budget(Instant deadline, Runnable control) {
            Instant bounded = Instant.now().plus(MAX_SCAN_DURATION);
            this.deadline = deadline.isBefore(bounded) ? deadline : bounded;
            this.control = control;
        }
        private void check() throws IOException {
            control.run();
            if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(deadline)) throw rejected();
        }
    }
}
