package com.skillforge.runtime.session;

/** 原已冻结Skill包中的完整文件值；宿主使用前仍校验归属、编码、大小和原始字节摘要。 */
public record FrozenSkillResource(String path,String encoding,String content,String sha256,long size) {
    @Override public String toString(){return "FrozenSkillResource[REDACTED]";}
}
