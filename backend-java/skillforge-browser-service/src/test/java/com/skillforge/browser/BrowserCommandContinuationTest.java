package com.skillforge.browser;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.browser.BrowserParameters;
import com.skillforge.execution.browser.BrowserReceipt;
import com.skillforge.execution.browser.BrowserRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证确定拒绝与页面变化观察不改变真实操作事实，也不跨个人或 Agent 窗口。 */
class BrowserCommandContinuationTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();

    private static class Fake extends BrowserEngine {
        private final ObjectMapper fixture=new ObjectMapper();
        final List<List<String>> calls=new ArrayList<>();
        final List<Integer> observationBudgets=new ArrayList<>();
        int daemonStarts;
        int observations;
        int failObservation;
        boolean cancelOnAction;
        boolean expireOnAction;
        boolean loseAction;
        Instant deadline;
        BrowserRequest current;
        JsonNode before,after;

        Fake(Path root)throws Exception{super(root.toString(),"bsk","wss://browser.example",new ObjectMapper().findAndRegisterModules());}
        @Override public synchronized int daemon(Path home)throws Exception{Files.createDirectories(home);daemonStarts++;return 52800;}
        @Override synchronized int daemon(Path home,Instant deadline)throws Exception{return daemon(home);}
        @Override JsonNode run(Path home,List<String> args,int seconds)throws Exception{
            calls.add(List.copyOf(args));
            if(args.equals(List.of("--json","daemon","devices")))return fixture.readTree("[{\"browser_id\":\"b1\",\"expires_at\":\"2100-01-01T00:00:00Z\"}]");
            if(args.equals(List.of("--json","session","list")))return fixture.readTree("[{\"session_id\":\"s1\",\"browser_instance_id\":\"b1\",\"agent_window_id\":7}]");
            if(args.contains("tab")&&args.contains("list")){
                observations++;observationBudgets.add(seconds);
                if(observations==failObservation)throw new IOException("lost observation");
                return observations==1?before:after;
            }
            if(args.contains("click")||args.contains("press")){
                if(loseAction)throw new IOException("lost action reply");
                if(cancelOnAction){
                    var cancelled=new BrowserReceipt(null,current.invocation()+":cancel","cancel",current.task(),current.session(),current.run(),Instant.now().toString(),"cancelled",null,"b1",null,fixture.createObjectNode().put("cleanup_complete",true));
                    write(home.resolve("receipts").resolve(hash(current.invocation()+":cancel")+".json"),cancelled);
                    Path receipt=home.resolve("receipts").resolve(hash(current.invocation())+".json");
                    write(receipt,read(receipt,BrowserReceipt.class).state("cancelled",cancelled.result()));
                }
                if(expireOnAction)while(!Instant.now().isAfter(deadline))Thread.sleep(Math.max(1,java.time.Duration.between(Instant.now(),deadline).toMillis()+1));
                return fixture.readTree("{\"ok\":true,\"tab_id\":41,\"click_count\":1}");
            }
            throw new AssertionError("Unexpected command: "+args);
        }
        BrowserReceipt active(String task)throws Exception{
            var state=new BrowserReceipt(null,"start","start",task,"session","run",Instant.now().toString(),"active",null,"b1",new BrowserReceipt.BrowserBinding("s1","b1",7L),null);
            write(home("1","2").resolve("tasks").resolve(task+".json"),state);return state;
        }
    }

    private BrowserRequest request(String invocation,String task,String operation,JsonNode parameters)throws Exception{
        return new BrowserRequest("1","2",invocation,task,operation,BrowserParameters.parse(operation,parameters),"session","run",Instant.now().plusSeconds(30));
    }
    private BrowserRequest click(String invocation,String task)throws Exception{
        return request(invocation,task,"click",json.createObjectNode().put("ref","element-1").put("tab_id",41));
    }
    private void lists(Fake fake)throws Exception{
        fake.before=json.readTree("{\"tabs\":[{\"tab_id\":41,\"window_id\":7,\"scope\":\"agent\",\"url\":\"https://example.com\",\"title\":\"原页\",\"active\":true}]}");
        fake.after=json.readTree("{\"tabs\":[{\"tab_id\":41,\"window_id\":7,\"scope\":\"agent\",\"url\":\"https://example.com\",\"active\":false},{\"tab_id\":42,\"window_id\":7,\"scope\":\"agent\",\"url\":\"https://example.com/detail\",\"title\":\"详情\",\"active\":true,\"page_body\":\"不应输出\"},{\"tab_id\":99,\"window_id\":9,\"scope\":\"agent\",\"url\":\"https://other.example\",\"active\":true},{\"tab_id\":100,\"window_id\":7,\"scope\":\"user\",\"active\":true}]}");
    }

    @Test void invalidMissingAndInactiveTasksPersistRejectionWithoutDaemonOrPageDispatch()throws Exception{
        var fake=new Fake(root);String missing=UUID.randomUUID().toString(),inactive=UUID.randomUUID().toString();
        BrowserReceipt task=fake.active(inactive);fake.write(fake.home("1","2").resolve("tasks").resolve(inactive+".json"),task.state("stopped",null));
        List<String> tasks=List.of("task:rgjn",missing,inactive);
        List<String> errors=List.of("browser_task_invalid","browser_task_not_found","browser_task_not_active");
        for(int n=0;n<tasks.size();n++){
            BrowserRequest request=click("reject-"+n,tasks.get(n));BrowserReceipt rejected=fake.execute(request);
            assertEquals("rejected",rejected.status());assertEquals(errors.get(n),rejected.result().path("error").asText());
            assertEquals(rejected,fake.execute(request));
            assertEquals(rejected.publicView(),fake.receipts("1","2").stream().filter(r->r.invocation().equals(request.invocation())).findFirst().orElseThrow());
        }
        assertEquals(0,fake.daemonStarts);assertTrue(fake.calls.isEmpty());
    }

    @Test void parameterRejectionAndInvocationConflictDoNotOverwriteOriginalEvidence()throws Exception{
        var fake=new Fake(root);String task=fake.active(UUID.randomUUID().toString()).task();
        BrowserRequest invalid=request("bad-parameter",task,"press",json.createObjectNode());BrowserReceipt rejected=fake.execute(invalid);
        assertEquals("rejected",rejected.status());assertEquals("browser_parameter_required",rejected.result().path("error").asText());assertEquals("key",rejected.result().path("parameter").asText());
        BrowserRequest changed=request("bad-parameter",task,"press",json.createObjectNode().put("key","Enter"));
        BrowserReceipt conflict=fake.execute(changed);assertEquals("rejected",conflict.status());assertEquals("browser_invocation_conflict",conflict.result().path("error").asText());
        assertEquals(rejected,fake.execute(invalid));assertEquals(0,fake.daemonStarts);assertTrue(fake.calls.isEmpty());
        assertEquals(1,fake.receipts("1","2").size());
    }

    @Test void invalidTaskReturnsStructuredHttpReceiptInsteadOfServerError()throws Exception{
        var fake=new Fake(root);var codec=new ObjectMapper().findAndRegisterModules();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BrowserController(fake,"synthetic-service-token"))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(codec)).build();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/browser/execute")
                .header("X-Browser-Service-Token","synthetic-service-token").contentType("application/json").content(codec.writeValueAsBytes(click("invalid-http","task:rgjn"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        BrowserReceipt result=codec.readValue(response.getContentAsByteArray(),BrowserReceipt.class);
        assertEquals("rejected",result.status());assertEquals("browser_task_invalid",result.result().path("error").asText());assertNull(result.fingerprint());
        assertEquals(0,fake.daemonStarts);assertTrue(fake.calls.isEmpty());assertEquals(1,fake.receipts("1","2").size());
    }

    @Test void clickRetainsOriginalResultAndAddsOnlyBoundWindowCandidatesWithoutSelectingThem()throws Exception{
        var fake=new Fake(root);lists(fake);String task=fake.active(UUID.randomUUID().toString()).task();BrowserRequest click=click("click",task);
        BrowserReceipt result=fake.execute(click);assertEquals("succeeded",result.status());assertEquals(1,result.result().path("click_count").asInt());
        JsonNode navigation=result.result().path("navigation");assertEquals("observed",navigation.path("status").asText());assertTrue(navigation.path("candidate_only").asBoolean());
        assertEquals(41,navigation.path("source_tab_id").asLong());assertEquals(42,navigation.path("active_tab_id").asLong());assertFalse(navigation.path("source_url_changed").asBoolean());
        assertEquals(1,navigation.path("new_tabs").size());JsonNode added=navigation.path("new_tabs").get(0);
        assertEquals(42,added.path("tab_id").asLong());assertEquals("详情",added.path("title").asText());assertEquals("https://example.com/detail",added.path("url").asText());assertFalse(added.has("page_body"));
        assertEquals(2,fake.observations);assertTrue(fake.observationBudgets.stream().allMatch(b->b<=5));
        assertFalse(fake.calls.stream().anyMatch(c->c.contains("select")||c.contains("borrow")));
        int calls=fake.calls.size();assertEquals(receiptJson(result),receiptJson(fake.execute(click)));assertEquals(calls,fake.calls.size());
    }

    @Test void pressRecognizesSameTabNavigationWithoutInventingNewTab()throws Exception{
        var fake=new Fake(root);lists(fake);fake.after=json.readTree("{\"tabs\":[{\"tab_id\":41,\"window_id\":7,\"scope\":\"agent\",\"url\":\"https://example.com/search\",\"active\":true}]}");
        String task=fake.active(UUID.randomUUID().toString()).task();BrowserReceipt result=fake.execute(request("press",task,"press",json.createObjectNode().put("key","Enter").put("tab_id",41)));
        assertEquals("succeeded",result.status());assertTrue(result.result().path("navigation").path("source_url_changed").asBoolean());assertEquals(0,result.result().path("navigation").path("new_tabs").size());
    }

    @Test void observationFailureCannotTurnSuccessfulClickUnknownOrClaimExistingTabsAsNew()throws Exception{
        for(int stage:List.of(1,2)){
            var fake=new Fake(root.resolve("failure-"+stage));lists(fake);fake.failObservation=stage;String task=fake.active(UUID.randomUUID().toString()).task();BrowserRequest click=click("click",task);
            BrowserReceipt result=fake.execute(click);assertEquals("succeeded",result.status());assertEquals("partial",result.result().path("navigation").path("status").asText());
            assertEquals("browser_navigation_observation_unknown",result.result().path("navigation").path(stage==1?"before_error":"after_error").asText());
            assertEquals(0,result.result().path("navigation").path("new_tabs").size());
            assertEquals(1,fake.calls.stream().filter(c->c.contains("click")).count());
            int calls=fake.calls.size();assertEquals(receiptJson(result),receiptJson(fake.execute(click)));assertEquals(calls,fake.calls.size());
        }
    }

    @Test void cancelledActionDoesNotAppendObservationOrOverwriteCancellation()throws Exception{
        var fake=new Fake(root);lists(fake);String task=fake.active(UUID.randomUUID().toString()).task();fake.current=click("cancelled",task);fake.cancelOnAction=true;
        BrowserReceipt result=fake.execute(fake.current);assertEquals("cancelled",result.status());assertEquals(1,fake.observations);assertFalse(result.result().has("navigation"));
    }

    @Test void malformedCandidateOnlyMarksObservationIncompleteAfterSuccessfulClick()throws Exception{
        var fake=new Fake(root);lists(fake);fake.after=json.readTree("{\"tabs\":[{\"tab_id\":\"bad-id\",\"window_id\":7,\"scope\":\"agent\",\"active\":true}]}");
        String task=fake.active(UUID.randomUUID().toString()).task();BrowserReceipt result=fake.execute(click("bad-candidate",task));
        assertEquals("succeeded",result.status());assertEquals(1,result.result().path("click_count").asInt());
        assertEquals("partial",result.result().path("navigation").path("status").asText());
        assertEquals("browser_navigation_observation_invalid",result.result().path("navigation").path("after_error").asText());assertEquals(0,result.result().path("navigation").path("new_tabs").size());
    }

    @Test void unknownActionHasNoPostObservationAndNeverReplaysOnRetry()throws Exception{
        var fake=new Fake(root);lists(fake);fake.loseAction=true;String task=fake.active(UUID.randomUUID().toString()).task();BrowserRequest click=click("unknown-click",task);
        BrowserReceipt result=fake.execute(click);assertEquals("unknown",result.status());assertEquals(1,fake.observations);assertFalse(result.result().has("navigation"));
        int count=fake.calls.size();assertEquals(result,fake.execute(click));assertEquals(count,fake.calls.size());
    }

    @Test void expiredDeadlineAfterSuccessfulActionSkipsObservationAndPreservesSuccess()throws Exception{
        var fake=new Fake(root);lists(fake);String task=fake.active(UUID.randomUUID().toString()).task();BrowserRequest input=click("deadline",task);
        fake.deadline=Instant.now().plusSeconds(2);fake.expireOnAction=true;
        var bounded=new BrowserRequest(input.tenant(),input.endUser(),input.invocation(),input.task(),input.operation(),input.parameters(),input.session(),input.run(),fake.deadline);
        BrowserReceipt result=fake.execute(bounded);assertEquals("succeeded",result.status());assertEquals(1,fake.observations);
        assertEquals("deadline_exceeded",result.result().path("navigation").path("after_error").asText());
    }

    /** 原始构造的 LongNode 与 JSON 回读的 IntNode 可能不同，持久幂等应比较无损 JSON 事实。 */
    private JsonNode receiptJson(BrowserReceipt receipt)throws IOException{return json.readTree(json.writeValueAsBytes(receipt));}
}
