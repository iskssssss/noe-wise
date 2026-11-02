package com.skillforge.runtime.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@Timeout(30)
class DesktopToolsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir
    Path temporary;

    @Test
    void readsEditsAndReportsActualChange() throws Exception {
        Path file = temporary.resolve("中文.txt");
        Files.writeString(file, "first\nhello 世界\n", StandardCharsets.UTF_8);
        try (DesktopTools tools = new DesktopTools(temporary)) {
            assertEquals("first\nhello 世界\n", data(call(tools, "read_file", "path", "中文.txt")).path("content").asText());
            DesktopTools.Result edit = call(tools, "edit_file", "path", "中文.txt", "oldText", "hello", "newText", "你好");
            assertEquals(DesktopTools.Status.SUCCEEDED, edit.status());
            assertEquals("first\n你好 世界\n", Files.readString(file));
            assertTrue(data(edit).path("diff").asText().contains("+++ b/中文.txt"));
            assertEquals("first\nhello 世界\n", data(edit).path("before").asText());
            assertEquals(DesktopTools.Status.SUCCEEDED, call(tools, "write_file", "path", "new.txt", "content", "new").status());
            assertEquals("new", Files.readString(temporary.resolve("new.txt")));
        }
    }

    @Test
    void rejectsUnobservedAndStaleOverwritesAcrossInstances() throws Exception {
        Files.writeString(temporary.resolve("code.txt"), "original");
        try (DesktopTools first = new DesktopTools(temporary); DesktopTools second = new DesktopTools(temporary)) {
            assertCode("READ_REQUIRED", call(first, "write_file", "path", "code.txt", "content", "overwrite"));
            call(first, "read_file", "path", "code.txt");
            call(second, "read_file", "path", "code.txt");
            assertEquals(DesktopTools.Status.SUCCEEDED, call(first, "write_file", "path", "code.txt", "content", "first").status());
            assertCode("FILE_VERSION_CONFLICT", call(second, "write_file", "path", "code.txt", "content", "second"));
            assertEquals("first", Files.readString(temporary.resolve("code.txt")));
        }
    }

    @Test
    void overlappingWritesHaveOneWinner() throws Exception {
        Files.writeString(temporary.resolve("shared.txt"), "initial");
        try (DesktopTools first = new DesktopTools(temporary); DesktopTools second = new DesktopTools(temporary);
             ExecutorService pool = Executors.newFixedThreadPool(2)) {
            call(first, "read_file", "path", "shared.txt");
            call(second, "read_file", "path", "shared.txt");
            CyclicBarrier start = new CyclicBarrier(2);
            var a = pool.submit(() -> { start.await(); return call(first, "write_file", "path", "shared.txt", "content", "a"); });
            var b = pool.submit(() -> { start.await(); return call(second, "write_file", "path", "shared.txt", "content", "b"); });
            List<DesktopTools.Result> results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(result -> result.status() == DesktopTools.Status.SUCCEEDED).count());
            assertEquals(1, results.stream().filter(result -> data(result).path("error").path("code").asText().equals("FILE_VERSION_CONFLICT")).count());
        }
    }

    @Test
    void rejectsTraversalAndDoesNotModifyOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = temporary.resolve("outside.txt");
        Files.writeString(outside, "private");
        try (DesktopTools tools = new DesktopTools(workspace)) {
            assertCode("PATH_OUTSIDE_WORKSPACE", call(tools, "read_file", "path", "../outside.txt"));
            assertCode("PATH_OUTSIDE_WORKSPACE", call(tools, "write_file", "path", "../outside.txt", "content", "changed"));
            assertEquals("private", Files.readString(outside));
        }
    }

    @Test
    void rejectsSymlinkEscapeWhenPlatformPermitsLinks() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("private.txt"), "private");
        try {
            Files.createSymbolicLink(workspace.resolve("escape"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "Platform did not permit creating a symbolic link: " + unavailable.getClass().getSimpleName());
        }
        try (DesktopTools tools = new DesktopTools(workspace)) {
            assertCode("PATH_OUTSIDE_WORKSPACE", call(tools, "read_file", "path", "escape/private.txt"));
            assertCode("PATH_OUTSIDE_WORKSPACE", call(tools, "write_file", "path", "escape/new.txt", "content", "outside"));
            assertFalse(Files.exists(outside.resolve("new.txt")));
        }
    }

    @Test
    void listsLargeDirectoriesWithStableBoundedPagesAndFilenameFilter() throws Exception {
        for (int i = 449; i >= 0; i--) Files.createFile(temporary.resolve("item-%03d.txt".formatted(i)));
        Files.createFile(temporary.resolve("other.txt"));
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var declaration = tools.definitions().stream().filter(item -> item.name().equals("list_files")).findFirst().orElseThrow();
            assertTrue(declaration.description().contains("nextAfter"));
            var schema = JSON.readTree(declaration.inputSchemaJson());
            assertTrue(schema.path("properties").has("after"));
            assertTrue(schema.path("properties").has("nameContains"));
            var names = new java.util.ArrayList<String>();
            String after = null;
            for (int page = 0; page < 3; page++) {
                var args = JSON.createObjectNode().put("path", ".").put("nameContains", "item-");
                if (after != null) args.put("after", after);
                var result = tools.execute("list_files", args.toString(), "page-" + page, false, () -> false);
                assertEquals(DesktopTools.Status.SUCCEEDED, result.status(), result.contentJson());
                var value = data(result);
                value.path("entries").forEach(entry -> names.add(entry.path("name").asText()));
                assertEquals(page < 2, value.path("truncated").asBoolean());
                after = value.path("nextAfter").asText(null);
                if (page < 2) assertEquals("item-%03d.txt".formatted((page + 1) * 200 - 1), after);
                else assertEquals(null, after);
            }
            assertEquals(450, names.size());
            for (int i = 0; i < names.size(); i++) assertEquals("item-%03d.txt".formatted(i), names.get(i));
            var unfiltered = data(call(tools, "list_files", "path", "."));
            assertEquals(200, unfiltered.path("entries").size());
            assertTrue(unfiltered.path("truncated").asBoolean());
            for (String invalid : List.of("{\"after\":null}", "{\"nameContains\":\"\"}")) {
                var args = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(invalid); args.put("path", ".");
                assertCode("INVALID_ARGUMENT", tools.execute("list_files", args.toString(), "invalid", false, () -> false));
            }
            assertCode("PATH_OUTSIDE_WORKSPACE", call(tools, "list_files", "path", ".."));
            var whitespace = JSON.createObjectNode().put("path", ".").put("after", " ").put("nameContains", " ");
            assertEquals(DesktopTools.Status.SUCCEEDED, tools.execute("list_files", whitespace.toString(), "whitespace", false, () -> false).status());
            for (String argument : List.of("after", "nameContains")) {
                var unicode = JSON.createObjectNode().put("path", ".").put(argument, "🙂".repeat(4096));
                assertEquals(DesktopTools.Status.SUCCEEDED, tools.execute("list_files", unicode.toString(), "unicode", false, () -> false).status());
                unicode.put(argument, "🙂".repeat(4097));
                assertCode("INVALID_ARGUMENT", tools.execute("list_files", unicode.toString(), "unicode-over", false, () -> false));
            }
            Files.createFile(temporary.resolve("item-000a.txt"));
            Files.createFile(temporary.resolve("item-199a.txt"));
            var changed = JSON.createObjectNode().put("path", ".").put("after", "item-199.txt").put("nameContains", "item-");
            var changedPage = data(tools.execute("list_files", changed.toString(), "changed", false, () -> false));
            assertEquals("item-199a.txt", changedPage.path("entries").get(0).path("name").asText());
            assertFalse(changedPage.path("entries").toString().contains("item-000a.txt"));
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            assertCode("CANCELLED_BEFORE_WRITE", tools.execute("list_files", "{\"path\":\".\"}", "cancel", false, () -> checks.incrementAndGet() > 4));
        }
    }

    @Test
    void lightweightSearchPaginatesWithoutLineContentsAndBindsOutputMode() throws Exception {
        Files.writeString(temporary.resolve("many.txt"), ("needle " + "x".repeat(600) + "\n").repeat(300));
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var args = JSON.createObjectNode().put("path", "many.txt").put("text", "needle").put("output", "matches");
            var first = data(tools.execute("search_files", args.toString(), "first", false, () -> false));
            assertEquals(250, first.path("pageMatchCount").asInt());
            assertEquals(250, first.path("matches").size()); assertTrue(first.path("hasMore").asBoolean());
            first.path("matches").forEach(match -> { assertEquals(2, match.size()); assertTrue(match.has("path")); assertTrue(match.has("line")); });
            args.put("cursor", first.path("nextCursor").asText());
            var second = data(tools.execute("search_files", args.toString(), "second", false, () -> false));
            assertEquals(50, second.path("pageMatchCount").asInt()); assertEquals(251, second.path("matches").get(0).path("line").asInt());
            assertFalse(second.path("hasMore").asBoolean());
        }
    }

    @Test
    void lightweightSearchRejectsModeChangesAndKeepsDefaultOutput() throws Exception {
        Files.writeString(temporary.resolve("many.txt"), "needle\n".repeat(300));
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var args = JSON.createObjectNode().put("path", "many.txt").put("text", "needle").put("output", "matches");
            var first = data(tools.execute("search_files", args.toString(), "first", false, () -> false));
            args.put("cursor", first.path("nextCursor").asText()); args.remove("output");
            assertCode("INVALID_ARGUMENT", tools.execute("search_files", args.toString(), "wrong-mode", false, () -> false));
            args.put("output", "matches");
            assertCode("SEARCH_CURSOR_EXPIRED", tools.execute("search_files", args.toString(), "consumed", false, () -> false));
            var normal = data(call(tools, "search_files", "path", "many.txt", "text", "needle"));
            assertEquals("needle", normal.path("matches").get(0).path("text").asText());
            assertFalse(normal.has("pageMatchCount"));
            var otherMode = JSON.createObjectNode().put("path", "many.txt").put("text", "needle")
                    .put("cursor", normal.path("nextCursor").asText()).put("output", "matches");
            assertCode("INVALID_ARGUMENT", tools.execute("search_files", otherMode.toString(), "other-mode", false, () -> false));
            var wrong = JSON.createObjectNode().put("path", "many.txt").put("text", "needle").put("output", "count");
            assertCode("INVALID_ARGUMENT", tools.execute("search_files", wrong.toString(), "invalid", false, () -> false));
            var empty = JSON.createObjectNode().put("path", "many.txt").put("text", "missing").put("output", "matches");
            assertEquals(0, data(tools.execute("search_files", empty.toString(), "empty", false, () -> false)).path("pageMatchCount").asInt(-1));
        }
    }

    @Test
    void lightweightSearchCountsLinesAndReportsIncompleteCoverage() throws Exception {
        Files.writeString(temporary.resolve("matches.txt"), "needle needle\nneedle\n");
        Files.writeString(temporary.resolve("too-big.txt"), "x".repeat(256 * 1024 + 1));
        Path deep = temporary;
        for (int i = 0; i < 32; i++) deep = Files.createDirectory(deep.resolve("d"));
        Files.writeString(deep.resolve("hidden.txt"), "needle");
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var args = JSON.createObjectNode().put("path", ".").put("text", "needle").put("output", "matches");
            var page = data(tools.execute("search_files", args.toString(), "coverage", false, () -> false));
            assertEquals(2, page.path("pageMatchCount").asInt()); assertEquals(2, page.path("matches").size());
            assertEquals(1, page.path("skippedFiles").asInt()); assertEquals(1, page.path("depthLimitedDirectories").asInt());
            assertFalse(page.path("hasMore").asBoolean()); assertTrue(page.path("truncated").asBoolean());
            var implicit = data(call(tools, "search_files", "path", "matches.txt", "text", "needle"));
            var explicit = data(call(tools, "search_files", "path", "matches.txt", "text", "needle", "output", "content"));
            assertEquals(implicit, explicit);
        }
    }

    @Test
    void invalidSearchOutputDoesNotConsumeCursor() throws Exception {
        Files.writeString(temporary.resolve("many.txt"), "needle\n".repeat(300));
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var args = JSON.createObjectNode().put("path", "many.txt").put("text", "needle").put("output", "matches");
            var page = data(tools.execute("search_files", args.toString(), "first", false, () -> false));
            args.put("cursor", page.path("nextCursor").asText()).put("output", "invalid");
            assertCode("INVALID_ARGUMENT", tools.execute("search_files", args.toString(), "invalid", false, () -> false));
            args.put("output", "matches");
            assertEquals(50, data(tools.execute("search_files", args.toString(), "second", false, () -> false)).path("pageMatchCount").asInt());
        }
    }

    @Test
    void lightweightCursorCannotSurviveToolsRestart() throws Exception {
        Files.writeString(temporary.resolve("many.txt"), "needle\n".repeat(300));
        String cursor;
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var args = JSON.createObjectNode().put("path", "many.txt").put("text", "needle").put("output", "matches");
            cursor = data(tools.execute("search_files", args.toString(), "first", false, () -> false)).path("nextCursor").asText();
        }
        try (DesktopTools reopened = new DesktopTools(temporary)) {
            var args = JSON.createObjectNode().put("path", "many.txt").put("text", "needle").put("output", "matches").put("cursor", cursor);
            assertCode("SEARCH_CURSOR_EXPIRED", reopened.execute("search_files", args.toString(), "restart", false, () -> false));
        }
    }

    @Test
    void searchContinuesSingleFileAfterFirst250Matches() throws Exception {
        Files.writeString(temporary.resolve("many.txt"), "needle\n".repeat(300));
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var first = data(call(tools, "search_files", "path", "many.txt", "text", "needle"));
            assertEquals(250, first.path("matches").size()); assertTrue(first.path("hasMore").asBoolean());
            String cursor = first.path("nextCursor").asText(); assertFalse(cursor.isBlank());
            var args = JSON.createObjectNode().put("path", "many.txt").put("text", "needle").put("cursor", cursor);
            var second = data(tools.execute("search_files", args.toString(), "search-next", false, () -> false));
            assertEquals(50, second.path("matches").size()); assertEquals(251, second.path("matches").get(0).path("line").asInt());
            assertFalse(second.path("hasMore").asBoolean());
            assertCode("SEARCH_CURSOR_EXPIRED", tools.execute("search_files", args.toString(), "reused", false, () -> false));
        }
    }

    @Test
    void searchContinuesDirectoryAfterVisitLimitWithoutMatches() throws Exception {
        for (int i = 0; i < 5001; i++) Files.createFile(temporary.resolve("f-%05d".formatted(i)));
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var first = data(call(tools, "search_files", "path", ".", "text", "needle"));
            assertTrue(first.path("hasMore").asBoolean()); assertEquals(5000, first.path("visitedEntries").asInt());
            var args = JSON.createObjectNode().put("path", ".").put("text", "needle").put("cursor", first.path("nextCursor").asText());
            var second = data(tools.execute("search_files", args.toString(), "search-next", false, () -> false));
            assertFalse(second.path("hasMore").asBoolean()); assertTrue(second.path("visitedEntries").asInt() > 0);
        }
    }

    @Test
    void searchContinuesAcrossFilesAndSubdirectories() throws Exception {
        Path nested = Files.createDirectory(temporary.resolve("nested"));
        for (int i = 0; i < 300; i++) Files.writeString((i % 2 == 0 ? temporary : nested).resolve("f-%03d".formatted(i)), "needle");
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var paths = new java.util.HashSet<String>();
            var page = data(call(tools, "search_files", "path", ".", "text", "needle"));
            int pages = 0;
            while (true) {
                page.path("matches").forEach(match -> assertTrue(paths.add(match.path("path").asText())));
                pages++;
                if (!page.path("hasMore").asBoolean()) break;
                var args = JSON.createObjectNode().put("path", ".").put("text", "needle").put("cursor", page.path("nextCursor").asText());
                page = data(tools.execute("search_files", args.toString(), "page-" + pages, false, () -> false));
                assertTrue(pages < 10);
            }
            assertEquals(300, paths.size()); assertTrue(paths.contains("nested/f-299")); assertTrue(pages > 1);
        }
    }

    @Test
    void searchCursorReportsBoundaryAndRejectsWrongOrExpiredContinuations() throws Exception {
        Files.writeString(temporary.resolve("exact.txt"), "hit\n".repeat(250));
        Files.writeString(temporary.resolve("over.txt"), "hit\n".repeat(251));
        String cursor;
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var exact = data(call(tools, "search_files", "path", "exact.txt", "text", "hit"));
            assertEquals(250, exact.path("matches").size()); assertFalse(exact.path("hasMore").asBoolean());
            var first = data(call(tools, "search_files", "path", "over.txt", "text", "hit"));
            assertEquals(250, first.path("matches").size()); assertTrue(first.path("hasMore").asBoolean());
            cursor = first.path("nextCursor").asText();
            var wrong = JSON.createObjectNode().put("path", "over.txt").put("text", "other").put("cursor", cursor);
            assertCode("INVALID_ARGUMENT", tools.execute("search_files", wrong.toString(), "wrong", false, () -> false));
            var continuation = JSON.createObjectNode().put("path", "over.txt").put("text", "hit").put("cursor", cursor);
            assertCode("SEARCH_CURSOR_EXPIRED", tools.execute("search_files", continuation.toString(), "used", false, () -> false));
            cursor = data(call(tools, "search_files", "path", "over.txt", "text", "hit")).path("nextCursor").asText();
            assertCode("PATH_OUTSIDE_WORKSPACE", call(tools, "search_files", "path", "..", "text", "hit"));
        }
        try (DesktopTools fresh = new DesktopTools(temporary)) {
            var continuation = JSON.createObjectNode().put("path", "over.txt").put("text", "hit").put("cursor", cursor);
            assertCode("SEARCH_CURSOR_EXPIRED", fresh.execute("search_files", continuation.toString(), "restart", false, () -> false));
        }
    }

    @Test
    void searchReportsSkippedAndDepthLimitsAndBoundsLargePage() throws Exception {
        Files.writeString(temporary.resolve("too-big.txt"), "x".repeat(256 * 1024 + 1));
        Files.write(temporary.resolve("binary.txt"), new byte[] {(byte) 0xff});
        Files.writeString(temporary.resolve("long.txt"), ("hit" + "x".repeat(1990) + "\n").repeat(100));
        Path deep = temporary;
        for (int i = 0; i < 32; i++) deep = Files.createDirectory(deep.resolve("d"));
        Files.writeString(deep.resolve("hidden.txt"), "hit");
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var page = data(call(tools, "search_files", "path", ".", "text", "hit"));
            int skipped = 0, depth = 0, matches = 0, pages = 0;
            while (true) {
                skipped += page.path("skippedFiles").asInt(); depth += page.path("depthLimitedDirectories").asInt();
                matches += page.path("matches").size(); pages++;
                if (!page.path("hasMore").asBoolean()) break;
                var args = JSON.createObjectNode().put("path", ".").put("text", "hit").put("cursor", page.path("nextCursor").asText());
                page = data(tools.execute("search_files", args.toString(), "page-" + pages, false, () -> false));
                assertTrue(pages < 50);
            }
            assertEquals(2, skipped); assertEquals(1, depth); assertEquals(100, matches); assertTrue(pages > 1);
            assertEquals(2, page.path("totalSkippedFiles").asInt()); assertEquals(1, page.path("totalDepthLimitedDirectories").asInt());
            assertTrue(page.path("truncated").asBoolean());
        }
    }

    @Test
    void cancelledSearchClosesItsCursor() throws Exception {
        Files.writeString(temporary.resolve("many.txt"), "hit\n".repeat(300));
        try (DesktopTools tools = new DesktopTools(temporary)) {
            String cursor = data(call(tools, "search_files", "path", "many.txt", "text", "hit")).path("nextCursor").asText();
            var args = JSON.createObjectNode().put("path", "many.txt").put("text", "hit").put("cursor", cursor);
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            assertCode("CANCELLED_BEFORE_WRITE", tools.execute("search_files", args.toString(), "cancel", false, () -> checks.incrementAndGet() > 3));
            assertCode("SEARCH_CURSOR_EXPIRED", tools.execute("search_files", args.toString(), "again", false, () -> false));
        }
    }

    @Test
    void waitingSearchCanCancelWithoutWaitingForOtherSearch() throws Exception {
        Files.writeString(temporary.resolve("a.txt"), "hit");
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1); var cancelled = new AtomicBoolean();
        try (DesktopTools tools = new DesktopTools(temporary); ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            var first = pool.submit(() -> tools.execute("search_files", "{\"path\":\".\",\"text\":\"hit\"}", "first", false, () -> {
                if (checks.incrementAndGet() == 3) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                }
                return false;
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var second = pool.submit(() -> tools.execute("search_files", "{\"path\":\".\",\"text\":\"hit\"}", "second", false, () -> {
                    started.countDown(); return cancelled.get();
                }));
                assertTrue(started.await(5, TimeUnit.SECONDS)); cancelled.set(true);
                assertCode("CANCELLED_BEFORE_WRITE", second.get(2, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            assertEquals(DesktopTools.Status.SUCCEEDED, first.get(5, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void boundsReadsAndLiteralSearch() throws Exception {
        Files.writeString(temporary.resolve("large.txt"), "x".repeat(256 * 1024 + 1));
        Files.writeString(temporary.resolve("matches.txt"), "literal [text]\n".repeat(300));
        try (DesktopTools tools = new DesktopTools(temporary)) {
            assertCode("FILE_TOO_LARGE", call(tools, "read_file", "path", "large.txt"));
            JsonNode search = data(call(tools, "search_files", "path", ".", "text", "[text]"));
            assertEquals(250, search.path("matches").size());
            assertTrue(search.path("truncated").asBoolean());
        }
    }

    @Test
    void readPreviewPaginatesAllBytesAndLongLinesWithoutWeakeningVersionChecks() throws Exception {
        String content = "中🙂".repeat(1200) + "\r\n" + ("x".repeat(1000) + "\r\n").repeat(80) + "tail";
        try (DesktopTools tools = new DesktopTools(temporary)) {
            assertEquals(DesktopTools.Status.SUCCEEDED, call(tools, "write_file", "path", "paged.txt", "content", content).status());
            var reconstructed = new StringBuilder(); int line = 1; int column = 1; int pages = 0; String version = null;
            while (true) {
                var args = JSON.createObjectNode().put("path", "paged.txt").put("offset", line).put("column", column);
                var result = tools.execute("read_file", args.toString(), "page-" + pages++, false, () -> false);
                assertEquals(DesktopTools.Status.SUCCEEDED, result.status(), result.contentJson());
                var value = data(result); String preview = value.path("content").asText();
                assertFalse(preview.isEmpty()); assertTrue(preview.getBytes(StandardCharsets.UTF_8).length <= 51_200);
                assertTrue(preview.lines().count() <= 2000); preview.lines().forEach(text -> assertTrue(text.length() <= 2000));
                if (version == null) version = value.path("version").asText(); else assertEquals(version, value.path("version").asText());
                reconstructed.append(preview);
                if (!value.path("truncated").asBoolean()) { assertTrue(value.path("nextLine").isNull()); break; }
                line = value.path("nextLine").asInt(); column = value.path("nextColumn").asInt();
                assertTrue(pages < 64, "Read cursor did not make progress.");
            }
            assertEquals(content, reconstructed.toString()); assertTrue(pages > 2);
            Files.writeString(temporary.resolve("paged.txt"), content + "external");
            assertCode("FILE_VERSION_CONFLICT", call(tools, "edit_file", "path", "paged.txt", "oldText", "tail", "newText", "changed"));
            assertEquals(content + "external", Files.readString(temporary.resolve("paged.txt")));
        }
    }

    @Test
    void searchProvidesReadableColumnForLongUnicodeLines() throws Exception {
        var random = new java.util.Random(4921);
        var prefix = new StringBuilder();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 3000; i++) prefix.append(alphabet.charAt(random.nextInt(alphabet.length())));
        prefix.append("界😀");
        Files.writeString(temporary.resolve("long-unicode.txt"), prefix + "needle needle\n");
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var match = data(call(tools, "search_files", "path", "long-unicode.txt", "text", "needle")).path("matches").get(0);
            assertTrue(match.path("lineTruncated").asBoolean());
            assertFalse(match.path("text").asText().contains("needle"));
            assertEquals(prefix.length() + 1, match.path("readColumn").asInt());
            var read = JSON.createObjectNode().put("path", "long-unicode.txt").put("offset", match.path("line").asInt())
                    .put("column", match.path("readColumn").asInt());
            assertTrue(data(tools.execute("read_file", read.toString(), "match-read", false, () -> false))
                    .path("content").asText().startsWith("needle needle"));
            var compact = data(call(tools, "search_files", "path", "long-unicode.txt", "text", "needle", "output", "matches"));
            assertEquals(2, compact.path("matches").get(0).size());
        }
    }

    @Test
    void searchColumnReadsCrLfAndSurrogateBoundaries() throws Exception {
        Files.writeString(temporary.resolve("boundaries.txt"), "abc\r\n😀end\r\n");
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var cr = data(call(tools, "search_files", "path", "boundaries.txt", "text", "c\r")).path("matches").get(0);
            assertEquals(3, cr.path("readColumn").asInt());
            var read = JSON.createObjectNode().put("path", "boundaries.txt").put("offset", cr.path("line").asInt())
                    .put("column", cr.path("readColumn").asInt());
            assertTrue(data(tools.execute("read_file", read.toString(), "cr-read", false, () -> false))
                    .path("content").asText().startsWith("c\r\n"));
            var low = data(call(tools, "search_files", "path", "boundaries.txt", "text", "\ude00")).path("matches").get(0);
            assertEquals(1, low.path("readColumn").asInt());
            read.put("offset", low.path("line").asInt()).put("column", low.path("readColumn").asInt());
            assertTrue(data(tools.execute("read_file", read.toString(), "surrogate-read", false, () -> false))
                    .path("content").asText().startsWith("😀"));
            var cross = data(call(tools, "search_files", "path", "boundaries.txt", "text", "c\r\n😀"));
            assertEquals(0, cross.path("matches").size());
        }
    }

    @Test
    void readLineLimitAndCursorValidationAndSearchPreviewRespectTheirOwnUnits() throws Exception {
        Files.writeString(temporary.resolve("lines.txt"), "a\n".repeat(2200));
        Files.writeString(temporary.resolve("unicode.txt"), "界".repeat(800) + " needle\n");
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var first = data(call(tools, "read_file", "path", "lines.txt"));
            assertEquals("a\n".repeat(2000), first.path("content").asText());
            assertEquals(2001, first.path("nextLine").asInt()); assertEquals(1, first.path("nextColumn").asInt());
            var args = JSON.createObjectNode().put("path", "lines.txt").put("offset", 2001).put("limit", 200);
            var last = data(tools.execute("read_file", args.toString(), "last-page", false, () -> false));
            assertEquals("a\n".repeat(200), last.path("content").asText()); assertFalse(last.path("truncated").asBoolean());
            for (var invalid : List.of("{\"offset\":0}", "{\"column\":null}", "{\"limit\":2001}")) {
                var selected = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(invalid); selected.put("path", "lines.txt");
                assertCode("INVALID_ARGUMENT", tools.execute("read_file", selected.toString(), UUID.randomUUID().toString(), false, () -> false));
            }
            var search = data(call(tools, "search_files", "path", "unicode.txt", "text", "needle")).path("matches").get(0);
            assertEquals("界".repeat(666), search.path("text").asText());
            assertEquals(1998, search.path("text").asText().getBytes(StandardCharsets.UTF_8).length);
            assertTrue(search.path("lineTruncated").asBoolean());
            String boundary = ("x".repeat(1998) + "\r\n").repeat(25) + "y".repeat(1199) + "\r\nz";
            Files.writeString(temporary.resolve("boundary.txt"), boundary);
            var head = data(call(tools, "read_file", "path", "boundary.txt"));
            assertEquals(51_199, head.path("content").asText().getBytes(StandardCharsets.UTF_8).length);
            var continuation = JSON.createObjectNode().put("path", "boundary.txt").put("offset", head.path("nextLine").asInt())
                    .put("column", head.path("nextColumn").asInt());
            var tail = data(tools.execute("read_file", continuation.toString(), "crlf-tail", false, () -> false));
            assertEquals("\r\nz", tail.path("content").asText());
            assertEquals(boundary, head.path("content").asText() + tail.path("content").asText());
        }
    }

    @Test
    void commandRequiresExplicitApprovalAndReportsUnicodeAndExitCode() throws Exception {
        try (DesktopTools tools = new DesktopTools(temporary)) {
            String command = probeCommand("echo", temporary.resolve("receipt"));
            String args = JSON.createObjectNode().put("command", command).toString();
            assertCode("COMMAND_APPROVAL_REQUIRED", tools.execute("run_command", args, "denied", false, () -> false));
            assertFalse(Files.exists(temporary.resolve("receipt")));
            DesktopTools.Result result = tools.execute("run_command", args, "approved", true, () -> false);
            assertEquals(DesktopTools.Status.SUCCEEDED, result.status(), result.contentJson());
            assertEquals(0, data(result).path("exitCode").asInt());
            assertTrue(data(result).path("stdout").asText().contains("你好 stdout"), result.contentJson());
            assertTrue(data(result).path("stderr").asText().contains("stderr"), result.contentJson());
            assertTrue(Files.readString(temporary.resolve("receipt")).contains(temporary.toRealPath().toString()));
        }
    }

    @Test
    void shellDeclarationMatchesPlatformDeadlineAndRejectsValuesAboveTenMinutes() throws Exception {
        try (DesktopTools tools = new DesktopTools(temporary)) {
            var declaration = tools.definitions().stream().filter(item -> item.name().equals("run_command")).findFirst().orElseThrow();
            var timeout = JSON.readTree(declaration.inputSchemaJson()).path("properties").path("timeoutSeconds");
            assertEquals(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac") ? 60 : 120, timeout.path("default").asInt());
            assertEquals(600, timeout.path("maximum").asInt()); assertTrue(declaration.description().contains("64000 bytes"));
            assertCode("INVALID_ARGUMENT", command(tools, "must-not-run", "over-max", 601, () -> false));
        }
    }

    @Test
    void commandOutputIsBoundedWithoutBlockingEitherStream() throws Exception {
        try (DesktopTools tools = new DesktopTools(temporary)) {
            DesktopTools.Result result = command(tools, probeCommand("large", temporary.resolve("receipt")), "large", 15, () -> false);
            assertEquals(DesktopTools.Status.SUCCEEDED, result.status(), result.contentJson());
            JsonNode data = data(result);
            assertTrue(data.path("stdoutTruncated").asBoolean());
            assertTrue(data.path("stderrTruncated").asBoolean());
            assertEquals(64_000, data.path("stdout").asText().getBytes(StandardCharsets.UTF_8).length);
            assertEquals(64_000, data.path("stderr").asText().getBytes(StandardCharsets.UTF_8).length);
            assertTrue(data.path("processTreeStopped").asBoolean());
        }
    }

    @Test
    void commandReportsNonzeroAndClosesStdin() throws Exception {
        try (DesktopTools tools = new DesktopTools(temporary)) {
            DesktopTools.Result failed = command(tools, probeCommand("nonzero", temporary.resolve("unused")), "nonzero", 15, () -> false);
            assertEquals(DesktopTools.Status.FAILED, failed.status(), failed.contentJson());
            assertEquals(7, data(failed).path("exitCode").asInt());
            DesktopTools.Result stdin = command(tools, probeCommand("stdin", temporary.resolve("unused")), "stdin", 15, () -> false);
            assertEquals(DesktopTools.Status.SUCCEEDED, stdin.status(), stdin.contentJson());
            assertTrue(data(stdin).path("stdout").asText().contains("stdin=-1"), stdin.contentJson());
        }
    }

    @Test
    void timeoutPreservesUnknownAndStopsObservedCommandProcesses() throws Exception {
        Path receipt = temporary.resolve("timeout.pid");
        var previousChildren = ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
        var observed = new LinkedHashMap<Long, ProcessHandle>();
        var supervisor = new AtomicReference<ProcessHandle>();
        var ambiguousOwnership = new AtomicBoolean();
        try (DesktopTools tools = new DesktopTools(temporary)) {
            DesktopTools.Result result = command(tools, probeCommand("wait", receipt), "timeout", 2, () -> {
                // 此同步用例只新建一个监督器；仅从本测试JVM直属子进程中识别一次，不扫描其他宿主。
                if (supervisor.get() == null) {
                    var candidates = ProcessHandle.current().children().filter(child -> !previousChildren.contains(child.pid())).toList();
                    if (candidates.size() == 1) supervisor.set(candidates.getFirst());
                    else if (candidates.size() > 1) ambiguousOwnership.set(true);
                }
                var owned = supervisor.get();
                if (owned != null) {
                    observed.putIfAbsent(owned.pid(), owned);
                    owned.descendants().forEach(child -> observed.putIfAbsent(child.pid(), child));
                }
                // 始终不取消、不等待业务JVM就绪，真实deadline保持不变。
                return false;
            });
            assertEquals(DesktopTools.Status.UNKNOWN, result.status(), result.contentJson());
            assertEquals("COMMAND_TIMEOUT", data(result).path("code").asText());
            // 启动阶段超时可能没有完整OS回执；保留原false，不用进程枚举冒充OS确认。
            assertTrue(data(result).path("processTreeStopped").isBoolean(), result.contentJson());
            boolean businessReady = Files.isRegularFile(receipt) && Files.size(receipt) > 0;
            System.out.println("event=timeout_cleanup_evidence processTreeStopped=" + data(result).path("processTreeStopped")
                    + " observedPids=" + observed.keySet() + " businessReady=" + businessReady);
            assertFalse(ambiguousOwnership.get(), "Could not identify one owned command supervisor.");
            assertFalse(observed.isEmpty(), "No actual command supervisor was observed: " + result.contentJson());
            for (var process : observed.values()) assertFalse(process.isAlive(), "Owned observed process remains alive: " + process.pid());
            // 启动预算包含监督器/解释器启动；只有完整PID回执才证明业务probe到达ready。
            if (businessReady) assertStopped(receipt);
        }
    }

    @Test
    void cancellationStopsObservedChildTreeAndPreservesOtherProcesses() throws Exception {
        Path receipt = temporary.resolve("child.pid");
        Path unrelatedReceipt = temporary.resolve("unrelated.pid");
        Process unrelated = new ProcessBuilder(probeArguments("wait", unrelatedReceipt)).start();
        try (DesktopTools tools = new DesktopTools(temporary); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            awaitReceipt(unrelatedReceipt);
            var result = pool.submit(() -> command(tools, probeCommand("tree", receipt), "cancel-tree", 20, () -> false));
            awaitReceipt(receipt);
            tools.cancel("cancel-tree");
            DesktopTools.Result stopped = result.get(15, TimeUnit.SECONDS);
            assertEquals(DesktopTools.Status.UNKNOWN, stopped.status(), stopped.contentJson());
            assertEquals("COMMAND_CANCELLED", data(stopped).path("code").asText());
            assertTrue(data(stopped).path("processTreeStopped").asBoolean(), stopped.contentJson());
            assertStopped(receipt);
            assertTrue(unrelated.isAlive(), "Cancelling one tool must not kill unrelated processes.");
        } finally {
            unrelated.destroyForcibly();
            assertTrue(unrelated.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeWaitsForCommandCleanupAndRejectsNewDispatch() throws Exception {
        Path receipt = temporary.resolve("close.pid");
        DesktopTools tools = new DesktopTools(temporary);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> command(tools, probeCommand("wait", receipt), "close", 20, () -> false));
            awaitReceipt(receipt);
            tools.close();
            assertStopped(receipt);
            DesktopTools.Result stopped = result.get(10, TimeUnit.SECONDS);
            assertEquals(DesktopTools.Status.UNKNOWN, stopped.status(), stopped.contentJson());
            assertEquals("COMMAND_CANCELLED", data(stopped).path("code").asText(), stopped.contentJson());
            assertTrue(data(stopped).path("processTreeStopped").asBoolean(), stopped.contentJson());
            assertCode("TOOLS_CLOSED", call(tools, "list_files", "path", "."));
        } finally {
            tools.close();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void powershellNonterminatingErrorIsNotSuccessful() throws Exception {
        try (DesktopTools tools = new DesktopTools(temporary)) {
            DesktopTools.Result error = command(tools, "Write-Error 'review-probe-nonterminating-error'", "ps-error", 15, () -> false);
            assertEquals(DesktopTools.Status.FAILED, error.status(), error.contentJson());
            assertEquals(1, data(error).path("exitCode").asInt());
            assertTrue(data(error).path("stderr").asText().contains("review-probe-nonterminating-error"), error.contentJson());
            Path absent = temporary.resolve("does-not-exist");
            DesktopTools.Result remove = command(tools, "Remove-Item -LiteralPath '" + absent.toString().replace("'", "''") + "'", "ps-remove", 15, () -> false);
            assertEquals(DesktopTools.Status.FAILED, remove.status(), remove.contentJson());
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void normalShellExitCleansDetachedJobMemberBeforeReportingSuccess() throws Exception {
        Path receipt = temporary.resolve("detached.pid");
        Path membership = temporary.resolve("membership");
        try (DesktopTools tools = new DesktopTools(temporary)) {
            DesktopTools.Result result = command(tools, assertJobMembership(membership) + "; "
                    + probeCommand("detach", receipt), "detach-normal", 20, () -> false);
            assertEquals(DesktopTools.Status.SUCCEEDED, result.status(), result.contentJson());
            assertEquals("True", Files.readString(membership).trim());
            assertEquals("windows_job_object", data(result).path("processTracking").asText());
            assertTrue(data(result).path("processTreeStopped").asBoolean(), result.contentJson());
            assertStopped(receipt);
            Files.writeString(Path.of(receipt + ".gate"), "release");
            assertFalse(Files.exists(Path.of(receipt + ".escaped")));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void cancellationCleansDetachedJobMember() throws Exception {
        Path receipt = temporary.resolve("cancel-detached.pid");
        Path membership = temporary.resolve("cancel-membership");
        try (DesktopTools tools = new DesktopTools(temporary); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> command(tools, assertJobMembership(membership) + "; "
                    + probeCommand("detach-wait", receipt), "detach-cancel", 20, () -> false));
            awaitReceipt(receipt);
            assertEquals("True", Files.readString(membership).trim());
            tools.cancel("detach-cancel");
            DesktopTools.Result result = pending.get(15, TimeUnit.SECONDS);
            assertEquals(DesktopTools.Status.UNKNOWN, result.status(), result.contentJson());
            assertEquals("COMMAND_CANCELLED", data(result).path("code").asText());
            assertTrue(data(result).path("processTreeStopped").asBoolean(), result.contentJson());
            assertStopped(receipt);
            Files.writeString(Path.of(receipt + ".gate"), "release");
            assertFalse(Files.exists(Path.of(receipt + ".escaped")));
        }
    }

    private static String assertJobMembership(Path receipt) {
        return """
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public static class JobMembershipProbe {
                    [DllImport("kernel32.dll")] public static extern IntPtr GetCurrentProcess();
                    [DllImport("kernel32.dll", SetLastError=true)]
                    public static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool member);
                }
                '@
                $member = $false
                if (-not [JobMembershipProbe]::IsProcessInJob([JobMembershipProbe]::GetCurrentProcess(), [IntPtr]::Zero, [ref]$member)) { throw 'Job query failed' }
                if (-not $member) { throw 'Business process is outside the job' }
                [IO.File]::WriteAllText('%s', $member.ToString())
                """.formatted(receipt.toString().replace("'", "''"));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void posixGroupCleansDetachedDescendantOnNormalExitAndCancellation() throws Exception {
        Path receipt = temporary.resolve("posix-detached.pid");
        try (DesktopTools tools = new DesktopTools(temporary); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            DesktopTools.Result normal = command(tools, probeCommand("detach", receipt), "posix-normal", 15, () -> false);
            assertEquals(DesktopTools.Status.SUCCEEDED, normal.status(), normal.contentJson());
            assertEquals("posix_process_group", data(normal).path("processTracking").asText());
            assertTrue(data(normal).path("processTreeStopped").asBoolean(), normal.contentJson());
            assertStopped(receipt);
            Path cancelledReceipt = temporary.resolve("posix-cancelled.pid");
            var pending = pool.submit(() -> command(tools, probeCommand("detach-wait", cancelledReceipt), "posix-cancel", 15, () -> false));
            awaitReceipt(cancelledReceipt);
            tools.cancel("posix-cancel");
            DesktopTools.Result result = pending.get(10, TimeUnit.SECONDS);
            assertEquals(DesktopTools.Status.UNKNOWN, result.status(), result.contentJson());
            assertTrue(data(result).path("processTreeStopped").asBoolean(), result.contentJson());
            assertStopped(cancelledReceipt);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsJunctionCannotEscapeWorkspaceWithoutSymlinkPrivileges() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("junction-workspace"));
        Path outside = Files.createDirectory(temporary.resolve("junction-outside"));
        Files.writeString(outside.resolve("private.txt"), "private");
        Path junction = workspace.resolve("escape");
        Path cmd = Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe");
        Process create = new ProcessBuilder(cmd.toString(), "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(create.waitFor(5, TimeUnit.SECONDS), "Junction creation did not finish.");
            assertEquals(0, create.exitValue(), new String(create.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            try (DesktopTools tools = new DesktopTools(workspace)) {
                assertCode("PATH_OUTSIDE_WORKSPACE", call(tools, "read_file", "path", "escape/private.txt"));
                assertCode("PATH_OUTSIDE_WORKSPACE", call(tools, "write_file", "path", "escape/new.txt", "content", "outside"));
                assertEquals("private", Files.readString(outside.resolve("private.txt")));
                assertFalse(Files.exists(outside.resolve("new.txt")));
            }
        } finally {
            if (create.isAlive()) {
                create.destroyForcibly();
                assertTrue(create.waitFor(5, TimeUnit.SECONDS));
            }
            Files.deleteIfExists(junction);
        }
    }

    private static DesktopTools.Result call(DesktopTools tools, String name, String... values) {
        var args = JSON.createObjectNode();
        for (int index = 0; index < values.length; index += 2) {
            args.put(values[index], values[index + 1]);
        }
        return tools.execute(name, args.toString(), UUID.randomUUID().toString(), false, () -> false);
    }

    private static DesktopTools.Result command(DesktopTools tools, String command, String operation,
                                                int timeout, java.util.function.BooleanSupplier cancelled) {
        return tools.execute("run_command", JSON.createObjectNode().put("command", command)
                .put("timeoutSeconds", timeout).toString(), operation, true, cancelled);
    }

    private static JsonNode data(DesktopTools.Result result) {
        try {
            return JSON.readTree(result.contentJson());
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertCode(String expected, DesktopTools.Result result) {
        assertEquals(DesktopTools.Status.FAILED, result.status(), result.contentJson());
        assertEquals(expected, data(result).path("error").path("code").asText(), result.contentJson());
    }

    private static void assertStopped(Path receipt) throws IOException {
        long pid = Long.parseLong(Files.readString(receipt).trim());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Owned process remains alive: " + pid);
    }

    private static void awaitReceipt(Path receipt) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(receipt) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertTrue(Files.exists(receipt), "The actual child process did not signal readiness: " + receipt);
    }

    private static String probeCommand(String mode, Path receipt) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return (windows ? "& " : "") + probeArguments(mode, receipt).stream()
                .map(value -> windows ? "'" + value.replace("'", "''") + "'" : "'" + value.replace("'", "'\"'\"'") + "'")
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static List<String> probeArguments(String mode, Path receipt) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String classes;
        try {
            classes = Path.of(CommandProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException failure) {
            throw new IllegalStateException(failure);
        }
        return List.of(java.toString(), "-Dfile.encoding=UTF-8", "-cp", classes, CommandProbe.class.getName(), mode, receipt.toString());
    }

    /** 独立子进程夹具只读参数并写入测试私有目录，不依赖生产测试分支。 */
    public static final class CommandProbe {
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            Path receipt = Path.of(args[1]);
            if ("tree".equals(mode)) {
                Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), CommandProbe.class.getName(), "wait", receipt.toString())
                        .inheritIO().start();
                child.waitFor();
            } else if ("detach".equals(mode) || "detach-wait".equals(mode)) {
                Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), CommandProbe.class.getName(), "detached-child", receipt.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                child.getOutputStream().close();
                long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
                while ((!Files.exists(receipt) || Files.size(receipt) == 0) && System.nanoTime() < deadline) {
                    if (!child.isAlive()) { throw new IllegalStateException("Detached child exited before readiness"); }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
                if (!Files.exists(receipt)) { throw new IllegalStateException("Detached child did not signal readiness"); }
                if ("detach-wait".equals(mode)) { new CompletableFuture<Void>().get(); }
            } else if ("detached-child".equals(mode)) {
                Files.writeString(receipt, Long.toString(ProcessHandle.current().pid()));
                while (!Files.exists(Path.of(receipt + ".gate"))) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
                Files.writeString(Path.of(receipt + ".escaped"), "escaped");
            } else if ("wait".equals(mode)) {
                Files.writeString(receipt, Long.toString(ProcessHandle.current().pid()));
                new CompletableFuture<Void>().get();
            } else if ("large".equals(mode)) {
                byte[] output = "x".repeat(128 * 1024).getBytes(StandardCharsets.UTF_8);
                System.out.write(output);
                System.err.write(output);
            } else if ("nonzero".equals(mode)) {
                System.exit(7);
            } else if ("stdin".equals(mode)) {
                System.out.println("stdin=" + System.in.read());
            } else {
                Files.writeString(receipt, Path.of("").toRealPath().toString());
                // JDK21 在 Windows 对 stdout.encoding 单独采用本机代码页，file.encoding 不覆盖它。
                // 夹具明确发出 UTF-8，验证工具实际字节采集而非默认 PrintStream 编码。
                System.out.write("你好 stdout\n".getBytes(StandardCharsets.UTF_8));
                System.err.write("stderr\n".getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
