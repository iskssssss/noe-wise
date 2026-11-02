package com.skillforge.institution.browser;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.execution.browser.BrowserDevices;

/** 连接卡的授权查询投影，不返回配对串、设备密钥或内部恢复载荷。
 * @param connectionRequestId 当前用户可读的连接请求
 * @param status 连接生命周期，恢复提交不表示页面任务已成功
 * @param reason 首次需要用户操作的原因
 * @param deviceId 明确绑定的设备，可空
 * @param browserId 该设备的远程浏览器身份，可空
 * @param makeDefault 用户是否选择保存默认设备
 * @param devices 当前授权设备目录
 * @param error 稳定公开错误码，可空
 */
public record BrowserConnectionView(
        @JsonProperty("connection_request_id") String connectionRequestId,
        String status, String reason,
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("browser_id") String browserId,
        @JsonProperty("make_default") boolean makeDefault,
        BrowserDevices devices, String error) { }
