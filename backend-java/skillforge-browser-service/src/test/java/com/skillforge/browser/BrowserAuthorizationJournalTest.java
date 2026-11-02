package com.skillforge.browser;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserAuthorizationJournalTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();

    @Test void immutableObservationsSurviveRestartAndNeverAttributeAnExternalRemoval() throws Exception {
        String devices = "[{\"device_id\":\"chrome-device\",\"browser_id\":\"chrome\",\"expires_at\":\"2100-01-01T00:00:00Z\",\"label\":\"PRIVATE_LABEL\",\"token\":\"PRIVATE_TOKEN\"}]";
        var journal = new BrowserAuthorizationJournal(json);
        journal.observe(root, json.readTree(devices), null, "devices_read");
        new BrowserAuthorizationJournal(json).observe(root, json.readTree(devices), null, "devices_read");
        assertEquals(1, events(root).size());
        new BrowserAuthorizationJournal(json).observe(root, json.createArrayNode(), null, "devices_read");
        var change = events(root).stream().filter(e -> e.path("phase").asText().equals("changed")).findFirst().orElseThrow();
        assertEquals("grants", change.path("operation").asText());
        assertEquals(BrowserEngine.hash("chrome-device"), change.path("removed").get(0).asText());
        assertTrue(change.path("requestId").isNull());
        String all=events(root).toString();assertFalse(all.contains("PRIVATE"));assertFalse(all.contains("chrome-device"));
    }

    @Test void controlIntentPrecedesDispatchAndUnknownNeverClaimsRevokeCompleted() throws Exception {
        String requestId=UUID.randomUUID().toString();
        var engine=new BrowserEngine(root.toString(),"unused","ws://127.0.0.1",json){
            @Override public synchronized int daemon(Path home)throws Exception{Files.createDirectories(home);return 52800;}
            @Override JsonNode run(Path home,List<String> args,int seconds)throws Exception{
                if(args.contains("devices"))return json.createArrayNode();
                var intent=events(home).stream().filter(e->e.path("phase").asText().equals("started")).findFirst().orElseThrow();
                assertEquals(requestId,intent.path("requestId").asText());
                throw new IOException("PRIVATE_CREDENTIAL");
            }
        };
        assertThrows(IOException.class,()->engine.device("1","2","revoke","owned-device",requestId));
        var all=events(engine.home("1","2"));
        assertEquals(1,all.stream().filter(e->e.path("phase").asText().equals("unknown")).count());
        assertFalse(all.stream().anyMatch(e->e.path("phase").asText().equals("succeeded")));
        assertFalse(all.toString().contains("PRIVATE_CREDENTIAL"));
    }

    @Test void externalPairExchangeRecordsOnlyPublicIdentityAndHttpOutcome() throws Exception {
        var engine=new BrowserEngine(root.toString(),"unused","ws://127.0.0.1",json){
            @Override JsonNode run(Path home,List<String> args,int seconds){fail("授权响应不得等待CLI观察");return null;}
        };
        Path home=engine.home("1","2");Files.createDirectories(home);Files.writeString(home.resolve("owner.json"),"{\"port\":52800}");
        String key=home.getFileName().toString(),id=engine.authorizationExchangeStarted(key,"pair");
        engine.authorizationExchangeFinished(key,id,"pair",200,"{\"device_id\":\"new-device\",\"token\":\"PRIVATE_TOKEN\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result=events(home).stream().filter(e->e.path("phase").asText().equals("succeeded")).findFirst().orElseThrow();
        assertEquals(id,result.path("requestId").asText());assertEquals("extension",result.path("source").asText());
        assertEquals(200,result.path("upstreamStatus").asInt());assertEquals(BrowserEngine.hash("new-device"),result.path("deviceTag").asText());
        assertFalse(events(home).toString().contains("PRIVATE_TOKEN"));
    }

    @Test void auditWriteFailurePreventsControlDispatch()throws Exception{
        var engine=new BrowserEngine(root.toString(),"unused","ws://127.0.0.1",json){
            @Override public synchronized int daemon(Path home)throws Exception{Files.createDirectories(home);return 52800;}
            @Override JsonNode run(Path home,List<String> args,int seconds){fail("audit must persist before invoking CLI");return null;}
        };
        Path home=engine.home("1","2");Files.createDirectories(home);Files.writeString(home.resolve("authorization-audit"),"not-a-directory");
        assertThrows(IOException.class,()->engine.device("1","2","pair",null,UUID.randomUUID().toString()));
    }

    @Test void slowControlObservationDoesNotHoldUpSuccessfulPairExchange()throws Exception{
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var engine=new BrowserEngine(root.toString(),"unused","ws://127.0.0.1",json){
            @Override public synchronized int daemon(Path home)throws Exception{Files.createDirectories(home);Files.writeString(home.resolve("owner.json"),"{\"port\":52800}");return 52800;}
            @Override JsonNode run(Path home,List<String> args,int seconds)throws Exception{
                if(args.contains("devices")){entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return json.createArrayNode();}
                return json.createObjectNode().put("success",true);
            }
        };
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var control=executor.submit(()->engine.device("1","2","pair",null,null));
            try{
                assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
                var response=executor.submit(()->{
                    String key=engine.home("1","2").getFileName().toString();String id=engine.authorizationExchangeStarted(key,"pair");
                    engine.authorizationExchangeFinished(key,id,"pair",200,"{\"device_id\":\"chrome\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));return true;
                });
                assertTrue(response.get(2,java.util.concurrent.TimeUnit.SECONDS));
            }finally{release.countDown();control.get(5,java.util.concurrent.TimeUnit.SECONDS);}
        }
    }

    private List<JsonNode> events(Path home)throws IOException{
        try(var paths=Files.list(home.resolve("authorization-audit/events"))){
            return paths.filter(p->p.getFileName().toString().endsWith(".json")).map(p->{try{return json.readTree(p.toFile());}catch(IOException e){throw new java.io.UncheckedIOException(e);}}).toList();
        }
    }
}
