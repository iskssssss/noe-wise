package com.skillforge.core.foundation.messaging.integration;

import com.skillforge.core.foundation.messaging.persistence.MailboxRow;

/** 邮件协议适配边界；派发前回调提交权威dispatching状态，返回accepted只表示服务器接受。 */
public interface NotificationMailTransport {
    /**
     * 仅发送一个冻结收件人；连接/TLS/auth失败属于未派发拒绝，派发后的不确定结果禁止盲重试。
     * @param mailbox 冻结邮箱版本，密码仍为密文
     * @param recipient 一个已确认邮箱
     * @param subject 已解密标题，仅在调用栈使用
     * @param body 已解密正文，仅在调用栈使用
     * @param beforeDispatch 发送命令前必须执行的租约CAS；失败时不得发送
     * @return 确定接受、确定拒绝或未知及安全错误码
     */
    Result deliver(MailboxRow mailbox,String recipient,String subject,String body,Runnable beforeDispatch);
    /** @param status accepted、rejected、unknown @param errorCode 稳定安全错误码，不包含服务器文本或秘密 */
    record Result(String status,String errorCode) { }
}
