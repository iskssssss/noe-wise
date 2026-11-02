package com.skillforge.runtime.adapter.jdbc.session;
import java.time.Instant;
/** PostgreSQL storage-clock 的无损 epoch-second/nano 投影。 */
final class SessionStorageTimeRow {
    private long epochSecond;
    private int nano;

    SessionStorageTimeRow() {}

    Instant toInstant() {
        return Instant.ofEpochSecond(epochSecond, nano);
    }

    public long getEpochSecond() { return epochSecond; }
    public void setEpochSecond(long value) { epochSecond = value; }
    public int getNano() { return nano; }
    public void setNano(int value) { nano = value; }
}
