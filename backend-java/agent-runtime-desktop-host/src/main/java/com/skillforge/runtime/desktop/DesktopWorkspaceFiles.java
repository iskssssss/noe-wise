package com.skillforge.runtime.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/** 文件观察属于一个会话；同一进程的不同会话通过固定条带锁共享写入临界区。 */
final class DesktopWorkspaceFiles {
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_PATH_CHARS = 4096;
    private static final int MAX_LIST_ENTRIES = 200;
    private static final int MAX_READ_BYTES = 51_200;
    private static final int MAX_READ_LINES = 2000;
    private static final int MAX_READ_LINE_CHARS = 2000;
    private static final int MAX_SEARCH_ENTRIES = 5000;
    private static final int MAX_SEARCH_MATCHES = 250;
    private static final int MAX_MATCH_BYTES = 2000;
    private static final int MAX_SEARCH_PAGE_BYTES = 32_768;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_SEARCH_CURSORS = 8;
    private static final long SEARCH_CURSOR_IDLE_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final int LOCK_STRIPES = 256;
    private static final ReentrantLock[] FILE_LOCKS = createLocks();
    private static final ScheduledThreadPoolExecutor SEARCH_EXPIRATION = searchExpiration();
    private final Path workspace;
    private final Map<Path, String> observations = new ConcurrentHashMap<>();
    private final Map<String, SearchCursor> searches = new HashMap<>();
    private final ReentrantLock searchLock = new ReentrantLock();
    private boolean searchesClosed;

    DesktopWorkspaceFiles(Path workspace) throws IOException {
        this.workspace = workspace.toRealPath();
        if (!Files.isDirectory(this.workspace)) {
            throw new IOException("Workspace must be an existing directory.");
        }
    }

    Path workspace() {
        return workspace;
    }

    ObjectNode list(JsonNode args, BooleanSupplier cancelled) throws IOException {
        Path directory = resolve(args, true);
        if (!Files.isDirectory(directory)) {
            throw new DesktopTools.ToolFailure("NOT_DIRECTORY", "The selected path is not a directory.");
        }
        String after = optionalFilenameArgument(args, "after");
        String nameContains = optionalFilenameArgument(args, "nameContains");
        Comparator<Path> order = Comparator.comparing(path -> path.getFileName().toString());
        // 只保留最靠前的 201 个候选；每页重新扫描，游标不是目录快照。
        var candidates = new PriorityQueue<Path>(MAX_LIST_ENTRIES + 1, order.reversed());
        try (Stream<Path> children = Files.list(directory)) {
            Iterator<Path> iterator = children.iterator();
            while (iterator.hasNext()) {
                checkCancelled(cancelled);
                Path child = iterator.next();
                String name = child.getFileName().toString();
                if ((after != null && name.compareTo(after) <= 0)
                        || (nameContains != null && !name.contains(nameContains))) continue;
                candidates.add(child);
                if (candidates.size() > MAX_LIST_ENTRIES + 1) candidates.remove();
            }
        }
        boolean truncated = candidates.size() > MAX_LIST_ENTRIES;
        if (truncated) candidates.remove();
        var page = candidates.stream().sorted(order).toList();
        ObjectNode result = DesktopTools.object().put("path", relative(directory));
        ArrayNode entries = result.putArray("entries");
        for (Path child : page) {
            ObjectNode entry = entries.addObject().put("name", child.getFileName().toString());
            if (Files.isSymbolicLink(child)) {
                entry.put("type", "symbolic_link");
            } else {
                entry.put("type", Files.isDirectory(child) ? "directory" : "file");
            }
        }
        result.put("truncated", truncated);
        if (truncated) result.put("nextAfter", page.getLast().getFileName().toString());
        return result;
    }

    private static String optionalFilenameArgument(JsonNode args, String name) {
        if (!args.has(name)) return null;
        JsonNode value = args.get(name);
        if (!value.isTextual() || value.textValue().isEmpty()
                || value.textValue().codePointCount(0, value.textValue().length()) > MAX_PATH_CHARS) {
            throw new DesktopTools.ToolFailure("INVALID_ARGUMENT", "Invalid argument: " + name);
        }
        return value.textValue();
    }

