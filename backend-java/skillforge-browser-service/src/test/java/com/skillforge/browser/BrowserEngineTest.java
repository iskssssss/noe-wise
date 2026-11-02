package com.skillforge.browser;
import com.fasterxml.jackson.databind.*;
import com.skillforge.execution.browser.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 聚焦真实持久窗口：绑定先于claim、副作用未知不重放、个人目录隔离。 */
class BrowserEngineTest {
    @Test void actualCliExitPreservesConfirmedDenialButOtherTimeoutStaysUnknown()throws Exception{
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classes=Path.of(BrowserEngineTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var engine=new BrowserEngine(root.toString(),"unused","http://127.0.0.1",json){
            @Override ProcessBuilder process(Path home,List<String> command){return new ProcessBuilder(executable,"-cp",classes,BorrowTimeoutChild.class.getName());}
        };
        var known=assertThrows(BrowserEngine.CliFailure.class,()->engine.run(root,List.of("--json","tab","borrow"),5));
        assertEquals("confirmation_timeout",known.code);
        var unknown=assertThrows(java.io.IOException.class,()->engine.run(root,List.of("--json","navigate"),5));
        assertInstanceOf(BrowserEngine.CliFailure.class,unknown);
        assertEquals("timeout",unknown.getMessage());
        assertEquals("confirmation_timeout",((BrowserEngine.CliFailure)unknown).failure.reason());
        assertTrue(((BrowserEngine.CliFailure)unknown).failure.unknownEffect());
    }
    public static class BorrowTimeoutChild{
        public static void main(String[] args){System.out.println("{\"code\":\"timeout\",\"exit_code\":4,\"data\":{\"reason\":\"confirmation_timeout\"}}");System.exit(4);}
    }
    /** 真实子进程输出经过 CLI 边界后仍保留原因，而不传播错误正文。 */
    @Test void actualCliExitPreservesSafeReasonAndUnknownEffect() throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classes=Path.of(BrowserEngineTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var engine=new BrowserEngine(root.toString(),"unused","http://127.0.0.1",json){
            @Override ProcessBuilder process(Path home,List<String> command){return new ProcessBuilder(executable,"-cp",classes,DetailedFailureChild.class.getName());}
        };
        var known=assertThrows(BrowserEngine.CliFailure.class,()->engine.run(root,List.of("--json","click"),5));
        assertEquals("cdp_failed",known.code);
        assertEquals("input_outcome_unknown",known.failure.reason());
        assertEquals("failed",known.failure.cleanupState());
        assertTrue(known.failure.unknownEffect());
        assertEquals("cdp_failed",known.getMessage());
        assertFalse(json.writeValueAsString(known.failure).contains("private"));
    }
    /** CLI 测试子进程只写合成失败并退出，不连接真实浏览器。 */
    public static class DetailedFailureChild {
        /** 向真实解析入口提供固定协议的错误信封。 */
        public static void main(String[] args){System.out.println("{\"code\":\"cdp_failed\",\"message\":\"private page text\",\"data\":{\"reason\":\"input_outcome_unknown\",\"effect_state\":\"unknown\",\"cleanup_state\":\"failed\"}}");System.exit(4);}
    }
    @Test void onlyExactOfficialBorrowConfirmationTimeoutIsKnownNoBorrowFailure()throws Exception{
        var error=json.readTree("{\"code\":\"timeout\",\"exit_code\":4,\"data\":{\"reason\":\"confirmation_timeout\"}}");
        var borrow=List.of("--json","tab","borrow","--session","s1","--tab-id=7","--timeout=15s");
        assertTrue(BrowserEngine.confirmedBorrowTimeout(error,4,borrow));
        assertFalse(BrowserEngine.confirmedBorrowTimeout(error,1,borrow));
        assertFalse(BrowserEngine.confirmedBorrowTimeout(error,4,List.of("--json","navigate")));
        assertFalse(BrowserEngine.confirmedBorrowTimeout(json.readTree("{\"code\":\"timeout\"}"),4,borrow));
        assertFalse(BrowserEngine.confirmedBorrowTimeout(json.readTree("{\"code\":\"timeout\",\"data\":{\"reason\":\"cancel_cleanup_timeout\"}}"),4,borrow));
    }
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    static class Fake extends BrowserEngine {
        final List<List<String>> calls=new ArrayList<>();boolean loseNavigation;boolean authorizationUnavailable;int authorizationQueries;int expireOnAuthorizationQuery;java.time.Instant authorizationDeadline;final List<Integer> authorizationBudgets=new ArrayList<>();String rejectCode;BrowserFailure rejectFailure,cleanupFailure;boolean evaluationFailed;String cancelPhase="closed";
        Fake(Path root)throws Exception{super(root.toString(),"bsk","wss://browser.example",new ObjectMapper().findAndRegisterModules());
            Path authorization=home("1","2").resolve("remote-authorization.json");
            if(!Files.exists(authorization)){Files.createDirectories(authorization.getParent());Files.writeString(authorization,"{\"devices\":{\"fixture\":{\"device_id\":\"fixture\",\"browser_id\":\"b1\",\"expires_at\":\"2100-01-01T00:00:00Z\"}}}");}
        }
        @Override public synchronized int daemon(Path home)throws Exception{Files.createDirectories(home);return 52800;}
        @Override synchronized int daemon(Path home,java.time.Instant deadline)throws Exception{return daemon(home);}
        @Override JsonNode run(Path home,List<String> args,int seconds)throws Exception{
            if(args.equals(List.of("--json","daemon","devices"))){
                authorizationBudgets.add(seconds);authorizationQueries++;
                if(authorizationQueries==expireOnAuthorizationQuery){
                    long remaining=java.time.Duration.between(java.time.Instant.now(),authorizationDeadline).toMillis();
                    if(remaining>=0)Thread.sleep(remaining+20);
                }
                if(authorizationUnavailable)throw new java.io.IOException("authorization unavailable");
                JsonNode devices=new ObjectMapper().readTree(Files.readString(home.resolve("remote-authorization.json"))).path("devices");
                var grants=new ObjectMapper().createArrayNode();devices.forEach(grants::add);return grants;
            }
            calls.add(args);
            if(args.equals(List.of("--json","session","list")))return new ObjectMapper().readTree("[{\"session_id\":\"s1\",\"browser_instance_id\":\"b1\",\"agent_window_id\":7}]");
            if(rejectFailure!=null&&args.contains("navigate"))throw new CliFailure(rejectFailure);
            if(rejectCode!=null&&args.contains("navigate"))throw new CliFailure(rejectCode);
            if(evaluationFailed&&args.contains("evaluate"))return new ObjectMapper().readTree("{\"ok\":false}");
            if(args.contains("navigate")&&loseNavigation)throw new java.io.IOException("lost reply");
            if(args.contains("--cancel")){if(cleanupFailure!=null)throw new CliFailure(cleanupFailure);return new ObjectMapper().createObjectNode().put("state",cancelPhase);}
            if(args.contains("start"))return new ObjectMapper().readTree("{\"session_id\":\"s1\",\"browser_instance_id\":\"b1\",\"agent_window_id\":7}");
            return new ObjectMapper().readTree("{\"ok\":true}");
        }
    }
    BrowserRequest start(String user){return new BrowserRequest("1",user,"start-1",null,"start",new BrowserParameters.StartParameters("b1"),"session-1","run-1",java.time.Instant.now().plusSeconds(120));}
    @Test void missingSelectionIsDurableRejectionWithoutBrowserDispatch()throws Exception{
        var fake=new Fake(root);
        var request=new BrowserRequest("1","2","unselected-start",null,"start",new BrowserParameters.StartParameters(null),"session-1","run-1",java.time.Instant.now().plusSeconds(120));
        var result=fake.execute(request);
        assertEquals("rejected",result.status());
        assertEquals("browser_selection_required",result.result().path("error").asText());
        assertTrue(result.result().path("message").asText().contains("默认浏览器"));
        assertNull(result.task());assertNull(result.binding());assertNull(result.requestToken());
        assertTrue(fake.calls.isEmpty());assertEquals(0,fake.authorizationQueries);
        assertFalse(Files.exists(fake.home("1","2").resolve("tasks")));
        assertEquals(result.publicView(),fake.receipts("1","2").getFirst());
        assertTrue(fake.receipts("1","3").isEmpty());
        // 即使之后选中设备，原调用也只读拒绝回执；用户的新操作必须使用新 invocation。
        fake.write(fake.home("1","2").resolve("device-preferences.json"),new BrowserEngine.DevicePreferences("b1",List.of()));
        var restarted=new Fake(root);
        assertEquals(result,restarted.execute(request));assertTrue(restarted.calls.isEmpty());
        var next=new BrowserRequest("1","2","selected-start",null,"start",request.parameters(),"session-1","run-1",java.time.Instant.now().plusSeconds(120));
        assertEquals("active",restarted.execute(next).status());assertFalse(restarted.calls.isEmpty());
    }
    @Test void unusableExplicitSelectionIsRejectedWithoutFallingBackToDefault()throws Exception{
        var fake=new Fake(root);
        fake.write(fake.home("1","2").resolve("device-preferences.json"),new BrowserEngine.DevicePreferences("b1",List.of()));
        for(String browser:List.of(""," ","--session")){
            var request=new BrowserRequest("1","2","invalid-start-"+browser,null,"start",new BrowserParameters.StartParameters(browser),"session-1","run-1",java.time.Instant.now().plusSeconds(120));
            var result=fake.execute(request);
            assertEquals("rejected",result.status());assertEquals("browser_selection_required",result.result().path("error").asText());
        }
        assertTrue(fake.calls.isEmpty());assertEquals(0,fake.authorizationQueries);
    }
    @Test void expiredGrantInOfficialDeviceListDoesNotPermitDispatch()throws Exception{
        var fake=new Fake(root);
        Files.writeString(fake.home("1","2").resolve("remote-authorization.json"),"{\"devices\":{\"fixture\":{\"device_id\":\"fixture\",\"browser_id\":\"b1\",\"expires_at\":\"2000-01-01T00:00:00Z\"}}}");
        var result=fake.execute(start("2"));assertEquals("failed",result.status());
        assertEquals("browser_not_authorized",result.result().path("error").asText());assertTrue(fake.calls.isEmpty());
    }
    @Test void invalidPublicGrantExpiryIsUnknownWithoutDispatch()throws Exception{
        var fake=new Fake(root);
        Files.writeString(fake.home("1","2").resolve("remote-authorization.json"),"{\"devices\":{\"fixture\":{\"device_id\":\"fixture\",\"browser_id\":\"b1\",\"expires_at\":\"invalid\"}}}");
        var result=fake.execute(start("2"));assertEquals("unknown",result.status());
        assertEquals("browser_authorization_result_unknown",result.result().path("error").asText());assertTrue(fake.calls.isEmpty());
    }
    @Test void revokedConnectedBrowserRejectsNewEffectsButAllowsReplayAndCleanup()throws Exception{
        var fake=new Fake(root);var task=fake.execute(start("2"));
        Files.writeString(fake.home("1","2").resolve("remote-authorization.json"),"{\"devices\":{}}");
        int before=fake.calls.size();assertEquals("active",fake.execute(start("2")).status());
        var deniedStart=fake.execute(new BrowserRequest("1","2","new-start",null,"start",new BrowserParameters.StartParameters("b1"),"session-1","run-1",java.time.Instant.now().plusSeconds(120)));
        assertEquals("failed",deniedStart.status());assertEquals("browser_not_authorized",deniedStart.result().path("error").asText());
        var denied=fake.execute(new BrowserRequest("1","2","new-navigation",task.task(),"navigate",BrowserParameters.parse("navigate",json.createObjectNode().put("url","https://example.com")),"session-1","run-1",java.time.Instant.now().plusSeconds(120)));
        assertEquals("failed",denied.status());assertEquals("browser_not_authorized",denied.result().path("error").asText());assertEquals(before,fake.calls.size());
        assertEquals("cancelled",fake.cancel("1","2","start-1").status());assertTrue(fake.calls.getLast().contains("--cancel"));
    }
    @Test void revokedBrowserStillAllowsExplicitStop()throws Exception{
        var fake=new Fake(root);var task=fake.execute(start("2"));
        Files.writeString(fake.home("1","2").resolve("remote-authorization.json"),"{\"devices\":{}}");
        var stopped=fake.execute(new BrowserRequest("1","2","cleanup",task.task(),"stop",BrowserParameters.parse("stop",json.createObjectNode()),"session-1","run-1",java.time.Instant.now().plusSeconds(120)));
        assertEquals("succeeded",stopped.status());assertEquals(List.of("--json","session","stop","s1"),fake.calls.getLast());
    }
    @Test void authorizationReadFailureIsUnknownAndNeverDispatchesStart()throws Exception{
        var fake=new Fake(root);fake.authorizationUnavailable=true;
        var result=fake.execute(start("2"));assertEquals("unknown",result.status());
        assertEquals("browser_authorization_result_unknown",result.result().path("error").asText());assertTrue(fake.calls.isEmpty());
    }
    @Test void authorizationQueryConsumesOriginalDeadlineBeforeNavigationDispatch()throws Exception{
        var fake=new Fake(root);var task=fake.execute(start("2"));int before=fake.calls.size();
        var deadline=java.time.Instant.now().plusMillis(200);fake.authorizationDeadline=deadline;
        fake.expireOnAuthorizationQuery=fake.authorizationQueries+2;
        var request=new BrowserRequest("1","2","deadline-navigation",task.task(),"navigate",BrowserParameters.parse("navigate",json.createObjectNode().put("url","https://example.com")),"session-1","run-1",deadline);
        var result=fake.execute(request);assertEquals("failed",result.status());assertEquals("deadline_exceeded",result.result().path("error").asText());
        assertEquals(List.of(List.of("--json","session","list")),fake.calls.subList(before,fake.calls.size()));assertEquals(1,fake.authorizationBudgets.getLast());
    }
    @Test void authorizationQueryConsumesOriginalDeadlineBeforeStartDispatch()throws Exception{
        var fake=new Fake(root);var deadline=java.time.Instant.now().plusMillis(200);
        fake.authorizationDeadline=deadline;fake.expireOnAuthorizationQuery=1;
        var request=new BrowserRequest("1","2","deadline-start",null,"start",new BrowserParameters.StartParameters("b1"),"session-1","run-1",deadline);
        var result=fake.execute(request);assertEquals("failed",result.status());assertEquals("deadline_exceeded",result.result().path("error").asText());
        assertTrue(fake.calls.isEmpty());assertEquals(1,fake.authorizationBudgets.getLast());
    }
    @Test void revokedPendingStartCannotRecoverThroughConnectedDaemon()throws Exception{
        var fake=new Fake(root);var task=fake.execute(start("2"));
        Path receipt=fake.home("1","2").resolve("receipts");
        try(var files=Files.list(receipt)){Path file=files.filter(p->p.toString().endsWith(".json")).findFirst().orElseThrow();Files.writeString(file,json.writeValueAsString(task.state("starting",null)));}
        Files.writeString(fake.home("1","2").resolve("remote-authorization.json"),"{\"devices\":{}}");
        int before=fake.calls.size();assertEquals("failed",fake.execute(start("2")).status());assertEquals(before,fake.calls.size());
    }
    @Test void bindsBeforeClaimAndReturnsStableTask()throws Exception{
        var fake=new Fake(root);var first=fake.execute(start("2"));assertEquals("active",first.status());
        int count=fake.calls.size();assertEquals(first.task(),fake.execute(start("2")).task());assertEquals(count,fake.calls.size());
        assertEquals("--prepare",fake.calls.getFirst().getLast());assertEquals("--claim",fake.calls.getLast().getLast());
        assertTrue(Files.exists(fake.home("1","2").resolve("tasks").resolve(first.task()+".json")));
    }
    @Test void unknownSideEffectNeverReplaysAcrossRestart()throws Exception{
        var fake=new Fake(root);var task=fake.execute(start("2"));fake.loseNavigation=true;
        var call=new BrowserRequest("1","2","call-1",task.task(),"navigate",BrowserParameters.parse("navigate",json.createObjectNode().put("url","https://example.com")),"session-1","run-1",java.time.Instant.now().plusSeconds(120));
        assertEquals("unknown",fake.execute(call).status());var restarted=new Fake(root);
        assertEquals("unknown",restarted.execute(call).status());assertTrue(restarted.calls.isEmpty());
        var otherOwner=restarted.execute(new BrowserRequest("1","3","call-1",task.task(),"navigate",call.parameters(),"session-1","run-1",java.time.Instant.now().plusSeconds(120)));
        assertEquals("rejected",otherOwner.status());assertEquals("browser_task_not_found",otherOwner.result().path("error").asText());assertTrue(restarted.calls.isEmpty());
    }
    /** 上游明确未知副作用时，错误原因随原回执持久化，重启查询不重新派发。 */
    @Test void explicitUnknownEffectSurvivesRestartWithoutReplaying() throws Exception {
        var fake=new Fake(root);var task=fake.execute(start("2"));
        fake.rejectFailure=new BrowserFailure("cdp_failed","input_outcome_unknown","unknown","failed");
        var call=new BrowserRequest("1","2","effect-unknown",task.task(),"navigate",
                BrowserParameters.parse("navigate",json.createObjectNode().put("url","https://example.invalid")),
                "session-1","run-1",java.time.Instant.now().plusSeconds(30));
        var result=fake.execute(call);
        assertEquals("unknown",result.status());
        assertEquals("input_outcome_unknown",result.result().path("reason").asText());
        assertEquals("unknown",result.result().path("effect_state").asText());
        var restarted=new Fake(root);
        assertEquals(result,restarted.execute(call));
        assertTrue(restarted.calls.isEmpty());
    }
    @Test void rejectsSameInvocationDifferentArguments()throws Exception{
        var fake=new Fake(root);fake.execute(start("2"));var changed=new BrowserRequest("1","2","start-1",null,"start",new BrowserParameters.StartParameters("another"),"session-1","run-1",java.time.Instant.now().plusSeconds(120));
        var conflict=fake.execute(changed);assertEquals("rejected",conflict.status());assertEquals("browser_invocation_conflict",conflict.result().path("error").asText());
    }
    @Test void knownRejectionsAndScriptFailureAreNotSuccess()throws Exception{
        var fake=new Fake(root);var task=fake.execute(start("2"));fake.rejectCode="permission_denied";
        var call=new BrowserRequest("1","2","reject",task.task(),"navigate",BrowserParameters.parse("navigate",json.createObjectNode().put("url","https://example.com")),"session-1","run-1",java.time.Instant.now().plusSeconds(10));
        assertEquals("failed",fake.execute(call).status());fake.rejectCode=null;fake.evaluationFailed=true;
        var evaluate=new BrowserRequest("1","2","script-fail",task.task(),"evaluate",BrowserParameters.parse("evaluate",json.createObjectNode().put("expression","throw 1")),"session-1","run-1",java.time.Instant.now().plusSeconds(10));
        assertEquals("failed",fake.execute(evaluate).status());
    }
    /** 取消超时或协议异常仍是清理未知，不能因安全错误投影变成确定清理失败。 */
    @Test void cancellationKeepsUnknownCleanupDistinctFromConfirmedFailure() throws Exception {
        for(String code:List.of("timeout","protocol_error","permission_denied")) {
            var fake=new Fake(root.resolve(code));fake.execute(start("2"));
            fake.cleanupFailure=new BrowserFailure(code,null,null,null);
            var cancellation=fake.cancel("1","2","start-1");
            assertEquals(code.equals("permission_denied")?"cleanup_failed":"unknown",cancellation.status());
            assertEquals(code,cancellation.result().path("error").asText());
            assertFalse(cancellation.result().has("cleanup_complete"));
        }
    }
    @Test void cancellationIsDurableAndTargetsOnlyOriginalBinding()throws Exception{
        var fake=new Fake(root);var task=fake.execute(start("2"));var cancelled=fake.cancel("1","2","start-1");
        assertEquals("cancelled",cancelled.status());assertEquals("start-1:cancel",cancelled.invocation());
        assertFalse(fake.calls.stream().anyMatch(c->c.equals(List.of("--json","session","stop","s1"))));
        assertTrue(fake.calls.stream().anyMatch(c->c.contains("--cancel")));
        int count=fake.calls.size();assertEquals("cancelled",fake.cancel("1","2","start-1").status());assertEquals(count,fake.calls.size());
        assertEquals("cancelled",new Fake(root).execute(start("2")).status());
        assertThrows(java.io.IOException.class,()->fake.cancel("1","3","start-1"));
    }
    @Test void expiredDeadlineDoesNotDispatch()throws Exception{
        var fake=new Fake(root);var req=new BrowserRequest("1","2","expired",null,"start",new BrowserParameters.StartParameters("b1"),"session-1","run-1",java.time.Instant.now().minusSeconds(1));
        assertThrows(BrowserEngine.CliFailure.class,()->fake.execute(req));assertTrue(fake.calls.isEmpty());
    }
    @Test void personalActiveTaskLimitIsFive()throws Exception{
        var fake=new Fake(root);
        for(int n=0;n<5;n++)fake.execute(new BrowserRequest("1","2","start-"+n,null,"start",new BrowserParameters.StartParameters("b1"),"session-1","run-1",java.time.Instant.now().plusSeconds(120)));
        var rejected=fake.execute(new BrowserRequest("1","2","sixth",null,"start",new BrowserParameters.StartParameters("b1"),"session-1","run-1",java.time.Instant.now().plusSeconds(120)));
        assertEquals("rejected",rejected.status());assertEquals("browser_task_limit",rejected.result().path("error").asText());
    }

    @Test void cancellationSnapshotMustProveCleanupComplete()throws Exception{
        var fake=new Fake(root);fake.execute(start("2"));fake.cancelPhase="cancelling";
        assertEquals("unknown",fake.cancel("1","2","start-1").status());
        fake.cancelPhase="cleanup_failed";assertEquals("cleanup_failed",fake.cancel("1","2","start-1").status());
        fake.cancelPhase="closed";assertEquals("cancelled",fake.cancel("1","2","start-1").status());
    }

    /** 用真实子进程证明 stdin EOF 能执行清理再退出，同时保留未被选中的进程。 */
    @Test void ownedCancellationClosesPipeBeforeWaitingAndDoesNotKillOtherCommand()throws Exception{
        var engine=new Fake(root);
        assertEquals("1",engine.process(root,List.of("bsk")).environment().get("BSK_CANCEL_ON_STDIN_CLOSE"));
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classes=Path.of(BrowserEngineTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process owned=new ProcessBuilder(executable,"-cp",classes,PipeChild.class.getName()).start();
        Process survivor=new ProcessBuilder(executable,"-cp",classes,PipeChild.class.getName()).start();
        try{
            var ownedOutput=new java.io.BufferedReader(new java.io.InputStreamReader(owned.getInputStream()));
            var survivorOutput=new java.io.BufferedReader(new java.io.InputStreamReader(survivor.getInputStream()));
            assertEquals("ready",java.util.concurrent.CompletableFuture.supplyAsync(()->readLine(ownedOutput)).get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("ready",java.util.concurrent.CompletableFuture.supplyAsync(()->readLine(survivorOutput)).get(5,java.util.concurrent.TimeUnit.SECONDS));
            BrowserEngine.cancelOwnedProcesses(List.of(owned));
            assertFalse(owned.isAlive());assertEquals(0,owned.exitValue());assertEquals("cleanup_complete",ownedOutput.readLine());
            assertTrue(survivor.isAlive());
        }finally{
            for(Process child:List.of(owned,survivor)){
                child.getOutputStream().close();
                if(!child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();assertTrue(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));}
            }
        }
    }

    /** 等两条输出读取和 waitFor 都已开始后再中断，不靠延迟猜测进程是否启动。 */
    @Test void interruptedRunClosesChildAndDrainsBothOutputReaders()throws Exception{
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classes=Path.of(BrowserEngineTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process actual=new ProcessBuilder(executable,"-cp",classes,PipeChild.class.getName()).start();
        var readersFinished=new java.util.concurrent.CountDownLatch(2);
        var child=new ObservedProcess(actual,readersFinished);
        ProcessBuilder builder=org.mockito.Mockito.mock(ProcessBuilder.class);
        org.mockito.Mockito.when(builder.start()).thenReturn(child);
        var engine=new BrowserEngine(root.toString(),"bsk","wss://browser.example",json){
            @Override ProcessBuilder process(Path home,List<String> command){return builder;}
        };
        var outcome=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread caller=new Thread(()->{
            try{engine.run(root,List.of("--json","navigate","--session","s1"),30);}
            catch(Throwable failure){outcome.set(failure);}
        },"browser-interrupted-run-test");
        try{
            caller.start();
            assertTrue(child.stdout.reading.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(child.stderr.reading.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(child.waiting.await(5,java.util.concurrent.TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(10000);
            assertFalse(caller.isAlive(),"被中断调用必须在有界清理后返回");
            assertInstanceOf(InterruptedException.class,outcome.get());
            assertAll(
                    ()->assertFalse(actual.isAlive(),"中断返回不能留下仍在运行的命令进程"),
                    ()->assertTrue(readersFinished.await(5,java.util.concurrent.TimeUnit.SECONDS),"两条输出读取必须都退出并关闭流"));
            assertEquals(0,actual.exitValue(),"应通过 stdin EOF 完成清理，不依赖强制杀进程");
        }finally{
            actual.getOutputStream().close();
            if(!actual.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){
                actual.destroyForcibly();assertTrue(actual.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));
            }
            child.stdout.close();child.stderr.close();
            caller.interrupt();caller.join(5000);assertFalse(caller.isAlive());
        }
    }

    private static final class ObservedInput extends java.io.FilterInputStream{
        final java.util.concurrent.CountDownLatch reading=new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch finished;
        private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        ObservedInput(java.io.InputStream input,java.util.concurrent.CountDownLatch finished){super(input);this.finished=finished;}
        @Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException{
            reading.countDown();return super.read(bytes,offset,length);
        }
        @Override public void close()throws java.io.IOException{
            try{super.close();}finally{if(closed.compareAndSet(false,true))finished.countDown();}
        }
    }
    private static final class ObservedProcess extends Process{
        final Process delegate;
        final ObservedInput stdout,stderr;
        final java.util.concurrent.CountDownLatch waiting=new java.util.concurrent.CountDownLatch(1);
        ObservedProcess(Process delegate,java.util.concurrent.CountDownLatch finished){
            this.delegate=delegate;stdout=new ObservedInput(delegate.getInputStream(),finished);stderr=new ObservedInput(delegate.getErrorStream(),finished);
        }
        @Override public java.io.OutputStream getOutputStream(){return delegate.getOutputStream();}
        @Override public java.io.InputStream getInputStream(){return stdout;}
        @Override public java.io.InputStream getErrorStream(){return stderr;}
        @Override public int waitFor()throws InterruptedException{return delegate.waitFor();}
        @Override public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit)throws InterruptedException{waiting.countDown();return delegate.waitFor(timeout,unit);}
        @Override public int exitValue(){return delegate.exitValue();}
        @Override public boolean isAlive(){return delegate.isAlive();}
        @Override public void destroy(){delegate.destroy();}
        @Override public Process destroyForcibly(){delegate.destroyForcibly();return this;}
    }
    @Test void onlyExactRecordingCleanupRaceRetainsTrace()throws Exception{
        var engine=new Fake(root);Path output=root.resolve("trace");
        var success=json.createObjectNode().put("output",output.toString()).put("trace_json",output.resolve("trace.json").toString())
                .put("trace_version",3).put("window_closed",true);success.set("trace",json.createObjectNode().put("version",3));
        String stopping="{\"code\":\"timeout\",\"message\":\"session stop is already in progress\",\"exit_code\":4}";
        List<String> args=List.of("--json","record","stop","--output",output.toString());
        assertEquals(success,engine.recordingCleanupPending(success+"\n"+stopping,4,args));
        assertNull(engine.recordingCleanupPending(success+"\n"+stopping,1,args));
        assertNull(engine.recordingCleanupPending(success+"\n"+stopping,4,List.of("--json","debug","stop","--output",output.toString())));
        assertNull(engine.recordingCleanupPending(success+"\n"+stopping.replace("timeout","not_found"),4,args));
        assertNull(engine.recordingCleanupPending(success+"\n"+stopping+"\n{}",4,args));
        assertNull(engine.recordingCleanupPending(success.toString(),4,args));
    }
    @Test void recordingCleanupRequiresExactSuccessfulPeerAndMatchingTrace()throws Exception{
        var engine=new Fake(root);Path home=engine.home("1","2"),output=home.resolve("outputs/record"),first=home.resolve("receipts/start.json"),second=home.resolve("receipts/stop.json");
        var trace=json.createObjectNode().put("trace_json",output.resolve("trace.json").toString()).put("trace_version",3).put("window_closed",true);
        trace.set("trace",json.createObjectNode().put("version",3).put("purpose","same recording"));
        var pending=trace.deepCopy().put("error","browser_recording_cleanup_pending");
        var binding=new BrowserReceipt.BrowserBinding("s1","b1",7L);
        var start=new BrowserReceipt(null,"record-start","record-start","task","session","run","now","unknown",null,"b1",binding,pending);
        var stop=new BrowserReceipt(null,"record-stop","record-stop","task","session","run","now","unknown",null,"b1",binding,trace);
        engine.write(home.resolve("active-recording.json"),new BrowserEngine.RecordingBinding(first.toString(),second.toString(),"task",output.toString()));
        engine.write(first,start);engine.write(second,stop);engine.reconcileRecording(home);
        assertEquals("unknown",engine.read(first,BrowserReceipt.class).status());
        var different=trace.deepCopy();different.set("trace",json.createObjectNode().put("version",3).put("purpose","another recording"));
        engine.write(second,stop.state("succeeded",different));engine.reconcileRecording(home);
        assertEquals("unknown",engine.read(first,BrowserReceipt.class).status());
        engine.write(second,stop.state("succeeded",trace));engine.reconcileRecording(home);
        var confirmed=engine.read(first,BrowserReceipt.class);assertEquals("succeeded",confirmed.status());
        assertEquals("record-stop",confirmed.result().path("cleanup_confirmed_by").asText());assertFalse(confirmed.result().has("error"));
        // 两端互换完成顺序仍用真实 exit-0 端确认，不用已有派生成功串联确认。
        engine.write(first,start.state("succeeded",trace));engine.write(second,stop.state("unknown",pending));engine.reconcileRecording(home);
        assertEquals("record-start",engine.read(second,BrowserReceipt.class).result().path("cleanup_confirmed_by").asText());
    }

    private static String readLine(java.io.BufferedReader reader){try{return reader.readLine();}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}}
    public static class PipeChild{
        public static void main(String[] args)throws Exception{
            System.out.println("ready");System.out.flush();
            while(System.in.read()!=-1){}
            System.out.println("cleanup_complete");System.out.flush();
        }
    }

}
