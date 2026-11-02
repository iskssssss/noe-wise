package com.skillforge.runtime.starter;

/** 宿主提供的密文解密入口；Starter 不发明密钥，也不在日志中保留明文。 */
@FunctionalInterface
public interface AgentRuntimeDecrypt {

    /** 返回调用方独占的明文副本，用后必须清零。 */
    byte[] decrypt(String ciphertext);
}
