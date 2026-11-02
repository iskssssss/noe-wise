package com.skillforge.core.foundation.messaging.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.foundation.messaging.persistence.MailboxRow;
import com.skillforge.core.foundation.application.CryptoService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 真实本机TLS SMTP协议验收，无外部邮件或真实账号；服务器接受、拒绝、失回执各自保留事实。 */
class SmtpNotificationMailTransportTest {
    private static SSLContext original;
    private static SSLContext tls;
    private static Path directory;
    private static CryptoService crypto;

    /** 使用JDK keytool构造仅测试的localhost证书，不关闭生产TLS/主机验证。 */
    @BeforeAll static void certificate() throws Exception {
        original=SSLContext.getDefault(); directory=Files.createTempDirectory("messaging-smtp-test-"); Path store=directory.resolve("smtp.p12");
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        var process=new ProcessBuilder(executable,"-genkeypair","-alias","smtp","-keyalg","RSA","-keysize","2048","-storetype","PKCS12","-keystore",store.toString(),"-storepass","test-only-password","-keypass","test-only-password","-dname","CN=localhost","-ext","SAN=DNS:localhost","-validity","2","-noprompt").redirectErrorStream(true).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS)); assertEquals(0,process.exitValue());
        KeyStore keys=KeyStore.getInstance("PKCS12"); try(var input=Files.newInputStream(store)) { keys.load(input,"test-only-password".toCharArray()); }
        var managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(keys,"test-only-password".toCharArray());
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(keys);
        tls=SSLContext.getInstance("TLS"); tls.init(managers.getKeyManagers(),trust.getTrustManagers(),null); SSLContext.setDefault(tls);
        var properties=new SkillForgeProperties(); properties.setSecretKey("test-only-messaging-master-key-32-bytes"); crypto=new CryptoService(properties);
    }

    /** 恢复JVM TLS默认并移除本测试精确创建的文件。 */
    @AfterAll static void cleanup() throws Exception {
        if(original!=null) SSLContext.setDefault(original);
        if(directory!=null) { Files.deleteIfExists(directory.resolve("smtp.p12")); Files.deleteIfExists(directory); }
    }

    /** SMTP250只记录accepted，不报告最终送达。 */
    @Test void serverAcceptanceIsAcceptedAndEncryptedPasswordWorksThroughRealAuth() throws Exception {
        var result=deliver("accept"); assertEquals("accepted",result.status()); assertNull(result.errorCode());
    }
    /** 服务器明确550拒绝收件人，可以确认没有接受。 */
    @Test void explicitServerRejectionIsRejected() throws Exception { assertEquals("rejected",deliver("reject").status()); }
    /** DATA正文提交后服务器断链，不能认定未发送而盲重试。 */
    @Test void connectionLostAfterDataIsUnknown() throws Exception { assertEquals("unknown",deliver("unknown").status()); }
    /** 租约CAS失败停止于发送命令之前。 */
    @Test void losingLeaseNeverIssuesMailCommand() throws Exception { assertEquals("rejected",deliver("lease_lost").status()); }

    /** 一次测试只建立一个本机TLS连接；协议内容不写测试日志。 */
    private static NotificationMailTransport.Result deliver(String outcome) throws Exception {
        try(var server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress()); var executor=Executors.newSingleThreadExecutor()) {
            AtomicBoolean mailCommand=new AtomicBoolean(); AtomicBoolean callback=new AtomicBoolean();
            var protocol=executor.submit(()->{
                try(var socket=server.accept(); var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)); var writer=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true)) {
                    socket.setSoTimeout(15000); reply(writer,"220 localhost ESMTP"); String line; boolean data=false; int authStep=0;
                    while((line=reader.readLine())!=null) {
                        if(data) { if(line.equals(".")) { data=false; if(outcome.equals("unknown")) break; reply(writer,"250 2.0.0 accepted"); } continue; }
                        if(authStep==1) { authStep=2; reply(writer,"334 UGFzc3dvcmQ6"); continue; }
                        if(authStep==2) { authStep=0; reply(writer,"235 2.7.0 authenticated"); continue; }
                        if(line.startsWith("EHLO")) { reply(writer,"250-localhost"); reply(writer,"250 AUTH LOGIN"); }
                        else if(line.startsWith("AUTH LOGIN")) { authStep=1; reply(writer,"334 VXNlcm5hbWU6"); }
                        else if(line.startsWith("MAIL FROM")) { mailCommand.set(true); reply(writer,"250 2.1.0 sender ok"); }
                        else if(line.startsWith("RCPT TO")) reply(writer,outcome.equals("reject")?"550 5.1.1 recipient rejected":"250 2.1.5 recipient ok");
                        else if(line.equals("DATA")) { data=true; reply(writer,"354 send data"); }
                        else if(line.equals("RSET")) reply(writer,"250 reset");
                        else if(line.equals("QUIT")) { reply(writer,"221 bye"); break; }
                        else reply(writer,"500 unsupported");
                    }
                } catch(Exception failure) { throw new IllegalStateException("本机SMTP夹具失败",failure); }
            });
            var mailbox=new MailboxRow("1","platform",null,"localhost",server.getLocalPort(),"test-user",crypto.encrypt("test-only-password"),"notification@example.test","tls",true,true,Instant.now());
            var result=new SmtpNotificationMailTransport(crypto).deliver(mailbox,"recipient@example.test","测试标题","测试正文",()->{ callback.set(true); if(outcome.equals("lease_lost")) throw new IllegalStateException("lease lost"); });
            protocol.get(20,TimeUnit.SECONDS); assertTrue(callback.get(),"真实TLS认证成功后必须进行租约CAS");
            assertEquals(!outcome.equals("lease_lost"),mailCommand.get()); return result;
        }
    }
    /** 明确CRLF协议行终止，不依赖系统换行。 */
    private static void reply(PrintWriter writer,String line) { writer.print(line+"\r\n"); writer.flush(); }
}
