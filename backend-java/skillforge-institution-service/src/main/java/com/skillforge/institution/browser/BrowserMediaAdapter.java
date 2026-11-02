package com.skillforge.institution.browser;
import com.skillforge.institution.media.SessionMediaService;
import com.skillforge.runtime.api.SessionRef;
import java.util.Map;
import org.springframework.stereotype.Component;
/** 浏览器截图使用通用图片注册与模型内容，不返回 Base64 文本。 */
@Component
public class BrowserMediaAdapter implements BrowserScreenshotSink {
    private final SessionMediaService media;
    public BrowserMediaAdapter(SessionMediaService media){this.media=media;}
    @Override public SessionMediaService.ImageContent register(SessionRef owner,com.skillforge.runtime.api.RunRef run,String stableId,byte[] png){return SessionMediaService.content(media.registerForRun(owner,run,"browser:"+stableId,"浏览器截图",png));}
}
