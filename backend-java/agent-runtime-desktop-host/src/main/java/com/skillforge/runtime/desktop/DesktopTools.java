package com.skillforge.runtime.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 桌面宿主的本地工具入口；命令确认由 Runtime 的输入等待状态管理。 */
public final class DesktopTools implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ARGUMENT_CHARS = 600_000;
    private final DesktopWorkspaceFiles files;
    private final DesktopCommandProcesses commands;
    private final AtomicBoolean closed = new AtomicBoolean();

    public record Definition(String name, String description, String inputSchemaJson, boolean readOnly) { }
    public enum Status { SUCCEEDED, FAILED, UNKNOWN }
    public record Result(Status status, String contentJson) { }

    public DesktopTools(Path workspace) throws IOException {
        files = new DesktopWorkspaceFiles(workspace);
        commands = new DesktopCommandProcesses(files.workspace());
    }

    public List<Definition> definitions() {
        return List.of(
                definition("list_files", "List immediate children in ascending filename order, at most 200 per page. When truncated, pass nextAfter as after with the same path and nameContains to continue. Each page scans the current directory, not a snapshot; changes at or before the cursor may be missed. nameContains is a case-sensitive literal filename filter.",
                        "{\"path\":{\"type\":\"string\",\"description\":\"Workspace-relative directory; use . for root\"},\"after\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":4096,\"description\":\"Exclusive filename cursor from nextAfter\"},\"nameContains\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":4096}}", "[\"path\"]", true),
                definition("read_file", "Read a UTF-8 workspace file as a paged preview: at most 51200 bytes, 2000 lines and 2000 characters from one line per page. offset and column are one-based; column counts UTF-16 code units and must start at a complete Unicode character. limit defaults to 2000. If truncated, continue using nextLine and nextColumn as offset and column. The complete file capacity is 256 KiB. Every page reports the actual file version; stale observations are rejected before writing or editing.",
                        "{\"path\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647,\"default\":1},\"column\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":262145,\"default\":1},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2000,\"default\":2000}}", "[\"path\"]", true),
                definition("search_files", "Search literal text in UTF-8 workspace files, not glob matching. At most 250 matching lines and 5000 visited entries per page; large matches may reduce the page size. Previews are at most 2000 UTF-8 bytes per line. For content results, use path, line as offset and readColumn as column in read_file to read from the start of the complete Unicode character containing the first match; readColumn is one-based UTF-16 code units. Set output to matches for only paths and line numbers; pageMatchCount counts matching lines in this page, not all results. If hasMore, pass nextCursor as cursor with the same path, text and output. Cursors are single-use, expire after five idle minutes or host restart, and belong to this session. If a cursor is lost or expired, start a new search. Directory changes can cause missed or repeated entries; each file is read once when visited. skippedFiles and depthLimitedDirectories indicate incomplete coverage even when hasMore is false.",
                        "{\"path\":{\"type\":\"string\",\"description\":\"Workspace-relative directory or file\"},\"text\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1024},\"cursor\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64},\"output\":{\"type\":\"string\",\"enum\":[\"content\",\"matches\"],\"default\":\"content\"}}", "[\"path\",\"text\"]", true),
                definition("write_file", "Create a UTF-8 workspace file or replace a previously read file. The 256 KiB limit is the final file capacity, not a single-call generation target. For long files, write a small skeleton first, then grow it with small edit_file calls using unique oldText anchors. The parent directory must exist. A successful write advances the observed version; re-read after a version conflict or host restart. Returns the actual change for display.",
                        "{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\",\"maxLength\":262144}}", "[\"path\",\"content\"]", false),
                definition("edit_file", "Grow or modify a UTF-8 file with a small patch replacing exactly one occurrence of oldText. Use unique anchors for successive patches; the final file must stay within the file capacity limit. Read an existing file before the first edit. Each successful edit advances the observed version, so successive patches do not require another read; re-read after a version conflict or host restart. Returns the actual change for display.",
                        "{\"path\":{\"type\":\"string\"},\"oldText\":{\"type\":\"string\",\"minLength\":1},\"newText\":{\"type\":\"string\"}}", "[\"path\",\"oldText\",\"newText\"]", false),
                definition("read_context_reference", "Read a previously saved context-material event from this run. Use source_event_id from a context preview or history index. Offset is zero-based in Unicode characters; continue with next_offset until end. This reads the exact original safe message material without re-executing a tool.",
                        "{\"source_event_id\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},\"offset\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647,\"default\":0},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":8000,\"default\":8000}}", "[\"source_event_id\"]", true),
                definition("run_command", "Run a command in the selected workspace using the local shell. Requires user confirmation. This is not a sandbox: commands can access other local resources. No persistent terminal or stdin. Output is decoded as UTF-8, limited to 64000 bytes per stream; decoding failures are reported. Configure native programs to emit UTF-8. Timeout/cancel may leave effects and must not be blindly retried.",
                        "{\"command\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":32768},\"timeoutSeconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":600,\"default\":" + DesktopCommandProcesses.defaultTimeoutSeconds() + "}}", "[\"command\"]", false));
    }

    public Result execute(String name, String argumentsJson, String operationId,
                          boolean commandApproved, BooleanSupplier cancelled) {
        if (closed.get()) {
            return failure("TOOLS_CLOSED", "The desktop tools have stopped.");
        }
        if (operationId == null || operationId.isBlank() || cancelled == null) {
            return failure("INVALID_ARGUMENT", "operationId and cancellation signal are required.");
        }
        if (cancelled.getAsBoolean()) {
            return failure("CANCELLED_BEFORE_DISPATCH", "The operation was cancelled before dispatch.");
        }
        try {
            if (argumentsJson == null || argumentsJson.length() > MAX_ARGUMENT_CHARS) {
                throw new ToolFailure("INVALID_ARGUMENT", "Arguments are absent or exceed the size limit.");
            }
            JsonNode args = JSON.readTree(argumentsJson);
            if (args == null || !args.isObject()) {
                throw new ToolFailure("INVALID_ARGUMENT", "Arguments must be a JSON object.");
            }
            return switch (name) {
                case "list_files" -> success(files.list(args, cancelled));
                case "read_file" -> success(files.read(args));
                case "search_files" -> success(files.search(args, cancelled));
                case "write_file" -> success(files.write(args, cancelled));
                case "edit_file" -> success(files.edit(args, cancelled));
                case "run_command" -> commandApproved
                        ? commands.execute(args, operationId, cancelled)
                        : failure("COMMAND_APPROVAL_REQUIRED", "User confirmation is required before command execution.");
                default -> failure("UNKNOWN_TOOL", "The requested tool is not available.");
            };
        } catch (ToolFailure failure) {
            return "FILE_WRITE_OUTCOME_UNKNOWN".equals(failure.code())
                    ? result(Status.UNKNOWN, error(failure.code(), failure.getMessage()))
                    : failure(failure.code(), failure.getMessage());
        } catch (JsonProcessingException failure) {
            return failure("INVALID_ARGUMENT", "Arguments must be valid JSON.");
        } catch (IOException failure) {
            return failure("FILE_IO_ERROR", "The filesystem operation could not complete: " + failure.getClass().getSimpleName());
        }
    }

    public void cancel(String operationId) {
        commands.cancel(operationId);
    }

    @Override
    public void close() {
        closed.set(true);
        files.close();
        commands.close();
    }

    private static Definition definition(String name, String description, String properties,
                                         String required, boolean readOnly) {
        String schema = "{\"type\":\"object\",\"properties\":" + properties
                + ",\"required\":" + required + ",\"additionalProperties\":false}";
        return new Definition(name, description, schema, readOnly);
    }

    static String requiredText(JsonNode args, String name, int maxChars, boolean emptyAllowed) {
        JsonNode value = args.get(name);
        if (value == null || !value.isTextual() || value.textValue().length() > maxChars
                || (!emptyAllowed && value.textValue().isBlank())) {
            throw new ToolFailure("INVALID_ARGUMENT", "Invalid argument: " + name);
        }
        return value.textValue();
    }

    static ObjectNode object() {
        return JSON.createObjectNode();
    }

    static Result success(JsonNode value) {
        return result(Status.SUCCEEDED, value);
    }

    static Result failure(String code, String message) {
        return result(Status.FAILED, error(code, message));
    }

    static ObjectNode error(String code, String message) {
        ObjectNode result = object();
        result.putObject("error").put("code", code).put("message", message);
        return result;
    }

    static Result result(Status status, JsonNode value) {
        String json = value.toString();
        return new Result(status, json);
    }

    static final class ToolFailure extends RuntimeException {
        private final String code;

        ToolFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
