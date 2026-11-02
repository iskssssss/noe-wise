package com.skillforge.institution.browser;

import java.time.Instant;
import lombok.Data;

/** 宿主连接等待的持久事实；只保存来源和设备引用，不保存配对串或设备密钥。 */
@Data
public class BrowserConnectionRequest {
    /** 同一 Run/invocation 的稳定连接请求 UUID。 */
    private String requestId;
    /** 由可信 Runtime 或认证入口解析的机构。 */
    private long tenantId;
    /** 原调用所属个人身份。 */
    private long endUserId;
    /** 原工具调用的逻辑 Session。 */
    private String sessionId;
    /** 原工具调用的 Run。 */
    private String runId;
    /** 原工具 invocation；恢复不创建另一业务调用。 */
    private String invocationId;
    /** 首次等待原因：NO_SELECTION、OFFLINE 或 AUTHORIZATION_REQUIRED。 */
    private String reason;
    /** 连接生命周期，不替代工具或 Run 的状态。 */
    private String status;
    /** 用户选择或准确配对结果绑定的设备。 */
    private String deviceId;
    /** 派发前冻结的远程浏览器身份。 */
    private String browserId;
    /** 用户是否明确要求保存为个人默认设备。 */
    private boolean makeDefault;
    /** 配对已派发后不因重复点击再生成一次性凭据。 */
    private boolean pairDispatched;
    /** 可公开的稳定错误，不保存异常正文。 */
    private String error;
    /** Runtime 已提交的准确等待身份快照，首次观察后冻结，不含请求正文。 */
    private String waitSnapshot;
    /** 行 CAS 版本，防止迟到选择覆盖已冻结目标。 */
    private long revision;
    /** 稳定恢复输入的受理时间。 */
    private Instant createdAt;
    /** 最近一次状态推进时间。 */
    private Instant updatedAt;
}
