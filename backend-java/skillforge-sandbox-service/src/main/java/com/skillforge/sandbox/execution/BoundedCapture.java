package com.skillforge.sandbox.execution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 始终排空进程输出，只保留配置上限内的前缀并标记截断。 */
final class BoundedCapture {

    private final int limit;
    private final ByteArrayOutputStream captured;
    private boolean truncated;

    BoundedCapture(int limit) {
        this.limit = limit;
        this.captured = new ByteArrayOutputStream(Math.min(limit, 8192));
    }

    void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int remaining = limit - captured.size();
            if (remaining > 0) {
                captured.write(buffer, 0, Math.min(read, remaining));
            }
            if (read > remaining) {
                truncated = true;
            }
        }
    }

    /** 容量切口只返回完整 UTF-8 字符；原始字节仍由 bytes() 提供给严格消费者。 */
    String text() {
        if (!truncated) {
            return captured.toString(StandardCharsets.UTF_8);
        }
        // 容量截断不是流的真实结尾；未完成的 UTF-8 字符不生成会扩大字节数的替代字符。
        byte[] prefix = captured.toByteArray();
        CharBuffer text = CharBuffer.allocate(prefix.length);
        StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(prefix), text, false);
        return text.flip().toString();
    }

    byte[] bytes() {
        return captured.toByteArray();
    }

    boolean truncated() {
        return truncated;
    }
}
