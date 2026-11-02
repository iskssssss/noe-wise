package com.skillforge.runtime.adapter.jdbc.queue;

import com.skillforge.runtime.adapter.jdbc.codec.CanonicalBinaryCodec;

/** Work Queue 持久格式的稳定适配层。 */
final class WorkQueueCodec {
    private static final CanonicalBinaryCodec CODEC = new CanonicalBinaryCodec(
            new byte[] {'S', 'F', 'Q', '1'}, "work_queue_codec_failure");

    private WorkQueueCodec() {}

    static String encode(Object value) {
        return CODEC.encode(value);
    }

    static <T> T decode(String encoded, Class<T> expectedType) {
        return CODEC.decode(encoded, expectedType);
    }
}
