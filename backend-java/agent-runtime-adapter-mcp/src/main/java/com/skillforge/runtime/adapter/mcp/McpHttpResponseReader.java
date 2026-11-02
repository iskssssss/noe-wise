package com.skillforge.runtime.adapter.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** 发现和调用共用的有界 HTTP JSON/SSE 解码；调用方拥有超时、取消及流关闭。 */
final class McpHttpResponseReader {
    private final InputStream input;
    private final int maximumBytes;
    private final Runnable check;
    private int bytes;

    private McpHttpResponseReader(InputStream input, int maximumBytes, Runnable check) {
        this.input = input;
        this.maximumBytes = maximumBytes;
        this.check = check;
    }

    static boolean successful(int status) { return status >= 200 && status < 300; }

    static JsonNode packet(HttpResponse<InputStream> response, String id, int maximumBytes,
            Runnable check) throws IOException {
        if (!successful(response.statusCode())) throw new IOException("mcp_http_rejected");
        return packet(response.body(), response.headers().firstValue("Content-Type").orElse(""),
                id, maximumBytes, check);
    }

    static JsonNode packet(InputStream input, String contentType, String id, int maximumBytes,
            Runnable check) throws IOException {
        return new McpHttpResponseReader(input, maximumBytes, check).packet(contentType, id);
    }

    /** 只在确定 HTTP 拒绝后读取完整有界正文；越界或中断由调用方标记为事实不可得。 */
    static byte[] failureBody(InputStream input, int maximumBytes, Runnable check) throws IOException {
        var reader = new McpHttpResponseReader(input, maximumBytes, check);
        var output = new ByteArrayOutputStream(); int value;
        while ((value = reader.read()) != -1) output.write(value);
        return output.toByteArray();
    }

    private JsonNode packet(String contentType, String id) throws IOException {
        String type = contentType.split(";", 2)[0].trim();
        if (type.equalsIgnoreCase("application/json")) {
            var output = new ByteArrayOutputStream(); int value;
            while ((value = read()) != -1) output.write(value);
            return McpProtocol.parse(decode(output.toByteArray()));
        }
        if (!type.equalsIgnoreCase("text/event-stream")) throw new IOException("mcp_response_content_type_invalid");
        var line = new ByteArrayOutputStream(); var data = new StringBuilder(); boolean carriageReturn = false;
        while (true) {
            int value = read();
            if (value == -1) throw new IOException("mcp_response_incomplete");
            if (value == '\n' && carriageReturn) { carriageReturn = false; continue; }
            carriageReturn = value == '\r';
            if (value != '\n' && value != '\r') { line.write(value); continue; }
            String current = decode(line.toByteArray()); line.reset();
            if (current.endsWith("\r")) current = current.substring(0, current.length() - 1);
            if (current.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                String part = current.substring(5); data.append(part.startsWith(" ") ? part.substring(1) : part);
            } else if (current.isEmpty() && !data.isEmpty()) {
                JsonNode packet = McpProtocol.parse(data.toString()); data.setLength(0);
                // 请求流只允许相关通知；sampling、elicitation 与 roots 必须走 MRTR 结果。
                if (packet.isObject() && "2.0".equals(packet.path("jsonrpc").asText())
                        && !packet.has("id") && packet.path("method").isTextual()
                        && !packet.has("result") && !packet.has("error")) continue;
                McpProtocol.result(packet, id);
                check.run();
                return packet;
            }
        }
    }

    private int read() throws IOException {
        // 保留调用路径每 1024 字节及 EOF 的授权/取消复核，不将其放大到逐字节查询。
        if ((bytes & 1023) == 0) check.run();
        int value = input.read();
        if (value == -1) check.run();
        if (value != -1 && ++bytes > maximumBytes) throw new IOException("mcp_response_limit");
        return value;
    }

    private static String decode(byte[] value) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value)).toString();
    }
}