    ObjectNode read(JsonNode args) throws IOException {
        Path file = resolve(args, true);
        ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            if (!file.equals(resolve(args, true))) {
                throw new DesktopTools.ToolFailure("PATH_CHANGED", "The path changed while being read. Read it again.");
            }
            byte[] bytes = readBounded(file);
            String content = decode(bytes);
            String version = version(bytes);
            int offset = optionalPositiveInt(args, "offset", 1, Integer.MAX_VALUE);
            int column = optionalPositiveInt(args, "column", 1, MAX_FILE_BYTES + 1);
            int limit = optionalPositiveInt(args, "limit", MAX_READ_LINES, MAX_READ_LINES);
            var window = readWindow(content, offset, column, limit);
            observations.put(file, version);
            var result = DesktopTools.object().put("path", relative(file)).put("content", window.content())
                    .put("version", version).put("truncated", window.truncated()).put("startLine", offset).put("startColumn", column);
            if (window.truncated()) result.put("nextLine", window.nextLine()).put("nextColumn", window.nextColumn());
            else result.putNull("nextLine").putNull("nextColumn");
            return result;
        } finally {
            lock.unlock();
        }
    }

    ObjectNode write(JsonNode args, BooleanSupplier cancelled) throws IOException {
        return change(args, false, cancelled);
    }

    ObjectNode edit(JsonNode args, BooleanSupplier cancelled) throws IOException {
        return change(args, true, cancelled);
    }

    private ObjectNode change(JsonNode args, boolean edit, BooleanSupplier cancelled) throws IOException {
        Path file = resolve(args, edit);
        ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            Path checked = resolve(args, edit);
            if (!checked.equals(file)) {
                throw new DesktopTools.ToolFailure("PATH_CHANGED", "The path changed; read it again before writing.");
            }
            checkCancelled(cancelled);
            boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
            String before = "";
            if (exists) {
                byte[] current = readBounded(file);
                String observed = observations.get(file);
                if (observed == null) {
                    throw new DesktopTools.ToolFailure("READ_REQUIRED", "Read the existing file before writing it.");
                }
                if (!observed.equals(version(current))) {
                    throw new DesktopTools.ToolFailure("FILE_VERSION_CONFLICT", "The file changed since it was read. Read it again.");
                }
                before = decode(current);
            } else if (observations.containsKey(file)) {
                throw new DesktopTools.ToolFailure("FILE_VERSION_CONFLICT", "The previously read file was deleted. Read current state again.");
            }
            String after;
            if (edit) {
                String oldText = DesktopTools.requiredText(args, "oldText", MAX_FILE_BYTES, false);
                String newText = DesktopTools.requiredText(args, "newText", MAX_FILE_BYTES, true);
                int index = before.indexOf(oldText);
                if (index < 0 || before.indexOf(oldText, index + 1) >= 0) {
                    throw new DesktopTools.ToolFailure("EDIT_MATCH_NOT_UNIQUE", "oldText must match exactly one location.");
                }
                after = before.substring(0, index) + newText + before.substring(index + oldText.length());
            } else {
                after = DesktopTools.requiredText(args, "content", MAX_FILE_BYTES, true);
            }
            byte[] output = after.getBytes(StandardCharsets.UTF_8);
            if (output.length > MAX_FILE_BYTES) {
                throw new DesktopTools.ToolFailure("FILE_TOO_LARGE", "The resulting file exceeds 256 KiB.");
            }
            checkCancelled(cancelled);
            // 同目录临时文件加原子替换，避免失败后留下截断的目标文件；不静默退回非原子写。
            Path staging = Files.createTempFile(file.getParent(), ".noewise-write-", ".tmp");
            try {
                Files.write(staging, output);
                if (exists && Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
                    Files.setPosixFilePermissions(staging, Files.getPosixFilePermissions(file));
                }
                checkCancelled(cancelled);
                try {
                    Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException failure) {
                    throw new DesktopTools.ToolFailure("ATOMIC_WRITE_UNSUPPORTED", "This filesystem cannot atomically replace the file.");
                } catch (IOException failure) {
                    throw new DesktopTools.ToolFailure("FILE_WRITE_OUTCOME_UNKNOWN", "Atomic file replacement did not return a receipt. Read the file before retrying.");
                }
            } finally {
                Files.deleteIfExists(staging);
            }
            String version = version(output);
            observations.put(file, version);
            return DesktopTools.object().put("path", relative(file)).put("version", version)
                    .put("created", !exists).put("before", before).put("after", after)
                    .put("diff", previewDiff(relative(file), before, after));
        } finally {
            lock.unlock();
        }
    }

    /** 游标属于当前会话实例，只能消费一次；不把失效的游标解释为新的搜索。 */
    ObjectNode search(JsonNode args, BooleanSupplier cancelled) throws IOException {
        try {
            while (!searchLock.tryLock(100, TimeUnit.MILLISECONDS)) checkCancelled(cancelled);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DesktopTools.ToolFailure("CANCELLED_BEFORE_WRITE", "The operation was cancelled before further work.");
        }
        try {
            checkCancelled(cancelled);
            if (searchesClosed) throw new DesktopTools.ToolFailure("TOOLS_CLOSED", "The desktop tools have stopped.");
            return searchPage(args, cancelled);
        } finally { searchLock.unlock(); }
    }

    private ObjectNode searchPage(JsonNode args, BooleanSupplier cancelled) throws IOException {
        Path selected = resolve(args, true);
        String text = DesktopTools.requiredText(args, "text", 1024, false);
        var outputArg = args.get("output");
        String output = outputArg == null ? "content" : DesktopTools.requiredText(args, "output", 16, false);
        if (!output.equals("content") && !output.equals("matches"))
            throw new DesktopTools.ToolFailure("INVALID_ARGUMENT", "Search output must be content or matches.");
        var cursorArg = args.get("cursor");
        if (cursorArg != null && (!cursorArg.isTextual() || cursorArg.textValue().isBlank() || cursorArg.textValue().length() > 64))
            throw new DesktopTools.ToolFailure("INVALID_ARGUMENT", "Invalid search cursor.");
        long now = System.nanoTime();
        searches.entrySet().removeIf(entry -> {
            if (now - entry.getValue().lastAccess < SEARCH_CURSOR_IDLE_NANOS) return false;
            entry.getValue().close(); return true;
        });
        SearchCursor cursor;
        if (cursorArg == null) {
            if (searches.size() >= MAX_SEARCH_CURSORS)
                throw new DesktopTools.ToolFailure("SEARCH_CURSOR_LIMIT", "Too many unfinished searches; continue them or wait for expiration.");
            cursor = new SearchCursor(selected, text, output, Files.walk(selected, MAX_DEPTH));
        } else {
            cursor = searches.remove(cursorArg.textValue());
            if (cursor == null) throw new DesktopTools.ToolFailure("SEARCH_CURSOR_EXPIRED", "Search cursor expired or was already used; start a new search.");
            cursor.cancelExpiration();
            if (!cursor.root.equals(selected) || !cursor.text.equals(text) || !cursor.output.equals(output)) {
                cursor.close();
                throw new DesktopTools.ToolFailure("INVALID_ARGUMENT", "Search cursor does not match path, text and output.");
            }
        }
        try {
            ArrayNode matches = DesktopTools.object().arrayNode();
            int visited = 0, skipped = 0, depthLimited = 0, matchBytes = 0;
            while (matches.size() < MAX_SEARCH_MATCHES) {
                checkCancelled(cancelled);
                if (cursor.lines != null && cursor.lineIndex < cursor.lines.length) {
                    int index = cursor.lineIndex++;
                    String line = cursor.lines[index];
                    int matchIndex = line.indexOf(text);
                    if (matchIndex >= 0) {
                        var match = DesktopTools.object().put("path", relative(cursor.file)).put("line", index + 1);
                        if (output.equals("content")) {
                            int end = utf8PrefixEnd(line, 0, line.length(), MAX_MATCH_BYTES);
                            int readIndex = Character.isLowSurrogate(line.charAt(matchIndex)) ? matchIndex - 1 : matchIndex;
                            match.put("text", line.substring(0, end)).put("lineTruncated", end < line.length())
                                    .put("readColumn", readIndex + 1);
                        }
                        int bytes = match.toString().getBytes(StandardCharsets.UTF_8).length + 1;
                        if (!matches.isEmpty() && matchBytes + bytes > MAX_SEARCH_PAGE_BYTES) {
                            cursor.lineIndex--; break;
                        }
                        matches.add(match); matchBytes += bytes;
                    }
                    continue;
                }
                cursor.lines = null;
                if (visited == MAX_SEARCH_ENTRIES || !cursor.iterator.hasNext()) break;
                Path path = cursor.iterator.next(); visited++;
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && selected.relativize(path).getNameCount() >= MAX_DEPTH) depthLimited++;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    cursor.file = path;
                    cursor.lines = decode(readBounded(checkedReal(path))).split("\n", -1);
                    cursor.lineIndex = 0;
                } catch (IOException | DesktopTools.ToolFailure failure) {
                    cursor.lines = null; skipped++;
                }
            }
            // 空末行无法包含非空查询，不制造无意义的额外页。
            if (cursor.lines != null && cursor.lineIndex == cursor.lines.length - 1 && cursor.lines[cursor.lineIndex].isEmpty()) cursor.lineIndex++;
            boolean hasMore = cursor.lines != null && cursor.lineIndex < cursor.lines.length || cursor.iterator.hasNext();
            cursor.skipped += skipped; cursor.depthLimited += depthLimited;
            ObjectNode result = DesktopTools.object().put("hasMore", hasMore)
                    .put("truncated", hasMore || cursor.depthLimited > 0)
                    .put("visitedEntries", visited).put("skippedFiles", skipped).put("depthLimitedDirectories", depthLimited)
                    .put("totalSkippedFiles", cursor.skipped).put("totalDepthLimitedDirectories", cursor.depthLimited).put("maxDepth", MAX_DEPTH);
            if (hasMore) {
                String next = UUID.randomUUID().toString(); cursor.lastAccess = System.nanoTime();
                searches.put(next, cursor);
                cursor.expiration = SEARCH_EXPIRATION.schedule(() -> expireSearch(next, cursor), SEARCH_CURSOR_IDLE_NANOS, TimeUnit.NANOSECONDS);
                result.put("nextCursor", next);
            } else cursor.close();
            result.set("matches", matches);
            if (output.equals("matches")) result.put("pageMatchCount", matches.size());
            return result;
        } catch (UncheckedIOException failure) {
            cursor.close(); throw failure.getCause();
        } catch (RuntimeException failure) {
            cursor.close(); throw failure;
        }
    }

    void close() {
        searchLock.lock();
        try {
            searchesClosed = true;
            searches.values().forEach(SearchCursor::close);
            searches.clear();
        } finally { searchLock.unlock(); }
    }

    private void expireSearch(String id, SearchCursor cursor) {
        if (!searchLock.tryLock()) {
            SEARCH_EXPIRATION.schedule(() -> expireSearch(id, cursor), 1, TimeUnit.SECONDS);
            return;
        }
        try { if (searches.remove(id, cursor)) cursor.close(); }
        finally { searchLock.unlock(); }
    }

    private static ScheduledThreadPoolExecutor searchExpiration() {
        var scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "desktop-search-expiration"); thread.setDaemon(true); return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private static final class SearchCursor implements AutoCloseable {
        final Path root; final String text; final String output; final Stream<Path> stream; final Iterator<Path> iterator;
        Path file; String[] lines; int lineIndex, skipped, depthLimited; long lastAccess; ScheduledFuture<?> expiration;
        SearchCursor(Path root, String text, String output, Stream<Path> stream) {
            this.root = root; this.text = text; this.output = output; this.stream = stream; this.iterator = stream.iterator();
        }
        void cancelExpiration() { if (expiration != null) expiration.cancel(false); }
        @Override public void close() { cancelExpiration(); stream.close(); }
    }

    private record ReadWindow(String content, boolean truncated, int nextLine, int nextColumn) { }

    /** 页游标保留未返回的字节，包括长行余部；不会为了满足预览上限跳过正文。 */
    private static ReadWindow readWindow(String content, int offset, int column, int limit) {
        int lineStart = 0;
        for (int line = 1; line < offset; line++) {
            int newline = content.indexOf('\n', lineStart);
            if (newline < 0) throw new DesktopTools.ToolFailure("READ_OFFSET_OUT_OF_RANGE", "offset is past the final line.");
            lineStart = newline + 1;
        }
        int newline = content.indexOf('\n', lineStart);
        int contentEnd = newline < 0 ? content.length() : newline;
        if (contentEnd > lineStart && content.charAt(contentEnd - 1) == '\r') contentEnd--;
        if (column > contentEnd - lineStart + 1) throw new DesktopTools.ToolFailure("READ_OFFSET_OUT_OF_RANGE", "column is past the selected line.");
        int start = lineStart + column - 1;
        if (start < content.length() && Character.isLowSurrogate(content.charAt(start)))
            throw new DesktopTools.ToolFailure("INVALID_ARGUMENT", "column must start at a complete Unicode character.");
        int position = start; int line = offset; int completedLines = 0; int bytes = 0;
        while (position < content.length() && completedLines < limit) {
            newline = content.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? content.length() : newline + 1;
            contentEnd = newline < 0 ? content.length() : newline;
            if (contentEnd > lineStart && content.charAt(contentEnd - 1) == '\r') contentEnd--;
            int segmentEnd = Math.min(contentEnd, position + MAX_READ_LINE_CHARS);
            if (segmentEnd < contentEnd && Character.isHighSurrogate(content.charAt(segmentEnd - 1))) segmentEnd--;
            if (segmentEnd == contentEnd) segmentEnd = lineEnd;
            int end = utf8PrefixEnd(content, position, segmentEnd, MAX_READ_BYTES - bytes);
            if (end > contentEnd && end < lineEnd) end = contentEnd;
            bytes += content.substring(position, end).getBytes(StandardCharsets.UTF_8).length;
            position = end;
            if (position < lineEnd) break;
            completedLines++; line++; lineStart = position;
        }
        return new ReadWindow(content.substring(start, position), position < content.length(), line, position - lineStart + 1);
    }

    private static int optionalPositiveInt(JsonNode args, String name, int defaultValue, int maximum) {
        var value = args.get(name);
        if (value == null) return defaultValue;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > maximum)
            throw new DesktopTools.ToolFailure("INVALID_ARGUMENT", name + " is outside its supported range.");
        return value.intValue();
    }

    private static int utf8PrefixEnd(String value, int start, int end, int maxBytes) {
        int position = start; int bytes = 0;
        while (position < end) {
            int codePoint = value.codePointAt(position);
            int size;
            if (codePoint <= 0x7f) size = 1;
            else if (codePoint <= 0x7ff) size = 2;
            else if (codePoint <= 0xffff) size = 3;
            else size = 4;
            if (bytes + size > maxBytes) break;
            bytes += size; position += Character.charCount(codePoint);
        }
        return position;
    }

    private Path resolve(JsonNode args, boolean mustExist) throws IOException {
        String value = DesktopTools.requiredText(args, "path", MAX_PATH_CHARS, false);
        try {
            Path input = Path.of(value);
            if (java.io.File.separatorChar == '\\' && value.indexOf(':') >= 0) {
                throw new DesktopTools.ToolFailure("INVALID_PATH", "Drive-qualified paths and alternate data streams are not permitted.");
            }
            if (input.isAbsolute()) {
                throw new DesktopTools.ToolFailure("PATH_OUTSIDE_WORKSPACE", "Use a workspace-relative path.");
            }
            Path target = workspace.resolve(input).normalize();
            if (!target.startsWith(workspace)) {
                throw new DesktopTools.ToolFailure("PATH_OUTSIDE_WORKSPACE", "The path is outside the selected workspace.");
            }
            if (mustExist || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return checkedReal(target);
            }
            Path parent = checkedReal(target.getParent());
            if (!Files.isDirectory(parent)) {
                throw new DesktopTools.ToolFailure("NOT_DIRECTORY", "The parent directory must exist.");
            }
            return parent.resolve(target.getFileName());
        } catch (InvalidPathException failure) {
            throw new DesktopTools.ToolFailure("INVALID_PATH", "The path is invalid for this platform.");
        }
    }

    private Path checkedReal(Path value) throws IOException {
        Path real = value.toRealPath();
        if (!real.startsWith(workspace)) {
            throw new DesktopTools.ToolFailure("PATH_OUTSIDE_WORKSPACE", "The resolved path is outside the selected workspace.");
        }
        return real;
    }

    private byte[] readBounded(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new DesktopTools.ToolFailure("NOT_FILE", "The selected path is not a regular file.");
        }
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new DesktopTools.ToolFailure("FILE_TOO_LARGE", "Files larger than 256 KiB require a command-based inspection.");
            }
            return bytes;
        }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new DesktopTools.ToolFailure("NOT_UTF8", "The file is not valid UTF-8 text.");
        }
    }

    private String relative(Path file) {
        return workspace.relativize(file).toString().replace('\\', '/');
    }

    private static String version(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("The Java runtime must provide SHA-256.", failure);
        }
    }

    private static String previewDiff(String file, String before, String after) {
        // 完整 before/after 是展示事实源；预览仅保留有界前缀并显式标识截断。
        int previewChars = 8192;
        String diff = "--- a/" + file + "\n+++ b/" + file + "\n-" + before.replace("\n", "\n-")
                + "\n+" + after.replace("\n", "\n+");
        return diff.length() <= previewChars ? diff : diff.substring(0, previewChars) + "\n[diff preview truncated]";
    }

    private static ReentrantLock lockFor(Path file) {
        return FILE_LOCKS[Math.floorMod(file.hashCode(), LOCK_STRIPES)];
    }

    private static ReentrantLock[] createLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new DesktopTools.ToolFailure("CANCELLED_BEFORE_WRITE", "The operation was cancelled before further work.");
        }
    }
}
