package com.skillforge.core.model.media;

import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.AssetKind;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_OK;

/** 已授权媒体结果只从境内OSS收集；签名URL不返回浏览器，不转发模型凭据。 */
public final class FileModelMediaAssetStore implements ModelMediaAssetStore {
    private static final String BEIJING_OSS_ENDPOINT = "oss-cn-beijing.aliyuncs.com";
    private static final Set<String> DOMESTIC_OSS_ENDPOINTS = Set.of(BEIJING_OSS_ENDPOINT,
            "oss-cn-hangzhou.aliyuncs.com", "oss-cn-shanghai.aliyuncs.com",
            "oss-cn-shenzhen.aliyuncs.com", "oss-cn-wulanchabu.aliyuncs.com");
    private static final int MAXIMUM_ACCELERATED_OBJECT_REQUESTS = 2;
    private static final int MAXIMUM_OSS_ERROR_BYTES = 16 * 1024;
    private static final String OSS_ERROR_ROOT = "Error";
    private static final String OSS_ERROR_ENDPOINT = "Endpoint";
    private static final String OSS_V1_EXPIRES = "Expires";
    private static final String OSS_V1_ACCESS_KEY = "OSSAccessKeyId";
    private static final String OSS_V1_SIGNATURE = "Signature";
    private final HttpClient http;
    private final Path root;
    private final long maximumBytes;

    public FileModelMediaAssetStore(HttpClient http, Path root, long maximumBytes) {
        if (http.followRedirects() != HttpClient.Redirect.NEVER || maximumBytes <= 0)
            throw new IllegalArgumentException("model_media_store_configuration_invalid");
        this.http = http; this.root = root.toAbsolutePath().normalize(); this.maximumBytes = maximumBytes;
    }

