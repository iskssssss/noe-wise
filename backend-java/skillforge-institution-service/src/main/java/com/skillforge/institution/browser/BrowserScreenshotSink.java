package com.skillforge.institution.browser;
import com.skillforge.runtime.api.SessionRef;
import java.util.Map;
/** 将真实浏览器 PNG 注册到通用媒体；实现必须保留来源和用户归属。 */
public interface BrowserScreenshotSink {
    com.skillforge.institution.media.SessionMediaService.ImageContent register(SessionRef owner,com.skillforge.runtime.api.RunRef run,String stableId,byte[] png)throws Exception;
}
