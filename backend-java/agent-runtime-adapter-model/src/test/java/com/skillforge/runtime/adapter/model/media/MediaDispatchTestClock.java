package com.skillforge.runtime.adapter.model.media;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** 审计回调精确推进调用期限，不使用等待或真实时间竞争。 */
final class MediaDispatchTestClock extends Clock {
    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    MediaDispatchTestClock() { this(new AtomicReference<>(Instant.EPOCH), ZoneOffset.UTC); }

    private MediaDispatchTestClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now; this.zone = zone;
    }

    void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
    @Override public Instant instant() { return now.get(); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId value) { return new MediaDispatchTestClock(now, value); }
}
