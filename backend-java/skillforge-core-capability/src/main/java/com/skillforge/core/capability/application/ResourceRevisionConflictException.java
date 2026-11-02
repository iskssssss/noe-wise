package com.skillforge.core.capability.application;

/** 外部资源映射 If-Match/revision 竞争失败。 */
public class ResourceRevisionConflictException extends RuntimeException {

    private final long currentRevision;
    private final String currentEtag;

    public ResourceRevisionConflictException(long currentRevision, String currentEtag) {
        super("resource mapping revision is stale or missing");
        this.currentRevision = currentRevision;
        this.currentEtag = currentEtag;
    }

    public long currentRevision() {
        return currentRevision;
    }

    public String currentEtag() {
        return currentEtag;
    }
}
