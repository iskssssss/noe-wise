package com.skillforge.core.foundation.messaging.integration;

import com.skillforge.core.foundation.messaging.persistence.MailboxRow;
import com.skillforge.core.foundation.application.CryptoService;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;

/** 生产Jakarta Mail SMTP适配器；强制TLS和证书主机验证，有限超时，无协议调试和自动重发。 */
public class SmtpNotificationMailTransport implements NotificationMailTransport {
    private final CryptoService crypto;

    /** 凭据只在该发送边界解密。 */
    public SmtpNotificationMailTransport(CryptoService crypto) { this.crypto=crypto; }

    /** {@inheritDoc} */
    @Override
    public Result deliver(MailboxRow mailbox,String recipient,String subject,String body,Runnable beforeDispatch) {
        boolean dispatched=false; Transport transport=null;
        try {
            Properties properties=new Properties();
            properties.setProperty("mail.smtp.auth","true");
            properties.setProperty("mail.smtp.connectiontimeout","5000");
            properties.setProperty("mail.smtp.timeout","10000");
            properties.setProperty("mail.smtp.writetimeout","10000");
            properties.setProperty("mail.smtp.ssl.checkserveridentity","true");
            if(mailbox.tlsMode().equals("tls")) properties.setProperty("mail.smtp.ssl.enable","true");
            else { properties.setProperty("mail.smtp.starttls.enable","true"); properties.setProperty("mail.smtp.starttls.required","true"); }
            Session session=Session.getInstance(properties);
            MimeMessage message=new MimeMessage(session);
            message.setFrom(new InternetAddress(mailbox.fromAddress(),true));
            message.setRecipient(Message.RecipientType.TO,new InternetAddress(recipient,true));
            message.setSubject(subject,StandardCharsets.UTF_8.name());
            message.setText(body,StandardCharsets.UTF_8.name()); message.setSentDate(new Date());
            transport=session.getTransport("smtp");
            transport.connect(mailbox.host(),mailbox.port(),mailbox.username(),crypto.decrypt(mailbox.passwordCipher()));
            // 在连接和认证成功后提交dispatching，任何CAS失败均必须停止发送。
            beforeDispatch.run(); dispatched=true;
            transport.sendMessage(message,message.getAllRecipients());
            return new Result("accepted",null);
        } catch(MessagingException failure) {
            if(!dispatched) return new Result("rejected","SMTP_PRE_DISPATCH_REJECTED");
            // 只依据真实SMTP否定响应认定未接受；超时、断链、异常文本不能作为重试依据。
            if(definitiveRejection(failure)) return new Result("rejected","SMTP_SERVER_REJECTED");
            return new Result("unknown","SMTP_RESULT_UNKNOWN");
        } catch(RuntimeException failure) {
            return new Result(dispatched?"unknown":"rejected",dispatched?"SMTP_RESULT_UNKNOWN":"SMTP_PRE_DISPATCH_REJECTED");
        } finally {
            // QUIT失败不能抹去服务器已接受事实，也不触发重发。
            if(transport!=null && transport.isConnected()) try { transport.close(); } catch(MessagingException ignored) { }
        }
    }

    /** 单收件人SMTP明确4xx/5xx响应为未接受，其他错误保留unknown。 */
    private static boolean definitiveRejection(MessagingException failure) {
        Exception current=failure;
        for(int depth=0;current!=null && depth<10;depth++) {
            if(current instanceof SMTPSendFailedException smtp && smtp.getReturnCode()>=400 && smtp.getReturnCode()<=599
                    && (smtp.getValidSentAddresses()==null || smtp.getValidSentAddresses().length==0)) return true;
            if(current instanceof SMTPAddressFailedException smtp && smtp.getReturnCode()>=400 && smtp.getReturnCode()<=599) return true;
            current=current instanceof MessagingException mail?mail.getNextException():null;
        }
        return false;
    }
}
