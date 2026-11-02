package com.skillforge.institution.artifact;

/** 产物来源图的持久化行；inputIndex 只用于稳定重建规范顺序。 */
public record ArtifactInputRow(
        String artifactId,
        int inputIndex,
        String sourceArtifactId,
        String sourcePath,
        long sourceSize,
        String sourceSha256) {

    ArtifactProvenance provenance() {
        return new ArtifactProvenance(sourceArtifactId, sourcePath, sourceSize, sourceSha256);
    }
}
