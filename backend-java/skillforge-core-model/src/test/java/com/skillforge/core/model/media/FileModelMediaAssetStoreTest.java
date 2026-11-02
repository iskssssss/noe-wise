package com.skillforge.core.model.media;

import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.AssetKind;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileModelMediaAssetStoreTest {
    @TempDir Path root;
    private static final URI ACCELERATED = URI.create("https://dashscope-0484.oss-accelerate.aliyuncs.com/redacted/image%20a%2Fb.png?Expires=1999999999&OSSAccessKeyId=synthetic&Signature=a%2Fb%2Bz%3D");
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6qtQAAAAASUVORK5CYII=");
    private static final Reply WULANCHABU = new Reply(403, "application/xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <Error><Code>AccessDenied</Code><RequestId>redacted-region-receipt</RequestId><Endpoint>oss-cn-wulanchabu.aliyuncs.com</Endpoint></Error>
            """.getBytes(StandardCharsets.UTF_8), null);
    private static final Reply IMAGE = new Reply(200, "image/png", PNG, null);

    @Test void inlineAudioIsPersistedAndCanBeReopenedWithoutSupplierUrlOrHeapState() throws Exception {
        var http = mock(HttpClient.class);
        when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        var store = new FileModelMediaAssetStore(http, root, 1024);
        byte[] bytes = {73, 68, 51, 1, 2, 3};
        var first = store.storeBytes("101", AssetKind.AUDIO, bytes, "audio/mpeg", Duration.ofSeconds(5), () -> false);
        assertEquals(6, first.sizeBytes());
        assertEquals("audio-101.mp3", first.name());
        assertFalse(first.toString().contains("http"));
        var rebuilt = new FileModelMediaAssetStore(http, root, 1024);
        try (var saved = rebuilt.open("101")) { assertArrayEquals(bytes, saved.readAllBytes()); }
        assertEquals(first, rebuilt.storeBytes("101", AssetKind.AUDIO, bytes, "audio/mpeg", Duration.ofSeconds(5), () -> false));
        assertThrows(IOException.class, () -> rebuilt.storeBytes("101", AssetKind.AUDIO, new byte[]{1}, "audio/mpeg", Duration.ofSeconds(5), () -> false));
        try (var listing = Files.list(root)) { assertEquals(1, listing.count()); }
        verify(http, never()).sendAsync(any(), any());
    }

    @Test void foreignSourcesAndInvalidIdentitiesAreRejectedBeforeNetworkOrFileCreation() {
        var http = mock(HttpClient.class);
        when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        var store = new FileModelMediaAssetStore(http, root, 1024);
        for (String address : new String[]{"https://bucket.oss-cn-hongkong.aliyuncs.com/file",
                "https://bucket.oss-us-west-1.aliyuncs.com/file", "https://example.com/file",
                "http://bucket.oss-cn-beijing.aliyuncs.com:80/file"}) {
            assertThrows(IOException.class, () -> store.importAsset("101", AssetKind.IMAGE, URI.create(address), "image/png", Duration.ofSeconds(5), () -> false));
        }
        assertThrows(IllegalArgumentException.class, () -> store.open("../other"));
        assertThrows(IOException.class, () -> store.storeBytes("102", AssetKind.AUDIO, new byte[]{1}, "text/html", Duration.ofSeconds(5), () -> false));
        assertThrows(IOException.class, () -> store.storeBytes("103", AssetKind.AUDIO, new byte[]{1}, "audio/mpeg", Duration.ofSeconds(5), () -> true));
        verify(http).followRedirects();
        verifyNoMoreInteractions(http);
    }

    @Test void officialHttpSourceUsesHttpsWithoutChangingItsRawSignedPathOrQuery() throws Exception {
        try (var transport = new LoopbackTransport(false)) {
            var store = new FileModelMediaAssetStore(transport.routing, root, 1024);
            var collected = store.importAsset("101", AssetKind.AUDIO,
                    URI.create("http://bucket.oss-cn-beijing.aliyuncs.com/audio%20x.mp3?auth=a%2Fb%2Bz"),
                    "audio/mpeg", Duration.ofSeconds(5), () -> false);
            assertEquals("https://bucket.oss-cn-beijing.aliyuncs.com/audio%20x.mp3?auth=a%2Fb%2Bz",
                    transport.requested.get().uri().toString());
            assertTrue(transport.requested.get().headers().firstValue("Authorization").isEmpty());
            assertEquals(transport.body.length, collected.sizeBytes());
            try (var saved = store.open("101")) { assertArrayEquals(transport.body, saved.readAllBytes()); }
            try (var listing = Files.list(root)) { assertEquals(1, listing.count()); }
            verify(transport.routing).sendAsync(any(), any());
        }
    }

    @Test void stalledBodyAfterHeadersStopsAtTotalDeadlineAndRemovesStagedFile() throws Exception {
        try (var transport = new LoopbackTransport(true)) {
            var store = new FileModelMediaAssetStore(transport.routing, root, 1024);
            var pending = transport.downloads.submit(() -> store.importAsset("101", AssetKind.AUDIO,
                    URI.create("https://bucket.oss-cn-beijing.aliyuncs.com/audio.mp3"),
                    "audio/mpeg", Duration.ofMillis(500), () -> false));
            assertTrue(transport.headersSent.await(2, TimeUnit.SECONDS), "fixture did not send response headers");
            var failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            var downloadFailure = assertInstanceOf(IOException.class, failure.getCause());
            assertEquals("model_media_asset_download_timeout", downloadFailure.getMessage());
            try (var listing = Files.list(root)) { assertEquals(0, listing.count()); }
            verify(transport.routing).sendAsync(any(), any());
        }
    }

    @Test void cancellationAfterHeadersStopsOriginalRequestAndRemovesStagedFile() throws Exception {
        try (var transport = new LoopbackTransport(true)) {
            var store = new FileModelMediaAssetStore(transport.routing, root, 1024);
            var cancelled = new AtomicBoolean();
            var pending = transport.downloads.submit(() -> store.importAsset("101", AssetKind.AUDIO,
                    URI.create("https://bucket.oss-cn-beijing.aliyuncs.com/audio.mp3"),
                    "audio/mpeg", Duration.ofSeconds(10), cancelled::get));
            assertTrue(transport.headersSent.await(2, TimeUnit.SECONDS), "fixture did not send response headers");
            cancelled.set(true);
            var failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            var downloadFailure = assertInstanceOf(IOException.class, failure.getCause());
            assertEquals("model_media_asset_download_cancelled", downloadFailure.getMessage());
            try (var listing = Files.list(root)) { assertEquals(0, listing.count()); }
            verify(transport.routing).sendAsync(any(), any());
        }
    }

    @Test void acceleratedV1ReceiptResolvesThroughBeijingToWulanchabuAndCollectsPng() throws Exception {
        try (var transport = new LoopbackTransport(false, List.of(WULANCHABU, IMAGE))) {
            var store = new FileModelMediaAssetStore(transport.routing, root, 1024);
            var collected = store.importAsset("101", AssetKind.IMAGE, ACCELERATED, "image/png", Duration.ofSeconds(5), () -> false);
            assertEquals("image/png", collected.mediaType()); assertEquals(PNG.length, collected.sizeBytes());
            assertEquals("image-101.png", collected.name());
            try (var saved = store.open("101")) { assertArrayEquals(PNG, saved.readAllBytes()); }
            assertEquals(2, transport.allRequested.size());
            assertEquals("dashscope-0484.oss-cn-beijing.aliyuncs.com", transport.allRequested.getFirst().uri().getHost());
            assertEquals("dashscope-0484.oss-cn-wulanchabu.aliyuncs.com", transport.allRequested.getLast().uri().getHost());
            for (var request : transport.allRequested) {
                assertEquals("https", request.uri().getScheme()); assertEquals("GET", request.method());
                assertEquals(ACCELERATED.getRawPath(), request.uri().getRawPath());
                assertEquals(ACCELERATED.getRawQuery(), request.uri().getRawQuery());
                assertTrue(request.headers().firstValue("Authorization").isEmpty());
            }
            assertTrue(transport.allRequested.getLast().timeout().orElseThrow()
                    .compareTo(transport.allRequested.getFirst().timeout().orElseThrow()) < 0);
            try (var listing = Files.list(root)) { assertEquals(1, listing.count()); }
            verify(transport.routing, times(2)).sendAsync(any(), any());
        }
    }

    @Test void acceleratedV1ReceiptCanCollectFromBeijingWithoutASecondRequest() throws Exception {
        try (var transport = new LoopbackTransport(false, List.of(IMAGE))) {
            var store = new FileModelMediaAssetStore(transport.routing, root, 1024);
            var collected = store.importAsset("101", AssetKind.IMAGE, ACCELERATED, "image/png", Duration.ofSeconds(5), () -> false);
            assertEquals(PNG.length, collected.sizeBytes()); assertEquals(1, transport.allRequested.size());
            assertEquals("dashscope-0484.oss-cn-beijing.aliyuncs.com", transport.requested.get().uri().getHost());
            verify(transport.routing).sendAsync(any(), any());
        }
    }

    @Test void ordinaryWulanchabuSourceKeepsItsSingleRequestPath() throws Exception {
        try (var transport = new LoopbackTransport(false, List.of(IMAGE))) {
            var store = new FileModelMediaAssetStore(transport.routing, root, 1024);
            var source = URI.create("https://dashscope-0484.oss-cn-wulanchabu.aliyuncs.com/image%20a.png?Signature=a%2Fb%3D");
            assertEquals(PNG.length, store.importAsset("101", AssetKind.IMAGE, source, "image/png", Duration.ofSeconds(5), () -> false).sizeBytes());
            assertEquals(source, transport.requested.get().uri()); assertEquals(1, transport.allRequested.size());
            verify(transport.routing).sendAsync(any(), any());
        }
    }

    @Test void acceleratedSourcesRequireTheObservedV1SignatureBeforeAnyNetworkRequest() {
        var http = mock(HttpClient.class);
        when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        var store = new FileModelMediaAssetStore(http, root, 1024);
        for (String address : new String[] {
                "http://dashscope-0484.oss-accelerate.aliyuncs.com/image.png?Expires=1999999999&OSSAccessKeyId=synthetic&Signature=x",
                "https://foreign.oss-accelerate.aliyuncs.com/image.png?Expires=1999999999&OSSAccessKeyId=synthetic&Signature=x",
                "https://nested.dashscope-0484.oss-accelerate.aliyuncs.com/image.png?Expires=1999999999&OSSAccessKeyId=synthetic&Signature=x",
                "https://dashscope-0484.oss-accelerate-overseas.aliyuncs.com/image.png?Expires=1999999999&OSSAccessKeyId=synthetic&Signature=x",
                "https://dashscope-0484.oss-accelerate.aliyuncs.com/image.png?x-oss-signature-version=OSS4-HMAC-SHA256&x-oss-credential=synthetic&x-oss-signature=x",
                "https://dashscope-0484.oss-accelerate.aliyuncs.com/image.png?Expires=1999999999&OSSAccessKeyId=synthetic&Signature=x&x-oss-signature-version=OSS4-HMAC-SHA256",
                "https://dashscope-0484.oss-accelerate.aliyuncs.com/image.png?Expires=1999999999&OSSAccessKeyId=synthetic",
                "https://dashscope-0484.oss-accelerate.aliyuncs.com/image.png?Expires=invalid&OSSAccessKeyId=synthetic&Signature=x",
                "https://dashscope-0484.oss-accelerate.aliyuncs.com/image.png?Expires=1999999999&OSSAccessKeyId=synthetic&Signature=",
                "https://dashscope-0484.oss-accelerate.aliyuncs.com/image.png?Expires=1999999999&OSSAccessKeyId=synthetic&Signature=x&Signature=y"}) {
            assertThrows(IOException.class, () -> store.importAsset("101", AssetKind.IMAGE, URI.create(address), "image/png", Duration.ofSeconds(5), () -> false));
        }
        verify(http).followRedirects(); verifyNoMoreInteractions(http);
        assertFalse(Files.exists(root.resolve("101.body")));
    }

    @Test void unapprovedOrMissingErrorEndpointsNeverCauseAnotherObjectRequest() throws Exception {
        var invalidErrors = List.of(
                "<Error><Endpoint>oss-cn-hongkong.aliyuncs.com</Endpoint></Error>",
                "<Error><Endpoint>oss-us-west-1.aliyuncs.com</Endpoint></Error>",
                "<Error><Endpoint>foreign.example</Endpoint></Error>",
                "<Error><Endpoint>https://oss-cn-wulanchabu.aliyuncs.com</Endpoint></Error>",
                "<Error><Endpoint>oss-cn-wulanchabu.aliyuncs.com.foreign.example</Endpoint></Error>",
                "<Error><Endpoint>oss-cn-wulanchabu.aliyuncs.com:443</Endpoint></Error>",
                "<Error><Code>AccessDenied</Code></Error>",
                "<Error><Details><Endpoint>oss-cn-wulanchabu.aliyuncs.com</Endpoint></Details></Error>",
                "<Error><Endpoint>oss-cn-wulanchabu.aliyuncs.com</Endpoint><Details><Endpoint>oss-cn-beijing.aliyuncs.com</Endpoint></Details></Error>",
                "<Error><Endpoint>oss-cn-wulanchabu.aliyuncs.com</Endpoint><Endpoint>oss-cn-beijing.aliyuncs.com</Endpoint></Error>",
                "<Other><Endpoint>oss-cn-wulanchabu.aliyuncs.com</Endpoint></Other>");
        for (var xml : invalidErrors) {
            var error = new Reply(403, "application/xml", xml.getBytes(StandardCharsets.UTF_8), "https://foreign.example/ignored");
            try (var transport = new LoopbackTransport(false, List.of(error))) {
                var store = new FileModelMediaAssetStore(transport.routing, root, 1024);
                assertThrows(IOException.class, () -> store.importAsset("101", AssetKind.IMAGE, ACCELERATED, "image/png", Duration.ofSeconds(5), () -> false));
                assertEquals(1, transport.allRequested.size()); verify(transport.routing).sendAsync(any(), any());
                try (var listing = Files.list(root)) { assertEquals(0, listing.count()); }
            }
        }
    }

    @Test void endpointResolutionUsesErrorXmlAndStopsAfterAtMostTwoRequests() throws Exception {
        var redirect = new Reply(301, "application/xml", WULANCHABU.body(), "https://foreign.example/ignored");
        try (var transport = new LoopbackTransport(false, List.of(redirect, IMAGE))) {
            var store = new FileModelMediaAssetStore(transport.routing, root, 1024);
            assertEquals(PNG.length, store.importAsset("101", AssetKind.IMAGE, ACCELERATED, "image/png", Duration.ofSeconds(5), () -> false).sizeBytes());
            assertEquals(2, transport.allRequested.size());
            assertEquals("dashscope-0484.oss-cn-wulanchabu.aliyuncs.com", transport.requested.get().uri().getHost());
        }
        try (var transport = new LoopbackTransport(false, List.of(redirect))) {
            var store = new FileModelMediaAssetStore(transport.routing, root, 1024);
            assertThrows(IOException.class, () -> store.importAsset("102", AssetKind.IMAGE, ACCELERATED, "image/png", Duration.ofSeconds(5), () -> false));
            assertEquals(2, transport.allRequested.size()); assertFalse(Files.exists(root.resolve("102.body")));
            try (var listing = Files.list(root)) { assertEquals(1, listing.count()); }
        }
        var missingEndpoint = new Reply(301, "application/xml", "<Error><Code>Redirect</Code></Error>".getBytes(StandardCharsets.UTF_8),
                "https://dashscope-0484.oss-cn-wulanchabu.aliyuncs.com/ignored");
        try (var transport = new LoopbackTransport(false, List.of(missingEndpoint))) {
            var store = new FileModelMediaAssetStore(transport.routing, root, 1024);
            assertThrows(IOException.class, () -> store.importAsset("103", AssetKind.IMAGE, ACCELERATED, "image/png", Duration.ofSeconds(5), () -> false));
            assertEquals(1, transport.allRequested.size()); assertFalse(Files.exists(root.resolve("103.body")));
        }
    }

    @Test void dtdAndOversizedOssErrorsAreRejectedWithoutResolvingAnotherEndpoint() throws Exception {
        Path external = root.resolve("external-region.txt");
        Files.writeString(external, "oss-cn-wulanchabu.aliyuncs.com");
        var errors = List.of(
                "<!DOCTYPE Error [<!ENTITY region SYSTEM \"" + external.toUri() + "\">]><Error><Endpoint>&region;</Endpoint></Error>",
                "<Error><Message>" + "x".repeat(17 * 1024) + "</Message><Endpoint>oss-cn-wulanchabu.aliyuncs.com</Endpoint></Error>");
        for (var xml : errors) {
            var error = new Reply(403, "application/xml", xml.getBytes(StandardCharsets.UTF_8), null);
            try (var transport = new LoopbackTransport(false, List.of(error, IMAGE))) {
                var store = new FileModelMediaAssetStore(transport.routing, root, 64 * 1024);
                assertThrows(IOException.class, () -> store.importAsset("101", AssetKind.IMAGE, ACCELERATED, "image/png", Duration.ofSeconds(5), () -> false));
                assertEquals(1, transport.allRequested.size()); assertFalse(Files.exists(root.resolve("101.body")));
                try (var listing = Files.list(root)) { assertEquals(1, listing.count()); }
            }
        }
    }

    @Test void stalledResolvedDownloadUsesTheOriginalDeadlineAndRemovesBothStageBodies() throws Exception {
        try (var transport = new LoopbackTransport(true, List.of(WULANCHABU, IMAGE))) {
            var store = new FileModelMediaAssetStore(transport.routing, root, 1024);
            var pending = transport.downloads.submit(() -> store.importAsset("101", AssetKind.IMAGE, ACCELERATED,
                    "image/png", Duration.ofMillis(500), () -> false));
            assertTrue(transport.secondHeadersSent.await(2, TimeUnit.SECONDS));
            var failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertEquals("model_media_asset_download_timeout", assertInstanceOf(IOException.class, failure.getCause()).getMessage());
            assertEquals(2, transport.allRequested.size());
            assertTrue(transport.allRequested.getLast().timeout().orElseThrow()
                    .compareTo(transport.allRequested.getFirst().timeout().orElseThrow()) < 0);
            try (var listing = Files.list(root)) { assertEquals(0, listing.count()); }
        }
    }

    @Test void cancellationDuringResolvedDownloadCancelsTheOriginalRequestAndRemovesTheStage() throws Exception {
        try (var transport = new LoopbackTransport(true, List.of(WULANCHABU, IMAGE))) {
            var store = new FileModelMediaAssetStore(transport.routing, root, 1024);
            var cancelled = new AtomicBoolean();
            var pending = transport.downloads.submit(() -> store.importAsset("101", AssetKind.IMAGE, ACCELERATED,
                    "image/png", Duration.ofSeconds(10), cancelled::get));
            assertTrue(transport.secondHeadersSent.await(2, TimeUnit.SECONDS)); cancelled.set(true);
            var failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertEquals("model_media_asset_download_cancelled", assertInstanceOf(IOException.class, failure.getCause()).getMessage());
            assertEquals(2, transport.allRequested.size());
            try (var listing = Files.list(root)) { assertEquals(0, listing.count()); }
        }
    }

    private record Reply(int status, String mediaType, byte[] body, String location) { }

    /** 只在测试中把已通过生产白名单的请求接到真实HTTP服务，正文仍由实际BodyHandler消费。 */
    private static final class LoopbackTransport implements AutoCloseable {
        private final byte[] body = {73, 68, 51, 1, 2, 3};
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final ExecutorService handlers = Executors.newSingleThreadExecutor();
        private final ExecutorService downloads = Executors.newSingleThreadExecutor();
        private final HttpClient actual = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        private final HttpClient routing = mock(HttpClient.class);
        private final AtomicReference<HttpRequest> requested = new AtomicReference<>();
        private final List<HttpRequest> allRequested = new CopyOnWriteArrayList<>();
        private final AtomicInteger responseIndex = new AtomicInteger();
        private final CountDownLatch headersSent = new CountDownLatch(1);
        private final CountDownLatch secondHeadersSent = new CountDownLatch(1);
        private final CountDownLatch releaseBody = new CountDownLatch(1);

        private LoopbackTransport(boolean stallBody) throws IOException {
            this(stallBody, List.of());
        }

        private LoopbackTransport(boolean stallBody, List<Reply> replies) throws IOException {
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                try (exchange) {
                    int index = responseIndex.getAndIncrement();
                    var reply = replies.isEmpty() ? new Reply(200, "audio/mpeg", body, null)
                            : replies.get(Math.min(index, replies.size() - 1));
                    exchange.getResponseHeaders().add("Content-Type", reply.mediaType());
                    if (reply.location() != null) exchange.getResponseHeaders().add("Location", reply.location());
                    exchange.sendResponseHeaders(reply.status(), reply.body().length);
                    headersSent.countDown();
                    if (index == 1) secondHeadersSent.countDown();
                    if (stallBody && (replies.isEmpty() || index == replies.size() - 1)) {
                        try { releaseBody.await(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                    exchange.getResponseBody().write(reply.body());
                }
            });
            server.start();
            when(routing.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
            when(routing.<Path>sendAsync(any(HttpRequest.class), any())).thenAnswer(invocation -> {
                HttpRequest original = invocation.getArgument(0);
                HttpResponse.BodyHandler<Path> handler = invocation.getArgument(1);
                requested.set(original);
                allRequested.add(original);
                var source = original.uri();
                String query = source.getRawQuery() == null ? "" : "?" + source.getRawQuery();
                var loopback = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + source.getRawPath() + query);
                var routed = HttpRequest.newBuilder(loopback).timeout(original.timeout().orElseThrow()).GET().build();
                return actual.sendAsync(routed, handler);
            });
        }

        @Override public void close() throws Exception {
            releaseBody.countDown();
            server.stop(0);
            downloads.shutdownNow();
            handlers.shutdownNow();
            assertTrue(downloads.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(handlers.awaitTermination(2, TimeUnit.SECONDS));
            actual.close();
        }
    }
}
