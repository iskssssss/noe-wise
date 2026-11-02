package com.skillforge.core.shared.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** 认证前请求体缓存预算；二进制入口预算由宿主按其文件契约显式提供。 */
@ConfigurationProperties(prefix = "skillforge.http")
public record HttpBodyCachingProperties(DataSize maxCachedBodySize, DataSize maxCachedBinaryBodySize) {
    public HttpBodyCachingProperties {
        if (maxCachedBodySize == null) maxCachedBodySize = DataSize.ofMegabytes(8);
        validate(maxCachedBodySize, "普通正文缓存预算");
        validate(maxCachedBinaryBodySize, "二进制正文缓存预算");
    }

    private static void validate(DataSize size, String name) {
        if (size == null || size.toBytes() <= 0 || size.toBytes() >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + "必须配置为正数且小于 2GiB");
        }
    }

    public int maxCachedBodyBytes() { return Math.toIntExact(maxCachedBodySize.toBytes()); }
    public int maxCachedBinaryBodyBytes() { return Math.toIntExact(maxCachedBinaryBodySize.toBytes()); }
}
