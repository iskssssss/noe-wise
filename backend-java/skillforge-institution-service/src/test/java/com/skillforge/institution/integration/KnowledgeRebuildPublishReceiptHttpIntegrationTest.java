package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.knowledge.EmbeddingProvider;
import com.skillforge.core.knowledge.search.SearchIndexer;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.service.KnowledgeManagementService;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@org.springframework.context.annotation.Import(KnowledgeServiceIntegrationTest.LocalEmbeddingFixtureConfiguration.class)
class KnowledgeRebuildPublishReceiptHttpIntegrationTest extends AbstractIntegrationTest {
 @Autowired TenantMapper tenants; @Autowired PlanMapper plans; @Autowired KnowledgeManagementService management;
 @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json; @Autowired PlatformTransactionManager manager;
 @Autowired com.skillforge.core.knowledge.KnowledgeService knowledge;
 @Autowired com.skillforge.core.knowledge.KnowledgeRebuildService rebuilds;
 @Autowired com.skillforge.core.model.PlatformModelCatalogService models;
 @MockitoBean com.skillforge.institution.knowledge.KnowledgeRebuildScheduler scheduler;
 @MockitoBean EmbeddingProvider embedding; @MockitoBean SearchIndexer indexer; @LocalServerPort int port;
 final HttpClient http=HttpClient.newHttpClient();
 @Test void revokedPublisherCanFenceOnlyItsOwnRequest() throws Exception {
  String slug="rebuild-receipt-"+SnowflakeIds.nextId();String tenant=createTenant(tenants,plans,slug);
  TenantContext.setTenantId(tenant);long member=createTestTenantAdministrator(tenant,"receipt-admin","ReceiptTestPassword123");
  long kb=withAuthenticatedMember(member,()->management.create(tenant,"回执测试",null,"economical","full_text",false).id());
  long job=SnowflakeIds.nextId();
  new TransactionTemplate(manager).executeWithoutResult(s->{
   jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant);
   jdbc.update("insert into nw_knowledge_rebuild_jobs (id,tenant_id,kb_id,idempotency_key,fingerprint,status,revision,fence,target_model_id,target_model_revision,target_dimensions,original_model_id,original_model_revision) values (?,?,?,? ,?,'READY',1,0,1,1,768,1,1)",job,Long.parseLong(tenant),kb,"test-"+job,"x".repeat(64));
  });TenantContext.clear();
  String admin=login(slug,"receipt-admin"), access="/api/v1/tenant/foundation/access";
  var read=List.of("foundation.knowledge.read","foundation.resource.knowledge.read");
  var permissions=List.of("foundation.knowledge.read","foundation.knowledge.publish","foundation.resource.knowledge.read","foundation.resource.knowledge.manage");
  JsonNode role=call("POST",access+"/role",admin,Map.of("code","writer_"+job,"name","writer","data_scope","all","permissions",permissions,"department_ids",List.of()),200);
  JsonNode reader=call("POST",access+"/role",admin,Map.of("code","reader_"+job,"name","reader","data_scope","all","permissions",read,"department_ids",List.of()),200);
  call("POST",access+"/member",admin,Map.of("username","original","display_name","original","password","ReceiptTestPassword123","role_ids",List.of(role.path("id").asText())),200);
  call("POST",access+"/member",admin,Map.of("username","other","display_name","other","password","ReceiptTestPassword123","role_ids",List.of(reader.path("id").asText())),200);
  String original=login(slug,"original"), other=login(slug,"other"), id=UUID.randomUUID().toString();
  String root="/api/v1/knowledge/bases/rebuilds";
  Map<String,Object> command=Map.of("request_id",id,"kb_id",kb,"job_id",job,"expected_revision",1,"expected_base_revision",0,"policy",Map.of("model_invocable",true,"user_invocable",true,"allowed_operators",List.of("search"),"freshness_policy","fresh","citation_required",true));
  String resolution=root+"/publish-resolution?kb_id="+kb+"&job_id="+job+"&request_id="+id;
  assertEquals("FENCED",call("POST",root+"/publish-fence",other,command,200).path("status").asText());
  assertEquals("UNKNOWN",call("GET",resolution,original,null,200).path("status").asText());
  String failedId=UUID.randomUUID().toString(); var failed=new java.util.HashMap<>(command); failed.put("request_id",failedId);
  call("POST",root+"/publish-with-receipt",original,failed,409);
  assertEquals("UNKNOWN",call("GET",root+"/publish-resolution?kb_id="+kb+"&job_id="+job+"&request_id="+failedId,original,null,200).path("status").asText());
  JsonNode revokedRole=call("PUT",access+"/role",admin,Map.of("id",role.path("id").asText(),"name","read only","data_scope","all","permissions",read,"department_ids",List.of(),"enabled",true,"lock_version",role.path("lock_version").asLong()),200);
  original=login(slug,"original");
  String revoked=original;
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
   var gate=new java.util.concurrent.CountDownLatch(1);
   var first=pool.submit(()->{gate.await();return call("POST",root+"/publish-fence",revoked,command,200);});
   var second=pool.submit(()->{gate.await();return call("POST",root+"/publish-fence",revoked,command,200);});
   gate.countDown(); assertEquals("FENCED",first.get().path("status").asText()); assertEquals(first.get(),second.get());
  }
  assertEquals("FENCED",call("GET",resolution,original,null,200).path("status").asText());
  var different=new java.util.HashMap<>(command); different.put("expected_revision",2);
  call("POST",root+"/publish-fence",original,different,409);
  call("POST",root+"/publish-with-receipt",original,command,403);
  call("PUT",access+"/role",admin,Map.of("id",role.path("id").asText(),"name","publisher restored","data_scope","all","permissions",permissions,"department_ids",List.of(),"enabled",true,"lock_version",revokedRole.path("lock_version").asLong()),200);
  original=login(slug,"original");
  assertEquals("FENCED",call("POST",root+"/publish-with-receipt",original,command,200).path("status").asText());
  // 后续作业成为最新作业后，原回执仍按原 job 和主体查询，而非读取最新状态。
  long newerJob=SnowflakeIds.nextId();
  new TransactionTemplate(manager).executeWithoutResult(s->{
   jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant);
   jdbc.update("update nw_knowledge_rebuild_jobs set status='CANCELLED' where tenant_id=? and id=?",Long.parseLong(tenant),job);
   jdbc.update("insert into nw_knowledge_rebuild_jobs (id,tenant_id,kb_id,idempotency_key,fingerprint,status,revision,fence,target_model_id,target_model_revision,target_dimensions,original_model_id,original_model_revision,created_at) values (?,?,?,? ,?,'QUEUED',1,0,1,1,768,1,1,now()+interval '1 second')",newerJob,Long.parseLong(tenant),kb,"new-"+newerJob,"y".repeat(64));
  });
  TenantContext.setTenantId(tenant); try {assertEquals(newerJob,rebuilds.latest(tenant,kb).jobId());} finally {TenantContext.clear();}
  assertEquals("FENCED",call("GET",resolution,original,null,200).path("status").asText());
  assertEquals("UNKNOWN",call("GET",root+"/publish-resolution?kb_id="+kb+"&job_id="+newerJob+"&request_id="+id,original,null,200).path("status").asText());
  String rlsRole="receipt_reader_"+job;
  jdbc.execute("CREATE ROLE "+rlsRole+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
  jdbc.execute("GRANT USAGE ON SCHEMA public TO "+rlsRole);
  jdbc.execute("GRANT SELECT ON nw_knowledge_rebuild_publish_receipts TO "+rlsRole);
  new TransactionTemplate(manager).executeWithoutResult(s->{
   jdbc.execute("set local role "+rlsRole);
   jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant);
   assertEquals(2,jdbc.queryForObject("select count(*) from nw_knowledge_rebuild_publish_receipts where request_id=?::uuid",Integer.class,id));
   jdbc.queryForObject("select set_config('app.tenant_id','999999999',true)",String.class);
   assertEquals(0,jdbc.queryForObject("select count(*) from nw_knowledge_rebuild_publish_receipts where request_id=?::uuid",Integer.class,id));
  });
 }
 @Test void competingMembersCannotClaimEachOthersPublishedPolicy() throws Exception {
  String slug="rebuild-race-"+SnowflakeIds.nextId(); String tenant=createTenant(tenants,plans,slug);
  TenantContext.setTenantId(tenant);
  try {
   long adminId=createTestTenantAdministrator(tenant,"race-admin","ReceiptTestPassword123");
   float[] vector=new float[1536];vector[0]=1;
   var model=models.create(new com.skillforge.core.model.ModelWrite(null,"发布回执模型","EMBEDDING",true,"custom","knowledge-embedding-fixture","knowledge-fixture-v1","openai-embeddings","bearer-api-key","https://embedding.example/v1","fixture","test-secret",null,null,null,null,null,30,1536,null,null,null,null,"CNY"),"receipt-test");
   var published=models.publish(model.id(),model.revision(),"receipt-test");
   org.mockito.Mockito.when(embedding.config(org.mockito.ArgumentMatchers.anyString())).thenReturn(models.readRevision(published.id(),published.revision()));
   org.mockito.Mockito.when(embedding.embed(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString())).thenReturn(vector);
   org.mockito.Mockito.when(embedding.embedBatch(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyList())).thenAnswer(i->((java.util.List<?>)i.getArgument(3)).stream().map(x->vector).toList());
   long kb; com.skillforge.core.knowledge.KnowledgeRebuildService.Snapshot job,ready;
   // 显式创建超出 JS 安全整数范围的真实数据库对象，避免依赖当前时间和雪花生成器参数。
   try(var ids=org.mockito.Mockito.mockStatic(SnowflakeIds.class)) {
    var next=new java.util.concurrent.atomic.AtomicLong(9_007_199_254_741_001L);
    ids.when(SnowflakeIds::nextId).thenAnswer(ignored->next.getAndIncrement());
    kb=withAuthenticatedMember(adminId,()->management.create(tenant,"真实发布并发",null,"high_quality","hybrid",false).id());
    knowledge.uploadFile(tenant,kb,"fixture",new org.springframework.mock.web.MockMultipartFile("file","fixture.txt","text/plain","receipt race knowledge".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    job=rebuilds.create(tenant,kb,published.id(),published.revision(),"concurrent",rebuilds.latest(tenant,kb).baseRevision());
    rebuilds.run(tenant,job.jobId());ready=rebuilds.latest(tenant,kb);assertEquals("READY",ready.status());
   }
   TenantContext.clear(); String admin=login(slug,"race-admin"), access="/api/v1/tenant/foundation/access";
   var permissions=List.of("foundation.knowledge.read","foundation.knowledge.publish","foundation.resource.knowledge.read","foundation.resource.knowledge.manage");
   JsonNode role=call("POST",access+"/role",admin,Map.of("code","publishers_"+kb,"name","publishers","data_scope","all","permissions",permissions,"department_ids",List.of()),200);
   var members=new java.util.HashMap<String,String>();
   for(String user:List.of("publisher-one","publisher-two")) {
    JsonNode created=call("POST",access+"/member",admin,Map.of("username",user,"display_name",user,"password","ReceiptTestPassword123","role_ids",List.of(role.path("id").asText())),200);
    members.put(user,created.path("id").asText());
   }
   String first=login(slug,"publisher-one"), second=login(slug,"publisher-two"), firstId=UUID.randomUUID().toString(), secondId=UUID.randomUUID().toString();
   String root="/api/v1/knowledge/bases/rebuilds";
   var firstCommand=Map.of("request_id",firstId,"kb_id",kb,"job_id",job.jobId(),"expected_revision",ready.revision(),"expected_base_revision",ready.baseRevision(),"policy",Map.of("model_invocable",true,"user_invocable",false,"allowed_operators",List.of("VECTOR"),"freshness_policy","PINNED","citation_required",true));
   var secondCommand=Map.of("request_id",secondId,"kb_id",kb,"job_id",job.jobId(),"expected_revision",ready.revision(),"expected_base_revision",ready.baseRevision(),"policy",Map.of("model_invocable",false,"user_invocable",true,"allowed_operators",List.of("VECTOR"),"freshness_policy","PINNED","citation_required",false));
   try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
    var gate=new java.util.concurrent.CountDownLatch(1);
    var a=pool.submit(()->{gate.await();return request("POST",root+"/publish-with-receipt",first,firstCommand);});
    var b=pool.submit(()->{gate.await();return request("POST",root+"/publish-with-receipt",second,secondCommand);});
    gate.countDown();var ra=a.get();var rb=b.get();assertEquals(Set.of(200,409),Set.of(ra.statusCode(),rb.statusCode()),ra.body()+" / "+rb.body());
    String winner=ra.statusCode()==200?first:second, loser=ra.statusCode()==200?second:first, winnerId=ra.statusCode()==200?firstId:secondId, loserId=ra.statusCode()==200?secondId:firstId;
    String prefix=root+"/publish-resolution?kb_id="+kb+"&job_id="+job.jobId()+"&request_id=";
    JsonNode receipt=call("GET",prefix+winnerId,winner,null,200); assertEquals("SUCCEEDED",receipt.path("status").asText());
    JsonNode initial=json.readTree(ra.statusCode()==200?ra.body():rb.body()).path("data"); assertEquals(initial,receipt);
    assertEquals("UNKNOWN",call("GET",prefix+winnerId,loser,null,200).path("status").asText());
    assertEquals("UNKNOWN",call("GET",prefix+loserId,loser,null,200).path("status").asText());
    assertEquals("PUBLISHED",receipt.path("snapshot").path("status").asText());
    JsonNode snapshot=receipt.path("snapshot");
    assertTrue(kb>9_007_199_254_740_991L);assertTrue(job.jobId()>9_007_199_254_740_991L);
    assertTrue(snapshot.path("kb_id").isTextual());assertEquals(Long.toString(kb),snapshot.path("kb_id").asText());
    assertTrue(snapshot.path("job_id").isTextual());assertEquals(job.jobId().toString(),snapshot.path("job_id").asText());
    assertTrue(snapshot.path("target_model_id").isTextual());assertEquals(published.id(),snapshot.path("target_model_id").asText());
    // 历史行以 JSON 数字持有雪花 ID；数据库和服务端仍有整数精度，查询时必须输出十进制字符串。
    var historical=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot.deepCopy();
    historical.put("kb_id",kb);historical.put("job_id",job.jobId());historical.put("target_model_id",Long.parseLong(published.id()));
    String historicalId=UUID.randomUUID().toString();
    String actor="tenant:"+tenant+":member:"+members.get(ra.statusCode()==200?"publisher-one":"publisher-two");
    new TransactionTemplate(manager).executeWithoutResult(tx->{
     jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant);
     jdbc.update("insert into nw_knowledge_rebuild_publish_receipts (tenant_id,request_id,actor_ref,kb_id,job_id,fingerprint,status,snapshot) values (?,?::uuid,?,?,?,?, 'SUCCEEDED',?::jsonb)",Long.parseLong(tenant),historicalId,actor,kb,job.jobId(),"f".repeat(64),historical.toString());
     assertEquals("number",jdbc.queryForObject("select jsonb_typeof(snapshot->'job_id') from nw_knowledge_rebuild_publish_receipts where tenant_id=? and request_id=?::uuid",String.class,Long.parseLong(tenant),historicalId));
    });
    assertEquals(receipt,call("GET",prefix+historicalId,winner,null,200));
    var policyRows=queryRows(jdbc,tenant,"select capability_policy::text as policy from nw_knowledge_base_revisions where tenant_id=? and kb_id=? and status='published'",Long.parseLong(tenant),kb);
    assertEquals(1,policyRows.size()); JsonNode frozen=json.readTree(policyRows.getFirst().get("policy").toString());
    assertEquals(ra.statusCode()==200,frozen.path("model_invocable").asBoolean());
    assertEquals(ra.statusCode()!=200,frozen.path("user_invocable").asBoolean());
    assertEquals(ra.statusCode()==200,frozen.path("citation_required").asBoolean());
   }
  } finally {TenantContext.clear();}
 }
 HttpResponse<String> request(String method,String path,String token,Object body) throws Exception {
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));if(token!=null)b.header("Authorization","Bearer "+token);if(body!=null)b.header("Content-Type","application/json");
  return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
 }
 String login(String slug,String username) throws Exception {return call("POST","/api/v1/auth/login",null,Map.of("tenant_slug",slug,"username",username,"password","ReceiptTestPassword123"),200).path("access_token").asText();}
 JsonNode call(String method,String path,String token,Object body,int expected) throws Exception {
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));if(token!=null)b.header("Authorization","Bearer "+token);
  if(body!=null)b.header("Content-Type","application/json");
  var r=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  assertEquals(expected,r.statusCode(),r.body());return json.readTree(r.body()).path("data");
 }
}
