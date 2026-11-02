package com.skillforge.execution.browser;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
/** 设备授权与真实在线状态；不提供上游不存在的设备改名或选择接口。 */
public record BrowserDevices(List<Device> items,@JsonProperty("installation_url")String installationUrl,@JsonProperty("selected_browser_id")String selectedBrowserId) {
    public BrowserDevices{items=List.copyOf(items);}
    public record Device(@JsonProperty("device_id")String deviceId,@JsonProperty("browser_id")String browserId,
        String label,@JsonProperty("expires_at")String expiresAt,boolean connected){}
}
