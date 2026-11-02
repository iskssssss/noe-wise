package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.knowledge.KnowledgeBaseRevisionService;
import com.skillforge.core.knowledge.KnowledgeRebuildService;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.knowledge.RerankProvider;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.knowledge.dto.request.KnowledgeSearchRequest;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.ModelSelections;
import com.skillforge.core.model.ModelView;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

/** 显式启用且单次批次的真实百炼验收；沿用完整迁移 PG/Redis 和真实服务，不替换模型 Provider。 */
@EnabledIfEnvironmentVariable(named = "LIVE_KNOWLEDGE_ACCEPTANCE", matches = "true")
class KnowledgeLiveProviderAcceptanceTest extends AbstractIntegrationTest {
    private final KnowledgeService knowledge;
    private final KnowledgeBaseRevisionService revisions;
    private final KnowledgeRebuildService rebuilds;
    private final RerankProvider rerank;
    private final PlatformModelCatalogService models;
    private final PlanMapper plans;
    private final TenantMapper tenants;
    private final JdbcTemplate jdbc;

    @Autowired
    KnowledgeLiveProviderAcceptanceTest(KnowledgeService knowledge, KnowledgeBaseRevisionService revisions,
            KnowledgeRebuildService rebuilds, RerankProvider rerank, PlatformModelCatalogService models,
            PlanMapper plans, TenantMapper tenants, JdbcTemplate jdbc) {
        this.knowledge=knowledge; this.revisions=revisions; this.rebuilds=rebuilds; this.rerank=rerank;
        this.models=models; this.plans=plans; this.tenants=tenants; this.jdbc=jdbc;
    }

