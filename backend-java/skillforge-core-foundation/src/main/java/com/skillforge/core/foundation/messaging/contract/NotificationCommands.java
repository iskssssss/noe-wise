package com.skillforge.core.foundation.messaging.contract;

import java.util.List;
import java.util.Map;

/** 消息领域公开写入命令；可信作用域由调用业务确认，秘密变量仅进入加密邮件正文。 */
public final class NotificationCommands {
    private NotificationCommands() { }

    /**
     * 与调用方业务事务共同受理一条通知，不表示邮件已经发送。
     * @param scopeKey platform 或 tenant:机构ID
     * @param recipientMemberId 站内收件人；仅邮箱通知可为空
     * @param recipientEmail 已由调用业务确认的邮箱；不从模板变量解析
     * @param deduplicationKey 同作用域稳定业务幂等键
     * @param templateKey 当前已发布模板键
     * @param variables 模板变量；邮件正文受理时加密冻结
     * @param inApp 是否产生站内消息；安全 token 模板禁止开启
     * @param email 是否产生邮件 outbox
     */
    public record Enqueue(String scopeKey, String recipientMemberId, String recipientEmail,
            String deduplicationKey, String templateKey, Map<String, String> variables,
            boolean inApp, boolean email) {
        public Enqueue { variables = variables == null ? Map.of() : Map.copyOf(variables); }
    }

    /**
     * 公告发布范围由成员目录在同租户内解析，受理后冻结收件人。
     * @param title 公告标题
     * @param body 纯文本公告正文
     * @param audience all、members、departments、roles
     * @param recipientIds 所选范围的ID或角色编码；all为空
     * @param email 是否同时尝试邮件通知
     * @param deduplicationKey 发布请求幂等键
     */
    public record Publish(String title, String body, String audience, List<String> recipientIds,
            boolean email, String deduplicationKey) {
        public Publish { recipientIds = recipientIds == null ? List.of() : List.copyOf(recipientIds); }
    }

    /**
     * 完整发布一个模板版本，新通知采用新版本，旧通知继续读取冻结正文。
     * @param key 稳定业务键
     * @param subject 标题模板，变量写为 {{name}}
     * @param body 纯文本正文模板
     * @param variableNames 必须完整提供的变量名
     */
    public record Template(String key, String subject, String body, List<String> variableNames) {
        public Template { variableNames = variableNames == null ? List.of() : List.copyOf(variableNames); }
    }

    /**
     * 邮箱完整配置；密码为空表示沿用当前加密凭据，禁用允许配置为空。
     * @param host SMTP主机名
     * @param port 端口，1至65535
     * @param username SMTP身份
     * @param password 一次性写入的密码，不回显
     * @param fromAddress 发件地址
     * @param tlsMode starttls 或 tls；生产必须加密传输
     * @param enabled 是否受理并投递新邮件
     */
    public record Mailbox(String host, Integer port, String username, String password,
            String fromAddress, String tlsMode, boolean enabled) { }
}