    @Override public Stored importAsset(String id, AssetKind kind, URI source, String reportedMediaType,
            Duration timeout, BooleanSupplier cancelled) throws IOException {
        Path target = path(id);
        boolean accelerated = acceleratedSource(source);
        URI downloadSource = domesticSource(source);
        Files.createDirectories(root);
        if (Files.exists(target)) return inspect(target, id, kind, reportedMediaType);
        if (cancelled.getAsBoolean() || timeout.isNegative() || timeout.isZero()) throw new IOException("model_media_asset_download_cancelled");
        long started = System.nanoTime();
        long budgetNanos = timeout.toNanos();
        Path staged = Files.createTempFile(root, "collect-", ".part");
        try {
            int maximumRequests = accelerated ? MAXIMUM_ACCELERATED_OBJECT_REQUESTS : 1;
            for (int attempt = 0; attempt < maximumRequests; attempt++) {
                if (cancelled.getAsBoolean()) throw new IOException("model_media_asset_download_cancelled");
                long remaining = budgetNanos - (System.nanoTime() - started);
                if (remaining <= 0) throw new IOException("model_media_asset_download_timeout");
                var request = HttpRequest.newBuilder(downloadSource).timeout(Duration.ofNanos(remaining)).GET().build();
                HttpResponse<Path> response;
                try (var subscriber = new FileSubscriber(staged, maximumBytes, cancelled)) {
                    var pending = http.sendAsync(request, info -> {
                        if (info.statusCode() != HTTP_OK) subscriber.maximum = Math.min(maximumBytes, MAXIMUM_OSS_ERROR_BYTES);
                        return subscriber;
                    });
                    try { response = awaitBody(pending, started, budgetNanos, cancelled); }
                    finally { if (!pending.isDone()) pending.cancel(true); }
                }
                if (response.statusCode() == HTTP_OK) {
                    String type = response.headers().firstValue("Content-Type").orElse(reportedMediaType);
                    String mediaType = mediaType(kind, type);
                    try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE)) { channel.force(true); }
                    if (cancelled.getAsBoolean() || System.nanoTime() - started >= budgetNanos)
                        throw new IOException("model_media_asset_download_cancelled");
                    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
                    return inspect(target, id, kind, mediaType);
                }
                if (!accelerated || attempt + 1 == maximumRequests
                        || response.statusCode() != HTTP_FORBIDDEN && response.statusCode() != HTTP_MOVED_PERM)
                    throw new IOException("model_media_asset_download_failed");
                // 只接受有界OSS错误正文指明的国内地域，不跟随Location，也不访问加速端点。
                var error = readOssError(staged);
                if (!DOMESTIC_OSS_ENDPOINTS.contains(error.endpoint()))
                    throw new IOException("model_media_asset_domestic_source_required");
                downloadSource = bucketEndpoint(source, error.endpoint());
            }
            throw new IOException("model_media_asset_download_failed");
        } finally { Files.deleteIfExists(staged); }
    }

    /** 以宿主总期限等待完整正文；定期观察取消仅检查同一future，不重新发送网络请求。 */
    private static <T> T awaitBody(CompletableFuture<T> pending, long started, long budget,
            BooleanSupplier cancelled) throws IOException {
        while (true) {
            if (cancelled.getAsBoolean()) throw new IOException("model_media_asset_download_cancelled");
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) throw new IOException("model_media_asset_download_timeout");
            try { return pending.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); }
            catch (TimeoutException waiting) { /* 仍等待原请求，同时复核总期限与取消。 */ }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IOException("model_media_asset_download_interrupted", interrupted);
            } catch (ExecutionException failure) {
                throw new IOException("model_media_asset_download_failed", failure.getCause());
            }
        }
    }

    /** 正文直接写入本次临时文件，不在内存累积视频；取消时关闭原订阅和文件。 */
    private static final class FileSubscriber implements HttpResponse.BodySubscriber<Path>, AutoCloseable {
        private final CompletableFuture<Path> completion = new CompletableFuture<>();
        private final Path file;
        private final FileChannel output;
        private long maximum;
        private final BooleanSupplier cancelled;
        private volatile Flow.Subscription subscription;
        private long bytes;
        private FileSubscriber(Path file, long maximum, BooleanSupplier cancelled) throws IOException {
            this.file = file; this.maximum = maximum; this.cancelled = cancelled;
            output = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        @Override public CompletionStage<Path> getBody() { return completion; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (completion.isDone()) value.cancel(); else value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            try {
                if (cancelled.getAsBoolean()) throw new IOException("model_media_asset_download_cancelled");
                for (var buffer : buffers) {
                    bytes = Math.addExact(bytes, buffer.remaining());
                    if (bytes > maximum) throw new IOException("model_media_asset_size_exceeded");
                    while (buffer.hasRemaining()) output.write(buffer);
                }
                subscription.request(1);
            } catch (IOException failure) { fail(failure); }
        }
        @Override public void onError(Throwable failure) { fail(failure); }
        @Override public void onComplete() {
            if (bytes == 0) { fail(new IOException("model_media_asset_empty")); return; }
            try { output.close(); completion.complete(file); }
            catch (IOException failure) { fail(failure); }
        }
        private void fail(Throwable failure) {
            if (subscription != null) subscription.cancel();
            try { output.close(); }
            catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            completion.completeExceptionally(failure);
        }
        @Override public void close() throws IOException {
            boolean incomplete = completion.completeExceptionally(new IOException("model_media_asset_download_cancelled"));
            if (subscription != null && incomplete) subscription.cancel();
            output.close();
        }
    }

    @Override public InputStream open(String id) throws IOException { return Files.newInputStream(path(id)); }

    @Override public Stored storeBytes(String id, AssetKind kind, byte[] bytes, String type,
            Duration timeout, BooleanSupplier cancelled) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > maximumBytes)
            throw new IOException("model_media_asset_size_exceeded");
        if (cancelled.getAsBoolean() || timeout.isNegative() || timeout.isZero())
            throw new IOException("model_media_asset_download_cancelled");
        String mime = mediaType(kind, type);
        Files.createDirectories(root);
        Path target = path(id);
        if (Files.exists(target)) {
            var previous = inspect(target, id, kind, mime);
            if (!previous.sha256().equals(HexFormat.of().formatHex(sha256().digest(bytes))))
                throw new IOException("model_media_asset_identity_conflict");
            return previous;
        }
        Path staged = Files.createTempFile(root, "collect-", ".part");
        try {
            Files.write(staged, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            return inspect(target, id, kind, mime);
        } finally { Files.deleteIfExists(staged); }
    }

    private Stored inspect(Path file, String id, AssetKind kind, String type) throws IOException {
        var digest = sha256();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024]; int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        String mime = mediaType(kind, type);
        String extension = switch (kind) {
            case IMAGE -> switch (mime) { case "image/jpeg" -> ".jpg"; case "image/webp" -> ".webp"; case "image/gif" -> ".gif"; default -> ".png"; };
            case VIDEO -> ".mp4";
            case AUDIO -> mime.equals("audio/mpeg") ? ".mp3" : ".wav";
            case TRANSCRIPT -> ".json";
        };
        return new Stored(mime, Files.size(file), HexFormat.of().formatHex(digest.digest()),
                kind.name().toLowerCase(Locale.ROOT) + "-" + id + extension);
    }

    private Path path(String id) {
        if (id == null || !id.matches("[1-9][0-9]*")) throw new IllegalArgumentException("model_media_asset_id_invalid");
        return root.resolve(id + ".body");
    }

    private static URI domesticSource(URI source) throws IOException {
        String host = source.getHost() == null ? "" : source.getHost().toLowerCase(Locale.ROOT);
        if (!("https".equalsIgnoreCase(source.getScheme()) || "http".equalsIgnoreCase(source.getScheme()))
                || source.getUserInfo() != null || source.getFragment() != null
                || source.getPort() != -1 && source.getPort() != 443)
            throw new IOException("model_media_asset_domestic_source_required");
        if (acceleratedSource(source)) {
            // 当前百炼回执的三字段V1签名不绑定Host；V4及其他签名形式不能盲改地域。
            String expires = null, accessKey = null, signature = null;
            String query = source.getRawQuery();
            if (query == null) throw new IOException("model_media_asset_domestic_source_required");
            for (String parameter : query.split("&", -1)) {
                String[] field = parameter.split("=", 2);
                if (field.length != 2 || field[1].isEmpty()) throw new IOException("model_media_asset_domestic_source_required");
                switch (field[0]) {
                    case OSS_V1_EXPIRES -> {
                        if (expires != null) throw new IOException("model_media_asset_domestic_source_required");
                        expires = field[1];
                    }
                    case OSS_V1_ACCESS_KEY -> {
                        if (accessKey != null) throw new IOException("model_media_asset_domestic_source_required");
                        accessKey = field[1];
                    }
                    case OSS_V1_SIGNATURE -> {
                        if (signature != null) throw new IOException("model_media_asset_domestic_source_required");
                        signature = field[1];
                    }
                    default -> throw new IOException("model_media_asset_domestic_source_required");
                }
            }
            if (expires == null || !expires.matches("[0-9]+") || accessKey == null || signature == null)
                throw new IOException("model_media_asset_domestic_source_required");
            return bucketEndpoint(source, BEIJING_OSS_ENDPOINT);
        }
        int bucketEnd = host.indexOf('.');
        if (bucketEnd <= 0 || !host.substring(0, bucketEnd).matches("[a-z0-9][a-z0-9-]*[a-z0-9]")
                || !DOMESTIC_OSS_ENDPOINTS.contains(host.substring(bucketEnd + 1)))
            throw new IOException("model_media_asset_domestic_source_required");
        // 官方Qwen音频回执可能给出http OSS地址；保留签名原path/query，仅升级协议后传输。
        return "http".equalsIgnoreCase(source.getScheme())
                ? URI.create("https:" + source.getRawSchemeSpecificPart()) : source;
    }

    private static boolean acceleratedSource(URI source) {
        return "https".equalsIgnoreCase(source.getScheme()) && source.getHost() != null
                && source.getHost().toLowerCase(Locale.ROOT)
                    .matches("dashscope-[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.oss-accelerate\\.aliyuncs\\.com");
    }

    private static URI bucketEndpoint(URI source, String endpoint) {
        String bucket = source.getHost().substring(0, source.getHost().indexOf('.'));
        return URI.create("https://" + bucket + "." + endpoint + source.getRawPath()
                + (source.getRawQuery() == null ? "" : "?" + source.getRawQuery()));
    }

    private record OssError(String endpoint) { }

    /** Error.Endpoint是唯一地域证据；禁用DTD、外部实体，不接受嵌套或重复Endpoint。 */
    private static OssError readOssError(Path file) throws IOException {
        if (Files.size(file) > MAXIMUM_OSS_ERROR_BYTES) throw new IOException("model_media_asset_domestic_source_required");
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        String endpoint = null;
        try (var input = Files.newInputStream(file)) {
            var reader = factory.createXMLStreamReader(input);
            try {
                int depth = 0;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
                        throw new IOException("model_media_asset_domestic_source_required");
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        if (depth == 1 && (!OSS_ERROR_ROOT.equals(reader.getLocalName()) || reader.getNamespaceURI() != null))
                            throw new IOException("model_media_asset_domestic_source_required");
                        if (OSS_ERROR_ENDPOINT.equals(reader.getLocalName())) {
                            if (depth != 2 || endpoint != null || reader.getNamespaceURI() != null)
                                throw new IOException("model_media_asset_domestic_source_required");
                            endpoint = reader.getElementText().strip();
                            depth--;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) depth--;
                }
            } finally { reader.close(); }
        } catch (XMLStreamException malformed) {
            throw new IOException("model_media_asset_domestic_source_required", malformed);
        }
        if (endpoint == null) throw new IOException("model_media_asset_domestic_source_required");
        return new OssError(endpoint);
    }

    private static String mediaType(AssetKind kind, String value) throws IOException {
        String type = value == null ? "" : value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        boolean allowed = switch (kind) {
            case IMAGE -> java.util.Set.of("image/png", "image/jpeg", "image/webp", "image/gif").contains(type);
            case VIDEO -> type.equals("video/mp4");
            case AUDIO -> java.util.Set.of("audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3").contains(type);
            case TRANSCRIPT -> type.equals("application/json") || type.equals("text/plain");
        };
        if (!allowed) throw new IOException("model_media_asset_type_invalid");
        return type;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