    @Test
    void realProvidersRebuildThreeDocumentsAndPublishManually() throws Exception {
        String directory = System.getenv("LIVE_KNOWLEDGE_BATCH_DIR");
        assertNotNull(directory, "LIVE_KNOWLEDGE_BATCH_DIR required: persistent approved batch directory");
        Path batch = Path.of(directory).toAbsolutePath().normalize();
        assertFalse(Files.exists(batch), "新知识批次目录必须不存在，禁止覆盖或重复发送历史请求");
        Files.createDirectories(batch);
        // 重新运行不能重置额度，也不能静默重复已发送的业务批次；原失败保留供人工决定后续范围。
        Files.writeString(batch.resolve("execution-started.json"),
                new ObjectMapper().writeValueAsString(Map.of("started", Instant.now().toString(),
                        "embedding_max_calls",20,"embedding_max_tokens",50_000,
                        "rerank_max_calls",10,"rerank_max_billed_tokens",50_000)), StandardOpenOption.CREATE_NEW);
        try (var proxy = new KnowledgeLiveMeteredProxy(batch, System.getenv("DASHSCOPE_API_KEY"))) {
            String tenant = createTenant(tenants, plans, "knowledge-live-" + SnowflakeIds.nextId());
            TenantContext.setTenantId(tenant);
            try {
                ModelView original = embeddingModel(proxy, "初始真实模型");
                ModelView target = embeddingModel(proxy, "重建真实模型");
                ModelView ranking = models.create(new ModelWrite(null,"真实百炼重排","RERANK",true,
                        "custom",null,null,"dashscope-rerank","bearer-api-key",proxy.rerankEndpoint(),
                        "qwen3.7-text-rerank","proxy-placeholder",null,null,null,null,null,60,null,
                        "/api/v1/services/rerank/text-rerank/text-rerank",java.math.BigDecimal.valueOf(50L),null,null,"CNY"),"knowledge-live");
                models.publish(ranking.id(),ranking.revision(),"knowledge-live");
                var selections=models.selections(tenant);
                models.select(tenant,new ModelSelections(selections.llmModelConfigId(),original.id(),ranking.id()));
                var base=knowledge.createBase(tenant,"真实供应商三文档验收",null);
                List<String> documents=List.of(
                        "服务申请：申请编号唯一，先选择资料查询并填写查询范围，再由申请人确认。2026年10月1日起资料查询限机构已授权目录，审批前不得处理敏感档案。查询申请需附部门、用途和收件范围；确认后取得申请编号。例外：历史档案需管理员二次审批，紧急口头请求不能绕过审批。",
                        "取消申请：以处理开始时间区分规则。开始处理前取消，系统退还全部申请费用并撤销原处理任务；处理开始后不得按旧版免费取消规则退款。2026年9月旧规则曾允许任意时点取消，该条已撤销。例外：供应商故障保留失败回执，管理员审核退款，不能重新派发未知结果任务。",
                        "费用报销：保留发票、付款凭证和原申请编号，金额以人民币元并保留两位小数。资料查询费用和采购费用独立核算；重复发票不得二次报销。提交机构财务审核后办理报销，未确认责任人和预算保持待确认。例外：缺失发票进入待补充，不得把缺失金额写成0或虚构发票。");
                List<String> oldSources=new ArrayList<>();
                List<Long> documentIds=new ArrayList<>();
                for(int index=0;index<documents.size();index++) {
                    var upload=knowledge.uploadFile(tenant,base.id(),"合成文档"+index,
                            new MockMultipartFile("file","synthetic-"+index+".txt","text/plain",
                                    documents.get(index).getBytes(StandardCharsets.UTF_8)));
                    assertEquals(1,upload.chunks()); oldSources.add(upload.sourceVersion()); documentIds.add(upload.documentId());
                    assertFalse(Files.exists(batch.resolve("external-failure.json")),
                            "本地代理或供应商失败：查看批次 external-failure.json 与 handler-failure-*.json 原始诊断");
                    assertEquals(1L,queryCount(jdbc,tenant,
                            "select count(*) from knowledge_chunk_values where tenant_id=? and source_version=? and vector_dims(embedding)=1024",
                            Long.parseLong(tenant),upload.sourceVersion()),"每份文档必须成功取得真实向量才继续下一调用");
                }
                assertEquals(3L,queryCount(jdbc,tenant,
                        "select count(*) from knowledge_chunk_values where tenant_id=? and kb_id=? and vector_dims(embedding)=1024",
                        Long.parseLong(tenant),base.id()),"真实供应商向量必须已写入，不能接受关键词降级");
                var draft=revisions.create(tenant,base.id());
                var policy=new KnowledgeCapabilityPolicyRequest(true,false,Set.of("VECTOR"),"PINNED",true);
                revisions.markReady(tenant,base.id(),draft.revision(),oldSources,policy);
                var firstPublished=revisions.publish(tenant,base.id(),draft.revision());
                String query="如何申请资料查询";
                var before=search(tenant,base.id(),query,null,false);
                assertEquals(3,before.size());
                assertTrue(before.stream().allMatch(hit->oldSources.contains(hit.sourceVersion())));
                var created=rebuilds.create(tenant,base.id(),target.id(),target.revision(),"approved-live-rebuild",
                        rebuilds.latest(tenant,base.id()).baseRevision());
                rebuilds.run(tenant,created.jobId());
                var ready=rebuilds.latest(tenant,base.id());
                assertEquals("READY",ready.status(),"真实 Embedding 完整回执才允许等待手工发布");
                assertTrue(ready.writeLocked());
                assertEquals(firstPublished.id(),knowledge.getBase(tenant,base.id()).activeRevisionId());
                for(int index=0;index<documentIds.size();index++) assertEquals(oldSources.get(index),
                        jdbc.queryForObject("select current_source_version from nw_knowledge_documents where id=?",String.class,documentIds.get(index)));
                var during=search(tenant,base.id(),query,null,false);
                assertEquals(3,during.size());
                assertTrue(during.stream().allMatch(hit->oldSources.contains(hit.sourceVersion())),"READY 时旧查询仍可用");
                var published=rebuilds.publish(tenant,base.id(),ready.jobId(),ready.revision(),ready.baseRevision(),policy);
                assertEquals("PUBLISHED",published.status());
                assertFalse(rebuilds.latest(tenant,base.id()).writeLocked());
                var after=search(tenant,base.id(),query,null,false);
                assertEquals(3,after.size());
                assertTrue(after.stream().noneMatch(hit->oldSources.contains(hit.sourceVersion())));
                assertEquals(new HashSet<>(documents),new HashSet<>(after.stream().map(hit->hit.content()).toList()));
                assertNotEquals(firstPublished.id(),knowledge.getBase(tenant,base.id()).activeRevisionId());
                assertEquals(Long.parseLong(target.id()),jdbc.queryForObject(
                        "select embedding_model_config_id from nw_knowledge_bases where id=?",Long.class,base.id()));
                var pinned=search(tenant,base.id(),query,oldSources.getFirst(),false);
                assertEquals(1,pinned.size()); assertEquals(oldSources.getFirst(),pinned.getFirst().sourceVersion());
                assertEquals(6L,queryCount(jdbc,tenant,
                        "select count(*) from knowledge_chunk_values where tenant_id=? and kb_id=? and vector_dims(embedding)=1024",
                        Long.parseLong(tenant),base.id()),"发布保留真实旧向量和新向量");
                var ranked=rerank.rerank(tenant,query,documents);
                assertNotNull(ranked,"生产 RerankProvider 不能静默降级为原顺序");
                assertEquals(3,ranked.size());
                assertEquals(Set.of(0,1,2),new HashSet<>(ranked.stream().map(row->row.index()).toList()));
                assertTrue(ranked.stream().allMatch(row->Double.isFinite(row.relevanceScore())&&row.relevanceScore()>=0&&row.relevanceScore()<=1));
                assertEquals(0,ranked.getFirst().index(),"明确申请查询应把申请文档排在首位");
                List<com.fasterxml.jackson.databind.JsonNode> attempts=proxy.reservations();
                assertEquals(10L,attempts.stream().filter(row->"embedding".equals(row.path("kind").asText())).count());
                assertEquals(1L,attempts.stream().filter(row->"rerank".equals(row.path("kind").asText())).count());
                assertFalse(Files.exists(batch.resolve("external-failure.json")),"外调或计量异常时禁止生成成功证据");
                var metering=proxy.meteringReceipts();
                assertEquals(attempts.size(),metering.size(),"每次预留必须有完整真实供应商计量回执");
                for (var attempt:attempts) {
                    var receipts=metering.stream().filter(row->row.path("attempt").asText()
                            .equals(attempt.path("attempt").asText())).toList();
                    assertEquals(1,receipts.size());
                    var receipt=receipts.getFirst();
                    assertTrue(receipt.path("complete_success").asBoolean());
                    assertEquals(attempt.path("reserved_tokens").asLong(),receipt.path("reserved_tokens").asLong());
                    assertTrue(receipt.path("total_tokens").asLong()>0
                            &&receipt.path("total_tokens").asLong()<=attempt.path("reserved_tokens").asLong());
                    if("embedding".equals(attempt.path("kind").asText())) assertTrue(receipt.path("prompt_tokens").asLong()>0);
                }
                Files.writeString(batch.resolve("acceptance-success.json"),new ObjectMapper().writeValueAsString(Map.of(
                        "status","PASS","tenant_id",tenant,"kb_id",base.id(),"job_id",created.jobId(),
                        "old_model",original.id(),"new_model",target.id(),"old_sources",oldSources,
                        "new_sources",after.stream().map(hit->hit.sourceVersion()).toList(),
                        "rerank_first_index",ranked.getFirst().index(),"attempts",attempts.size())),StandardOpenOption.CREATE_NEW);
            } finally { TenantContext.clear(); }
        }
    }

    private ModelView embeddingModel(KnowledgeLiveMeteredProxy proxy,String name) {
        var created = models.create(new ModelWrite(null,name,"EMBEDDING",true,"custom",null,null,"openai-embeddings",
                "bearer-api-key",proxy.embeddingEndpoint(),"qwen3.7-text-embedding-flash","proxy-placeholder",
                null,null,null,null,null,60,1024,null,new java.math.BigDecimal("12.5"),java.math.BigDecimal.valueOf(0L),java.math.BigDecimal.valueOf(0L),"CNY"),"knowledge-live");
        models.publish(created.id(),created.revision(),"knowledge-live");
        return created;
    }
    private List<com.skillforge.core.knowledge.KnowledgeHit> search(String tenant,Long base,String query,String source,boolean ranking) {
        return knowledge.search(tenant,base.toString(),query,3,new KnowledgeSearchRequest(base.toString(),query,3,
                "semantic",false,null,null,ranking,source));
    }
}
