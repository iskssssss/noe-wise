package com.skillforge.runtime.adapter.jdbc.scheduler;

import com.skillforge.runtime.adapter.jdbc.codec.CanonicalBinaryCodec;

/** Scheduler 持久格式的稳定适配层。 */
final class SchedulerCodec {
    private static final CanonicalBinaryCodec CODEC = new CanonicalBinaryCodec(
            new byte[] {'S', 'F', 'S', '1'}, "work_queue_codec_failure");

    private SchedulerCodec() {}

    static String encode(Object value) {
        return CODEC.encode(value);
    }

    static <T> T decode(String encoded, Class<T> expectedType) {
        return CODEC.decode(encoded, expectedType);
    }
}
