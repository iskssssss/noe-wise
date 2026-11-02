package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelImagePolicy;

/** 宿主媒体访问边界；准备发生在冻结前，读取发生在实际发送边界。 */
public interface MediaPort {
    byte[] read(SessionRef owner, MediaRef ref);
    MediaRef prepare(SessionRef owner, MediaRef ref, ModelImagePolicy policy);
}
