package com.skillforge.browser;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.browser.BrowserParameters;
import com.skillforge.execution.browser.BrowserReceipt;
import com.skillforge.execution.browser.BrowserRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 持久 task 不能沿用 daemon 重启前的临时 session，也不能把未知读取当作清理证据。 */
class BrowserTaskBindingTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();

    private static class Fake extends BrowserEngineTest.Fake {
        private final ObjectMapper codec=new ObjectMapper();
        JsonNode sessions;
        boolean unavailable;
        String cancelInvocation;
        int snapshots;
        Fake(Path root)throws Exception{super(root);sessions=codec.readTree("[{\"session_id\":\"s1\",\"browser_instance_id\":\"b1\",\"agent_window_id\":7}]");}
        @Override JsonNode run(Path home,List<String> args,int seconds)throws Exception{
            if(args.equals(List.of("--json","session","list"))){
                calls.add(args);snapshots++;assertTrue(seconds<=5);
                if(unavailable)throw new IOException("lost session snapshot");
                if(cancelInvocation!=null){String invocation=cancelInvocation;cancelInvocation=null;cancel("1","2",invocation);}
                return sessions;
            }
            return super.run(home,args,seconds);
        }
    }

    private BrowserRequest start(String invocation){return new BrowserRequest("1","2",invocation,null,"start",new BrowserParameters.StartParameters("b1"),"session","run",Instant.now().plusSeconds(30));}
    private BrowserRequest navigate(String invocation,String task)throws Exception{return new BrowserRequest("1","2",invocation,task,"navigate",BrowserParameters.parse("navigate",json.createObjectNode().put("url","https://example.com")),"session","run",Instant.now().plusSeconds(30));}
    private Path taskFile(Fake fake,String task){return fake.home("1","2").resolve("tasks").resolve(task+".json");}

    @Test void daemonRestartRejectsLostBindingAndPersistsInactiveWithoutRewritingHistory()throws Exception{
        var fake=new Fake(root);BrowserReceipt task=fake.execute(start("start"));fake.sessions=json.readTree("[]");
        BrowserRequest request=navigate("lost",task.task());BrowserReceipt denied=fake.execute(request);
        assertEquals("rejected",denied.status());assertEquals("browser_task_binding_lost",denied.result().path("error").asText());assertFalse(denied.result().path("cleanup_complete").asBoolean());
        BrowserReceipt inactive=fake.read(taskFile(fake,task.task()),BrowserReceipt.class);assertEquals("inactive",inactive.status());assertFalse(inactive.result().path("cleanup_complete").asBoolean());
        assertEquals("active",fake.execute(start("start")).status());assertEquals("active",fake.receipts("1","2").stream().filter(r->r.invocation().equals("start")).findFirst().orElseThrow().status());
        int count=fake.calls.size();assertEquals(denied,fake.execute(request));assertEquals(count,fake.calls.size());
        assertEquals("browser_task_not_active",fake.execute(navigate("again",task.task())).result().path("error").asText());
        assertFalse(fake.calls.stream().anyMatch(c->c.contains("navigate")));
    }

    @Test void sameShortSessionIdCannotRetargetDifferentBrowserOrWindow()throws Exception{
        for(String mismatch:List.of("[{\"session_id\":\"s1\",\"browser_instance_id\":\"other\",\"agent_window_id\":7}]",
                "[{\"session_id\":\"s1\",\"browser_instance_id\":\"b1\",\"agent_window_id\":8}]")){
            var fake=new Fake(root.resolve(mismatch.contains("other")?"browser":"window"));String task=fake.execute(start("start")).task();fake.sessions=json.readTree(mismatch);
            assertEquals("browser_task_binding_lost",fake.execute(navigate("mismatch",task)).result().path("error").asText());
            assertFalse(fake.calls.stream().anyMatch(c->c.contains("navigate")));
        }
    }

    @Test void unreadableBindingKeepsTaskActiveAndInvocationUnknownWithoutReplay()throws Exception{
        var fake=new Fake(root);String task=fake.execute(start("start")).task();fake.unavailable=true;
        BrowserRequest request=navigate("unknown",task);BrowserReceipt unknown=fake.execute(request);
        assertEquals("unknown",unknown.status());assertEquals("browser_task_binding_check_unknown",unknown.result().path("error").asText());
        assertEquals("active",fake.read(taskFile(fake,task),BrowserReceipt.class).status());assertEquals("unknown",fake.taskStates("1","2").getFirst().status());
        int count=fake.calls.size();assertEquals(unknown,fake.execute(request));assertEquals(count,fake.calls.size());
        fake.unavailable=false;assertEquals("succeeded",fake.execute(navigate("explicit-new-attempt",task)).status());
        assertEquals(1,fake.calls.stream().filter(c->c.contains("navigate")).count());
    }

    @Test void ipcWrapperCannotMasqueradeAsOfficialCliArray()throws Exception{
        var fake=new Fake(root);String task=fake.execute(start("start")).task();
        fake.sessions=json.readTree("{\"sessions\":[{\"session_id\":\"s1\",\"browser_instance_id\":\"b1\",\"agent_window_id\":7}]}");
        BrowserReceipt pending=fake.execute(navigate("wrong-wire-shape",task));
        assertEquals("unknown",pending.status());assertEquals("browser_task_binding_check_unknown",pending.result().path("error").asText());
        assertEquals("active",fake.read(taskFile(fake,task),BrowserReceipt.class).status());assertFalse(fake.calls.stream().anyMatch(c->c.contains("navigate")));
    }

    @Test void taskDirectoryProjectsCurrentBindingAndOnlyPublicErrorCleanupFields()throws Exception{
        var fake=new Fake(root);BrowserReceipt task=fake.execute(start("start"));
        var source=json.createObjectNode().put("error","safe_error").put("cleanup_complete",false).put("page_body","private page").put("request_token","secret");
        fake.write(taskFile(fake,task.task()),task.state("active",source));fake.sessions=json.readTree("[]");
        BrowserReceipt projected=fake.taskStates("1","2").getFirst();assertEquals("inactive",projected.status());assertEquals(task.task(),projected.task());
        assertEquals("browser_task_binding_lost",projected.result().path("error").asText());assertEquals(2,projected.result().size());assertNull(projected.requestToken());assertNull(projected.fingerprint());
        assertEquals("active",fake.read(taskFile(fake,task.task()),BrowserReceipt.class).status());assertTrue(fake.taskStates("1","3").isEmpty());
        fake.write(taskFile(fake,task.task()),task.state("unknown",source));BrowserReceipt pending=fake.taskStates("1","2").getFirst();
        assertEquals("unknown",pending.status());assertEquals(2,pending.result().size());assertFalse(pending.result().has("page_body"));
    }

    @Test void confirmedLostTasksDoNotFillLimitButUnknownTasksStillDo()throws Exception{
        var fake=new Fake(root);for(int n=0;n<5;n++)fake.execute(start("start-"+n));
        fake.sessions=json.readTree("[]");assertEquals("active",fake.execute(start("after-restart")).status());
        // 当前目录已明确无临时会话，但实际执行未知的任务不会因一次空列表被降为清理完成。
        for(BrowserReceipt task:fake.taskStates("1","2"))fake.write(taskFile(fake,task.task()),task.state("unknown",json.createObjectNode().put("error","browser_result_unknown")));
        BrowserReceipt limited=fake.execute(start("unknown-limit"));assertEquals("rejected",limited.status());assertEquals("browser_task_limit",limited.result().path("error").asText());
    }

    @Test void limitReadFailureDoesNotStartAnotherSession()throws Exception{
        var fake=new Fake(root);fake.execute(start("start"));int before=fake.calls.size();fake.unavailable=true;
        BrowserReceipt unknown=fake.execute(start("unknown-start"));assertEquals("unknown",unknown.status());assertEquals("browser_task_binding_check_unknown",unknown.result().path("error").asText());
        assertEquals(List.of(List.of("--json","session","list")),fake.calls.subList(before,fake.calls.size()));
    }

    @Test void cancellationDuringBindingReadKeepsCancellationAndDoesNotDispatchPageOperation()throws Exception{
        var fake=new Fake(root);String task=fake.execute(start("start")).task();fake.cancelInvocation="cancel-during-query";
        BrowserReceipt cancelled=fake.execute(navigate("cancel-during-query",task));assertEquals("cancelled",cancelled.status());
        assertTrue(cancelled.result().path("cleanup_complete").asBoolean());assertEquals("stopped",fake.read(taskFile(fake,task),BrowserReceipt.class).status());
        assertFalse(fake.calls.stream().anyMatch(c->c.contains("navigate")));
    }

    @Test void currentTaskEndpointKeepsServiceAuthenticationAndReturnsActualTasks()throws Exception{
        var fake=new Fake(root);String task=fake.execute(start("start")).task();fake.sessions=json.readTree("[]");
        var codec=new ObjectMapper().findAndRegisterModules();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BrowserController(fake,"synthetic-service-token"))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(codec)).build();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/browser/task-states")
                .header("X-Browser-Service-Token","synthetic-service-token").param("tenant","1").param("user","2"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        JsonNode body=codec.readTree(response.getContentAsByteArray());assertEquals(1,body.size());assertEquals(task,body.get(0).path("task").asText());assertEquals("inactive",body.get(0).path("status").asText());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/browser/task-states")
                .header("X-Browser-Service-Token","wrong-token").param("tenant","1").param("user","2"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
}
