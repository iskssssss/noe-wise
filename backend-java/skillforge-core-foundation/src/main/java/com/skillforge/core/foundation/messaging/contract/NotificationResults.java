package com.skillforge.core.foundation.messaging.contract;

import java.time.Instant;
import java.util.List;

/** 消息领域受控查询投影；邮箱密码与邮件正文不进入查询响应。 */
public final class NotificationResults {
    private NotificationResults() { }
    /** @param id 消息ID @param title 纯文本标题 @param body 纯文本正文 @param readAt 已读时间，未读为空 @param createdAt 受理时间 */
    public record Inbox(String id, String title, String body, Instant readAt, Instant createdAt) { }
    /** @param id 公告ID @param title 标题 @param body 正文 @param audience 发布范围 @param recipients 冻结收件人数 @param status published或withdrawn @param createdAt 发布时间 */
    public record Announcement(String id, String title, String body, String audience, int recipients, String status, Instant createdAt) { }
    /** @param id 模板版本ID @param key 模板键 @param subject 标题模板 @param body 正文模板 @param variableNames 必需变量名 @param createdAt 发布时间 */
    public record Template(String id, String key, String subject, String body, List<String> variableNames, Instant createdAt) { }
    /** @param configured 是否存在配置 @param enabled 是否启用 @param host 主机 @param port 端口 @param username SMTP账号 @param fromAddress 发件地址 @param tlsMode 传输模式 @param credentialConfigured 加密凭据是否已配置 */
    public record Mailbox(boolean configured, boolean enabled, String host, Integer port, String username,
            String fromAddress, String tlsMode, boolean credentialConfigured) { }
    /** @param id 投递ID @param notificationId 通知ID @param status 权威状态；accepted仅表示SMTP接受 @param errorCode 安全错误码 @param attempts 尝试次数 @param updatedAt 最近处理时间 */
    public record Delivery(String id, String notificationId, String status, String errorCode, int attempts, Instant updatedAt) { }
    /** @param items 当前页项目 @param hasMore 是否还有下一页 @param nextCursor 下一页ID游标，终页为空 */
    public record Page<T>(List<T> items, boolean hasMore, String nextCursor) { }
    /** @param days 终态材料保留天数，NULL表示关闭；UNKNOWN、可重试及调查保护材料独立受保护 */
    public record Retention(Integer days) { }
}
