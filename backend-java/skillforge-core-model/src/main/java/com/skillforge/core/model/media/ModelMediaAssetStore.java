package com.skillforge.core.model.media;

import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.AssetKind;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** 宿主拥有的不可变媒体正文；核心任务不泄漏供应商签名 URL 或宿主路径。 */
public interface ModelMediaAssetStore {
    Stored importAsset(String id, AssetKind kind, URI source, String reportedMediaType,
            Duration timeout, BooleanSupplier cancelled) throws IOException;
    Stored storeBytes(String id, AssetKind kind, byte[] bytes, String mediaType,
            Duration timeout, BooleanSupplier cancelled) throws IOException;
    InputStream open(String id) throws IOException;
    record Stored(String mediaType, long sizeBytes, String sha256, String name) { }
}
