package com.skillforge.execution;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** 宿主已授权文件的懒读取源；每次打开按所属存储重新核验，不能序列化为网络请求。 */
public record ExecutionInputSource(ArtifactFile file, Opener source) {
    public ExecutionInputSource {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(source, "source");
    }
    public InputStream open() throws IOException { return source.open(); }
    @FunctionalInterface public interface Opener { InputStream open() throws IOException; }
}
