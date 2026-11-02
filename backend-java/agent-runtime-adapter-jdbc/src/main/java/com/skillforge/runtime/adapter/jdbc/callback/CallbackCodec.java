package com.skillforge.runtime.adapter.jdbc.callback;

import com.skillforge.runtime.adapter.jdbc.codec.CanonicalBinaryCodec;

/** Callback 持久格式的稳定适配层。 */
final class CallbackCodec {
    private static final CanonicalBinaryCodec CODEC = new CanonicalBinaryCodec(
            new byte[] {'S', 'F', 'C', '1'}, "callback_codec_failure");

    private CallbackCodec() {}

    static String encode(Object value) {
        return CODEC.encode(value);
    }

    static <T> T decode(String encoded, Class<T> expectedType) {
        return CODEC.decode(encoded, expectedType);
    }
}
