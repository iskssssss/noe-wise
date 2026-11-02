package com.skillforge.core.foundation.assets;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 公共文件部署参数，修改后重启生效；文件目录必须为宿主持久卷。 */
@ConfigurationProperties("skillforge.foundation.files")
public class FoundationFileProperties {
    /** 持久根目录，不包含 Session/Artifact 文件。 */ private String root = "./data/foundation-files";
    /** 单文件上限，单位字节，默认 20 MiB。 */ private long maxBytes = 20 * 1024 * 1024;
    /** 无引用文件最小保留时长，单位小时。 */ private int orphanHours = 24;
    public String getRoot(){return root;} public void setRoot(String root){this.root=root;}
    public long getMaxBytes(){return maxBytes;} public void setMaxBytes(long maxBytes){this.maxBytes=maxBytes;}
    public int getOrphanHours(){return orphanHours;} public void setOrphanHours(int orphanHours){this.orphanHours=orphanHours;}
}
