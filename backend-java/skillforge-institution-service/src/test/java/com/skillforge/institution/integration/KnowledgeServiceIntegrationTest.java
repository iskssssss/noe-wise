package com.skillforge.institution.integration;
import com.skillforge.core.shared.config.SnowflakeIds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.knowledge.EmbeddingProvider;
import com.skillforge.core.knowledge.search.SearchIndexer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicInteger;
import com.skillforge.core.knowledge.KnowledgeBaseRevisionService;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.knowledge.dto.request.KnowledgeSearchRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 知识库 RAG 集成测试。
 *
 * <p>使用 Testcontainers pgvector 验证文档分块写入 embedding 后，
 * 向量检索能命中刚入库的分块。
 */
@org.springframework.context.annotation.Import(KnowledgeServiceIntegrationTest.LocalEmbeddingFixtureConfiguration.class)
class KnowledgeServiceIntegrationTest extends AbstractIntegrationTest {
    /** 只有EmbeddingProvider为受控回执；目录仍明确声明本地服务、EMBEDDING类型和真实协议契约。 */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class LocalEmbeddingFixtureConfiguration {
        @org.springframework.context.annotation.Bean
        ModelProviderCatalog knowledgeEmbeddingFixtureCatalog() {
            var unsupported=ModelProviderCatalog.SupportLevel.UNSUPPORTED;
            var capabilities=new ModelProviderCatalog.CapabilitySupport(unsupported,unsupported,unsupported,unsupported,unsupported);
            var protocol=new ModelProviderCatalog.ProtocolProfile(
                    ModelProviderCatalog.ModelProtocol.OPENAI_EMBEDDINGS,"openai-embeddings-v1",
                    Set.of(ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY),capabilities,
                    ModelProviderCatalog.VerificationStatus.VERIFIED);
            var service=new ModelProviderCatalog.ServiceProfile("knowledge-embedding-fixture","Knowledge controlled embedding fixture","knowledge-fixture-v1",
                    ModelProviderCatalog.Availability.SUPPORTED,null,
                    ModelProviderCatalog.EndpointMode.CUSTOM,null,List.of(protocol),
                    ModelProviderCatalog.ServiceRegion.LOCAL,
                    Set.of(ModelProviderCatalog.ModelKind.EMBEDDING));
            ModelProvider provider=()->new ModelProviderCatalog.ProviderPreset("custom","Knowledge controlled embedding fixture",
                    ModelProviderCatalog.ProviderCategory.CUSTOM_OR_SELF_HOSTED,List.of(service));
            return new ModelProviderCatalog(List.of(provider));
        }
    }


    /** 知识库服务。 */
    @Autowired
    private KnowledgeService knowledgeService;

    /** Knowledge Base revision 生命周期服务。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private KnowledgeBaseRevisionService revisionService;

    @Autowired private com.skillforge.core.knowledge.InstitutionalKnowledgeProjectionExecutor projections;
    @Autowired private com.skillforge.core.knowledge.mapper.KnowledgeGovernanceProjectionMapper projectionMapper;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper referenceBases;
    @Autowired private com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper referenceRevisions;

    /** 项目 Embedding Provider Mock，返回固定 1536 维向量以匹配 pgvector 列。 */
    @MockitoBean
    private EmbeddingProvider embeddingProvider;

    @MockitoBean
    private SearchIndexer searchIndexer;

    /** 本类显式推进真实作业/清理事务，隔离后台调度抢占，不以此声明调度器已验收。 */
    @MockitoBean
    private com.skillforge.institution.knowledge.KnowledgeRebuildScheduler knowledgeScheduler;

    /** 通过真实平台发布路径创建知识库冻结模型的外键目标。 */
    @Autowired
    private PlatformModelCatalogService models;

    /** 核对聚合前实际保留的历史分块和软删除事实。 */
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired private com.skillforge.core.knowledge.mapper.KnowledgeDocumentMapper documentCounts;
    @Autowired private com.skillforge.core.knowledge.mapper.KnowledgeChunkMapper chunkCounts;

    /** 真实正文预算/引用回收事务入口。 */
    @Autowired private com.skillforge.core.knowledge.KnowledgeStorageService storage;
    /** 只观察真实 Mapper 调用，不替换正文回执或 SQL。 */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.skillforge.core.knowledge.mapper.KnowledgeStorageMapper contentReads;
    @Autowired private com.skillforge.core.knowledge.KnowledgeIndexLifecycleService indexes;

    /** 租户表映射器。 */
    @Autowired
    private TenantMapper tenantMapper;

    /** 套餐表映射器。 */
    @Autowired
    private PlanMapper planMapper;

    /** 测试租户 ID。 */
    private String tenantId;

    /**
     * 注册测试租户并确保免费套餐存在。
     */
    @BeforeEach
    void setUp() {
        ensureFreePlan(planMapper);
        tenantId = createTenant(tenantMapper, planMapper, "rag-" + SnowflakeIds.nextId());
        TenantContext.clear();
    }

    @AfterEach
    void clearTenantContext() { TenantContext.clear(); }

    /**
     * 上传文档后可被向量检索命中。
     */
    @Test
    void uploadedDocumentIsSearchable() {
        float[] vector = new float[1536];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = 0.01f * (i % 10);
        }
        whenEmbeddingIsAvailable(vector);
        try {
            TenantContext.setTenantId(tenantId);
            var base = knowledgeService.createBase(tenantId, "RAG 测试库", null);
            String kbId = String.valueOf(base.id());
            var upload = knowledgeService.uploadFile(
                    tenantId, Long.parseLong(kbId), "申请说明",
                    textFile("request.txt", "服务申请流程：选择科室后确认时间，系统返回申请成功。"));
            assertEquals(1, upload.chunks());
            var chunkPage=knowledgeService.listChunks(tenantId,upload.documentId(),1,1);
            assertEquals(1,chunkPage.total());assertEquals(1,chunkPage.items().size());
            assertTrue(chunkPage.items().getFirst().hasEmbedding());assertEquals(upload.sourceVersion(),chunkPage.items().getFirst().sourceVersion());
            assertEquals("服务申请流程：选择科室后确认时间，系统返回申请成功。",chunkPage.items().getFirst().content());
            assertTrue(knowledgeService.listChunks(tenantId,upload.documentId(),2,1).items().isEmpty());
            var search = knowledgeService.search(tenantId, kbId, "申请", 3, null);
            var items = search;
            assertFalse(items.isEmpty());
            assertEquals("服务申请流程：选择科室后确认时间，系统返回申请成功。",
                    items.get(0).content());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void embeddingRunsOutsideTheDatabaseWriteTransaction() {
        whenEmbeddingIsAvailable(new float[1536]);
        when(embeddingProvider.embedBatch(anyString(), anyString(), anyLong(), anyList()))
                .thenAnswer(invocation -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                            "模型外调不能持有知识文档写事务");
                    List<String> inputs = invocation.getArgument(3);
                    return inputs.stream().map(ignored -> new float[1536]).toList();
                });
        try {
            TenantContext.setTenantId(tenantId);
            var base = knowledgeService.createBase(tenantId, "事务边界", null);
            var upload = knowledgeService.uploadFile(tenantId, base.id(), "文档",
                    textFile("transaction.txt", "模型调用先完成，再原子提交来源版本。"));
            assertEquals(1, upload.chunks());
        } finally { TenantContext.clear(); }
    }

    @Test
    void externalIndexStartsOnlyAfterTheCompleteSourceVersionCommits() {
        whenEmbeddingIsAvailable(new float[1536]);
        var indexed = new AtomicInteger();
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "外部索引不能在知识文档写事务中发布");
            List<java.util.Map<String, Object>> chunks = invocation.getArgument(1);
            for (var chunk : chunks) {
                assertEquals(1L, queryCount(jdbc, tenantId, """
                        SELECT count(*) FROM nw_knowledge_document_versions
                        WHERE tenant_id=? AND document_id=? AND source_version=? AND status='ready'
                        """, Long.parseLong(tenantId), chunk.get("document_id"), chunk.get("source_version")));
            }
            indexed.addAndGet(chunks.size());
            return true;
        }).when(searchIndexer).indexChunks(anyString(), anyList());
        try {
            TenantContext.setTenantId(tenantId);
            var base = knowledgeService.createBase(tenantId, "提交后索引", null);
            var upload = knowledgeService.uploadFile(tenantId, base.id(), "文档",
                    textFile("committed.txt", "只有完整 ready 来源版本提交后，派生索引才能写入。"));
            assertEquals(upload.chunks(), indexed.get());
        } finally { TenantContext.clear(); }
    }

    @Test
    void pinnedSourceVersionSearchExcludesAReindexedDocumentVersion() {
        float[] vector = new float[1536];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = 0.02f * (i % 10);
        }
        whenEmbeddingIsAvailable(vector);
        try {
            TenantContext.setTenantId(tenantId);
            var base = knowledgeService.createBase(tenantId, "版本测试库", null);
            String kbId = String.valueOf(base.id());
            var first = knowledgeService.uploadFile(
                    tenantId, Long.parseLong(kbId), "旧政策", textFile("old-policy.txt", "旧政策版本内容"));
            var second = knowledgeService.uploadFile(
                    tenantId, Long.parseLong(kbId), "新政策", textFile("new-policy.txt", "新政策版本内容"));
            String firstVersion = String.valueOf(first.sourceVersion());
            String secondVersion = String.valueOf(second.sourceVersion());
            assertFalse(firstVersion.equals(secondVersion));

            var search = knowledgeService.search(tenantId, kbId, "政策", 5,
                    new KnowledgeSearchRequest(kbId, "政策", 5, null, null, null, null, null,
                            firstVersion));
            var items = search;
            assertEquals(1, items.size());
            assertEquals("旧政策版本内容", items.get(0).content());
            assertEquals(firstVersion, items.get(0).sourceVersion());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void publishedKnowledgeReferencesReadOnlyFiveIdentityFieldsWithRealTenantIsolation(
            @Autowired com.skillforge.institution.service.CapabilityMountReferenceResolvers actualResolver) {
        whenEmbeddingIsAvailable(new float[1536]);
        TenantContext.setTenantId(tenantId);
        var base=knowledgeService.createBase(tenantId,"引用元数据投影",null);
        var first=knowledgeService.uploadFile(tenantId,base.id(),"第一份",textFile("reference-one.txt","第一份受控知识材料"));
        var second=knowledgeService.uploadFile(tenantId,base.id(),"第二份",textFile("reference-two.txt","第二份受控知识材料"));
        var draft=revisionService.create(tenantId,base.id());
        var ready=revisionService.markReady(tenantId,base.id(),draft.revision(),List.of(first.sourceVersion(),second.sourceVersion()),policy());
        var published=revisionService.publish(tenantId,base.id(),ready.revision());
        var unpublished=revisionService.create(tenantId,base.id());
        TenantContext.clear();String other=createTenant(tenantMapper,planMapper,"reference-other-"+SnowflakeIds.nextId());
        String reader="knowledge_reference_"+SnowflakeIds.nextId();
        jdbc.execute("CREATE ROLE "+reader+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO "+reader);
        jdbc.execute("GRANT SELECT ON nw_knowledge_bases,nw_knowledge_base_revisions,nw_capability_versions TO "+reader);
        // 仅观察真实Mapper返回的存储行；不替换SQL、正文或服务返回值。
        var observed=new java.util.ArrayList<com.skillforge.core.knowledge.model.KnowledgeBaseRevisionDO>();
        var observedMapper=org.mockito.Mockito.mock(com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(referenceRevisions));
        doAnswer(call->{var row=referenceRevisions.selectOne(call.getArgument(0));if(row!=null)observed.add(row);return row;})
                .when(observedMapper).selectOne(org.mockito.ArgumentMatchers.any());
        doAnswer(call->{var rows=referenceRevisions.selectList(call.getArgument(0));observed.addAll(rows);return rows;})
                .when(observedMapper).selectList(org.mockito.ArgumentMatchers.any());
        doAnswer(call->{var rows=referenceRevisions.activePublishedForCapabilityVersions(call.getArgument(0),call.getArgument(1));observed.addAll(rows);return rows;})
                .when(observedMapper).activePublishedForCapabilityVersions(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyList());
        var references=new com.skillforge.core.knowledge.KnowledgeReferenceService(referenceBases,observedMapper);
        long tenant=Long.parseLong(tenantId);
        var expected=new com.skillforge.core.knowledge.KnowledgeReferenceService.Revision(published.id(),tenant,base.id(),"published",published.capabilityVersionId());
        var tx=new TransactionTemplate(transactionManager);
        TenantContext.setTenantId(tenantId);
        long definitionId=jdbc.queryForObject("SELECT definition_id FROM nw_capability_versions WHERE tenant_id=? AND id=?",
                Long.class,tenant,published.capabilityVersionId());
        // 只构造解析输入，不持久化Agent、挂载、Team或Run，不赋予执行权限。
        var followActive=new com.skillforge.core.capability.CapabilityMount("reference-follow-active",tenant,1L,
                new com.skillforge.core.capability.CapabilityRef(com.skillforge.core.capability.CapabilityType.KNOWLEDGE_SOURCE,
                        Long.toString(definitionId),published.capabilityVersionId().toString()),
                new com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig(
                        com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE,null),
                2,3L,true);
        tx.executeWithoutResult(status->{
            jdbc.execute("SET LOCAL ROLE "+reader);
            assertFalse(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
            // 同输入完整读取负控，保留原source manifest与治理策略；不代表存储磁盘或网络字节。
            var full=referenceRevisions.selectById(published.id());
            assertEquals(Set.of(first.sourceVersion(),second.sourceVersion()),Set.copyOf(full.getSourceVersions()));
            assertFalse(full.getCapabilityPolicy().isEmpty());
            long materialBytes=jdbc.queryForObject("SELECT octet_length(source_versions::text)+octet_length(capability_policy::text) FROM nw_knowledge_base_revisions WHERE id=?",Long.class,published.id());
            assertTrue(materialBytes>0);
            assertEquals(expected,references.publishedRevision(tenant,published.id()));
            assertEquals(List.of(expected),references.publishedForCapability(tenant,published.capabilityVersionId()));
            assertEquals(List.of(expected),references.activePublishedForCapabilityVersions(tenant,List.of(published.capabilityVersionId(),Long.MAX_VALUE)));
            org.junit.jupiter.api.Assertions.assertNull(references.publishedRevision(tenant,unpublished.id()));
            org.junit.jupiter.api.Assertions.assertNull(references.publishedRevision(tenant,Long.MAX_VALUE));
            assertTrue(references.publishedForCapability(tenant,Long.MAX_VALUE).isEmpty());
            assertTrue(references.activePublishedForCapabilityVersions(tenant,List.of()).isEmpty());
            org.junit.jupiter.api.Assertions.assertNull(references.publishedRevision(Long.parseLong(other),published.id()));
            // 真实宿主resolver经能力五字段SELECT、知识定向JOIN，返回唯一精确PINNED身份。
            var resolved=actualResolver.resolveKnowledgeMount(tenant,followActive).orElseThrow();
            assertEquals(followActive.capability(),resolved.capability());
            assertEquals(new com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig(
                    com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig.BindingMode.PINNED_REVISION,
                    published.id().toString()),resolved.config());
            assertEquals(followActive.mountId(),resolved.mountId());
            assertEquals(followActive.tenantId(),resolved.tenantId());
            assertEquals(followActive.agentId(),resolved.agentId());
            assertEquals(followActive.priority(),resolved.priority());
            assertEquals(followActive.revision(),resolved.revision());
            assertEquals(followActive.active(),resolved.active());
            assertEquals(3,observed.size());
            for(var row:observed){org.junit.jupiter.api.Assertions.assertNull(row.getSourceVersions());org.junit.jupiter.api.Assertions.assertNull(row.getCapabilityPolicy());}
            System.out.printf("knowledge-reference same-input: completeJsonUtf8Bytes=%d projectedJsonUtf8Bytes=0%n",materialBytes);
        });
        TenantContext.setTenantId(other);
        tx.executeWithoutResult(status->{
            jdbc.execute("SET LOCAL ROLE "+reader);
            // 故意保留原租户参数；真实RLS必须在SQL身份过滤之外隐藏该行。
            org.junit.jupiter.api.Assertions.assertNull(references.publishedRevision(tenant,published.id()));
            assertTrue(references.publishedForCapability(tenant,published.capabilityVersionId()).isEmpty());
            assertTrue(references.activePublishedForCapabilityVersions(tenant,List.of(published.capabilityVersionId())).isEmpty());
            assertTrue(actualResolver.resolveKnowledgeMount(tenant,followActive).isEmpty());
        });
    }

    @Test
    void knowledgeBaseRevisionPublishesAndAtomicallyArchivesPreviousVersion() {
        float[] vector = new float[1536];
        whenEmbeddingIsAvailable(vector);
        try {
            TenantContext.setTenantId(tenantId);
            var base = knowledgeService.createBase(tenantId, "Revision 测试库", null);
            Long kbId = Long.valueOf(String.valueOf(base.id()));
            var firstDocument = knowledgeService.uploadFile(
                    tenantId, kbId, "第一版", textFile("first.txt", "第一版内容"));
            String firstSourceVersion = String.valueOf(firstDocument.sourceVersion());

            var draft = revisionService.create(tenantId, kbId);
            var ready = revisionService.markReady(
                    tenantId, kbId, draft.revision(), List.of(firstSourceVersion), policy());
            var published = revisionService.publish(tenantId, kbId, ready.revision());
            assertEquals("published", published.status());

            var secondDocument = knowledgeService.uploadFile(
                    tenantId, kbId, "第二版", textFile("second.txt", "第二版内容"));
            String secondSourceVersion = String.valueOf(secondDocument.sourceVersion());
            var activeItemsBeforeSecondPublish = knowledgeService.search(
                    tenantId, String.valueOf(kbId), "第二版", 5, null);
            assertFalse(activeItemsBeforeSecondPublish.stream()
                    .map(item -> item.content())
                    .anyMatch("第二版内容"::equals));

            var secondDraft = revisionService.create(tenantId, kbId);
            revisionService.markReady(tenantId, kbId, secondDraft.revision(),
                    List.of(firstSourceVersion, secondSourceVersion), policy());
            var secondPublished = revisionService.publish(tenantId, kbId, secondDraft.revision());
            var activeItemsAfterSecondPublish = knowledgeService.search(
                    tenantId, String.valueOf(kbId), "第二版", 5, null);

            assertEquals("published", secondPublished.status());
            assertTrue(activeItemsAfterSecondPublish.stream()
                    .map(item -> item.content())
                    .anyMatch("第二版内容"::equals));
            var revisions = revisionService.list(tenantId, kbId).items();
            assertEquals(2, revisions.size());
            assertEquals("published", revisions.get(0).status());
            assertEquals("archived", revisions.get(1).status());
            assertEquals(secondPublished.id(),
                    knowledgeService.getBase(tenantId, kbId).activeRevisionId());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void scopedBaseCountsQueryOnlyVisibleIdsAndKeepSharedHistoryAndDeletedDocumentsOut() {
        whenEmbeddingIsAvailable(new float[1536]);
        TenantContext.setTenantId(tenantId);
        Long visible = knowledgeService.createBase(tenantId, "可见库", null).id();
        Long hidden = knowledgeService.createBase(tenantId, "不可见库", null).id();
        var live = knowledgeService.uploadFile(tenantId, visible, "当前文档", textFile("visible.txt", "当前分块"));
        knowledgeService.updateDocument(tenantId, live.documentId(), "新标题", null, live.sourceVersion());
        var removed = knowledgeService.uploadFile(tenantId, visible, "已删除文档", textFile("deleted.txt", "已删除分块"));
        knowledgeService.deleteDocument(tenantId, removed.documentId(), removed.sourceVersion());
        knowledgeService.uploadFile(tenantId, hidden, "其他库文档", textFile("hidden.txt", "其他库分块"));

        var listed = knowledgeService.listBases(tenantId, List.of(visible));
        assertEquals(1, listed.total());
        assertEquals(visible, listed.items().getFirst().id());
        assertEquals(1L, listed.items().getFirst().documentCount());
        assertEquals(1L, listed.items().getFirst().chunkCount());
        // 直接执行相同Mapper SQL，结果集只能包含调用方传入的知识库。
        var documents = documentCounts.countByBase(Long.parseLong(tenantId), List.of(visible));
        var chunks = chunkCounts.countCurrentByBase(Long.parseLong(tenantId), List.of(visible));
        assertEquals(List.of(new com.skillforge.core.knowledge.model.KnowledgeBaseCount(visible, 1L)), documents);
        assertEquals(List.of(new com.skillforge.core.knowledge.model.KnowledgeBaseCount(visible, 1L)), chunks);
        assertEquals(0, knowledgeService.listBases(tenantId, List.of(SnowflakeIds.nextId())).total());
        assertEquals(0, knowledgeService.listBases(tenantId, List.of()).total());
        assertBaseCounts(knowledgeService.getBase(tenantId, hidden), 1L, 1L);
    }

    @Test
    void baseCountsExcludeHistoricalAndDeletedChunksAndStayWithinTheTenant() {
        whenEmbeddingIsAvailable(new float[1536]);
        try {
            TenantContext.setTenantId(tenantId);
            Long first = Long.valueOf(String.valueOf(knowledgeService.createBase(tenantId, "第一库", null).id()));
            Long second = Long.valueOf(String.valueOf(knowledgeService.createBase(tenantId, "第二库", null).id()));
            Long empty = Long.valueOf(String.valueOf(knowledgeService.createBase(tenantId, "空库", null).id()));
            var live = knowledgeService.uploadFile(tenantId, first, "当前文档",
                    textFile("live.txt", "当前分块"));
            Long documentId = Long.valueOf(String.valueOf(live.documentId()));
            // 标题追加元数据版本并复用分块集合，历史逻辑版本仍可精确解析。
            var current = knowledgeService.updateDocument(tenantId, documentId, "新标题", null,
                    String.valueOf(live.sourceVersion()));
            var deleted = knowledgeService.uploadFile(tenantId, first, "删除文档",
                    textFile("deleted.txt", "软删除分块"));
            knowledgeService.deleteDocument(tenantId, Long.valueOf(String.valueOf(deleted.documentId())),
                    String.valueOf(deleted.sourceVersion()));
            knowledgeService.uploadFile(tenantId, second, "其他库文档", textFile("other.txt", "其他库分块"));

            assertFalse(live.sourceVersion().equals(current.sourceVersion()));
            assertEquals(2L, queryCount(jdbc, tenantId,
                    "SELECT count(*) FROM nw_knowledge_chunks WHERE tenant_id=? AND kb_id=?",
                    Long.parseLong(tenantId), first));
            assertEquals(1L, queryCount(jdbc, tenantId,
                    "SELECT count(*) FROM knowledge_chunk_values WHERE tenant_id=? AND document_id=? AND source_version=?",
                    Long.parseLong(tenantId), documentId, live.sourceVersion()));
            assertEquals(1L, queryCount(jdbc, tenantId,
                    "SELECT count(*) FROM nw_knowledge_documents WHERE tenant_id=? AND kb_id=? AND deleted_at IS NOT NULL",
                    Long.parseLong(tenantId), first));
            assertBaseCounts(knowledgeService.getBase(tenantId, first), 1L, 1L);
            assertBaseCounts(knowledgeService.getBase(tenantId, second), 1L, 1L);
            assertBaseCounts(knowledgeService.getBase(tenantId, empty), 0L, 0L);
            var listed = knowledgeService.listBases(tenantId).items();
            assertEquals(3, listed.size());
            for (var baseItem : listed) {
                long expected = empty.equals(baseItem.id()) ? 0L : 1L;
                assertEquals(expected, baseItem.documentCount());
                assertEquals(expected, baseItem.chunkCount());
            }

            TenantContext.clear();
            String otherTenant = createTenant(tenantMapper, planMapper, "counts-" + SnowflakeIds.nextId());
            TenantContext.setTenantId(otherTenant);
            Long otherBase = Long.valueOf(String.valueOf(knowledgeService.createBase(otherTenant, "其他租户库", null).id()));
            knowledgeService.uploadFile(otherTenant, otherBase, "其他租户文档", textFile("tenant.txt", "其他租户分块"));
            assertBaseCounts(knowledgeService.getBase(otherTenant, otherBase), 1L, 1L);
            assertEquals(1, (knowledgeService.listBases(otherTenant).items()).size());
            TenantContext.setTenantId(tenantId);
            assertEquals(3, (knowledgeService.listBases(tenantId).items()).size());
            assertBaseCounts(knowledgeService.getBase(tenantId, first), 1L, 1L);
        } finally {
            TenantContext.clear();
        }
    }

    private void assertBaseCounts(com.skillforge.core.knowledge.dto.response.KnowledgeBaseDetailResponse base, long documents, long chunks) {
        assertEquals(documents, base.documentCount());
        assertEquals(chunks, base.chunkCount());
    }

    @Test
    void projectionPreparationIsOutsideWritesAndPublishFailureRollsBackEveryFactAndIndex() {
        whenEmbeddingIsAvailable(new float[1536]);
        when(embeddingProvider.embedBatch(anyString(), anyString(), anyLong(), anyList()))
                .thenAnswer(invocation -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    return ((List<String>) invocation.getArgument(3)).stream().map(text -> new float[1536]).toList();
                });
        try {
            TenantContext.setTenantId(tenantId);
            var base = knowledgeService.createBase(tenantId, "投影原子性", null);
            String proposalId = "proposal-" + SnowflakeIds.nextId();
            var proposal = institutionalProposal(proposalId, base.id());
            org.mockito.Mockito.doThrow(new IllegalStateException("冻结发布故障"))
                    .when(revisionService).publish(tenantId, base.id(), 1L);
            assertThrows(IllegalStateException.class, () -> projections.project(tenantId, proposal));
            assertEquals(0L, queryCount(jdbc, tenantId,
                    "SELECT count(*) FROM nw_knowledge_governance_projections WHERE tenant_id=? AND proposal_id=?",
                    Long.parseLong(tenantId), proposalId));
            for (String table : List.of("nw_knowledge_documents", "nw_knowledge_document_versions", "nw_knowledge_chunks", "nw_knowledge_base_revisions")) {
                assertEquals(0L, queryCount(jdbc, tenantId,
                        "SELECT count(*) FROM " + table + " WHERE tenant_id=? AND kb_id=?", Long.parseLong(tenantId), base.id()));
            }
            org.mockito.Mockito.verify(searchIndexer, org.mockito.Mockito.never()).indexChunks(anyString(), anyList());
            org.mockito.Mockito.doCallRealMethod().when(revisionService).publish(tenantId, base.id(), 1L);
            var applied = projections.project(tenantId, proposal);
            assertEquals("applied", applied.status());
            org.mockito.Mockito.clearInvocations(embeddingProvider, searchIndexer);
            var replay = projections.project(tenantId, proposal);
            assertEquals(applied.reference(), replay.reference());
            org.mockito.Mockito.verify(embeddingProvider, org.mockito.Mockito.never())
                    .embedBatch(anyString(), anyString(), anyLong(), anyList());
            org.mockito.Mockito.verify(searchIndexer, org.mockito.Mockito.never()).indexChunks(anyString(), anyList());
        } finally { TenantContext.clear(); }
    }

    @Test
    void duplicateProjectionClaimDoesNotAbortThePostgresWriteTransaction() {
        try {
            TenantContext.setTenantId(tenantId);
            var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            tx.executeWithoutResult(status -> {
                var row = new com.skillforge.core.knowledge.model.KnowledgeGovernanceProjectionDO();
                row.setId(SnowflakeIds.nextId()); row.setTenantId(Long.parseLong(tenantId));
                row.setProposalId("claim-" + SnowflakeIds.nextId()); row.setProjectionType("institutional_knowledge");
                row.setStatus("applied"); row.setDetails(java.util.Map.of("revision", 1));
                row.setCreatedAt(java.time.Instant.now()); row.setUpdatedAt(java.time.Instant.now());
                assertEquals(1, projectionMapper.insertClaim(row));
                row.setId(SnowflakeIds.nextId());
                assertEquals(0, projectionMapper.insertClaim(row));
                var winner = projectionMapper.lockByProposal(Long.parseLong(tenantId), row.getProposalId());
                assertEquals("applied", winner.getStatus());
                assertEquals(1, ((Number) winner.getDetails().get("revision")).intValue());
            });
        } finally { TenantContext.clear(); }
    }

    @Test
    void documentSummaryAndManifestSqlKeepCurrentVersionAndTenantBeforeTopK() {
        whenEmbeddingIsAvailable(new float[1536]);
        when(embeddingProvider.embedBatch(anyString(), anyString(), anyLong(), anyList())).thenReturn(null);
        try {
            TenantContext.setTenantId(tenantId);
            var base = knowledgeService.createBase(tenantId, "列表窄投影", null);
            var first = knowledgeService.uploadFile(tenantId, base.id(), "旧标题", textFile("first.txt", "needle obsolete"));
            var updated = knowledgeService.updateDocument(tenantId, first.documentId(), "当前标题", "needle current", first.sourceVersion());
            var second = knowledgeService.uploadFile(tenantId, base.id(), "第二文档", textFile("second.txt", "needle second"));
            var currentChunks=knowledgeService.listChunks(tenantId,first.documentId(),1,20);
            assertEquals(1,currentChunks.total());assertEquals(updated.sourceVersion(),currentChunks.items().getFirst().sourceVersion());
            assertEquals("needle current",currentChunks.items().getFirst().content());assertFalse(currentChunks.items().getFirst().hasEmbedding());
            var list = knowledgeService.listDocuments(tenantId, base.id(),
                    new com.skillforge.core.knowledge.dto.query.KnowledgeDocumentQuery(1, 10, "ready", null));
            assertEquals(2L, list.total());
            assertTrue(list.items().stream().anyMatch(item -> item.title().equals("当前标题") && item.sourceVersion().equals(updated.sourceVersion())));
            assertFalse(list.items().stream().anyMatch(item -> item.sourceVersion().equals(first.sourceVersion())));
            knowledgeService.updateBase(tenantId, base.id(), null, null, null,
                    new KnowledgeService.KnowledgeBaseSettingsUpdate(null, null, "economical", null, null, null, null, null, null));
            var hits = knowledgeService.search(tenantId, base.id().toString(), "needle", 2, null);
            assertEquals(Set.of(updated.sourceVersion(), second.sourceVersion()), hits.stream().map(hit -> hit.sourceVersion()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(1, knowledgeService.search(tenantId, base.id().toString(), "needle", 1, null).size());
        } finally { TenantContext.clear(); }
    }

    @Autowired private com.skillforge.core.knowledge.KnowledgeRebuildService rebuilds;
    @Autowired private com.skillforge.core.knowledge.mapper.KnowledgeRebuildJobMapper rebuildJobs;
    @Autowired private org.apache.ibatis.session.SqlSessionFactory knowledgeSqlSessions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.skillforge.core.capability.CapabilityProducerPublicationBridge catalogPublication;

    @Test
    void wholeBaseRebuildFreezesWritesThenManuallySwitchesDimensionsAndKeepsPinnedOldModel() {
        float[] oldVector=new float[1536];oldVector[0]=1;whenEmbeddingIsAvailable(oldVector);
        try {
            TenantContext.setTenantId(tenantId);
            var base=knowledgeService.createBase(tenantId,"重建测试",null);
            var document=knowledgeService.uploadFile(tenantId,base.id(),"版本测试",textFile("test.txt","embedding rebuild searchable content"));
            var model=rebuildModel(768);
            org.mockito.Mockito.doAnswer(call->{List<String> text=call.getArgument(3);float[] vector=new float[768];vector[0]=1;return text.stream().map(v->vector).toList();})
                    .when(embeddingProvider).embedBatch(anyString(),org.mockito.ArgumentMatchers.eq(model.id()),anyLong(),anyList());
            var job=rebuilds.create(tenantId,base.id(),model.id(),model.revision(),"create-rebuild",rebuilds.latest(tenantId,base.id()).baseRevision());
            assertEquals(job.jobId(),rebuilds.create(tenantId,base.id(),model.id(),model.revision(),"create-rebuild",rebuilds.latest(tenantId,base.id()).baseRevision()).jobId());
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->knowledgeService.updateDocument(tenantId,document.documentId(),null,"blocked",document.sourceVersion()));
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->revisionService.create(tenantId,base.id()));
            assertEquals(new com.skillforge.core.knowledge.mapper.KnowledgeRebuildJobMapper.Progress(0,0,0),rebuildJobs.progress(Long.parseLong(tenantId),Long.MAX_VALUE));
            assertEquals(1,job.documentCount());assertEquals(0,job.readyDocuments());assertEquals(0,job.unknownBatches());
            rebuilds.run(tenantId,job.jobId());var ready=rebuilds.latest(tenantId,base.id());assertEquals("READY",ready.status());
            assertEquals(1,ready.documentCount());assertEquals(1,ready.readyDocuments());assertEquals(0,ready.unknownBatches());
            assertEquals(new com.skillforge.core.knowledge.mapper.KnowledgeRebuildJobMapper.Progress(1,1,0),rebuildJobs.progress(Long.parseLong(tenantId),job.jobId()));
            assertEquals(document.sourceVersion(),queryValue(jdbc,tenantId,"select current_source_version from nw_knowledge_documents where id=?",String.class,document.documentId()));
            assertEquals(1536,queryValue(jdbc,tenantId,"select embedding_dimensions from nw_knowledge_bases where id=?",Integer.class,base.id()));
            var published=rebuilds.publish(tenantId,base.id(),job.jobId(),ready.revision(),ready.baseRevision(),policy());assertEquals("PUBLISHED",published.status());
            assertEquals(768,queryValue(jdbc,tenantId,"select embedding_dimensions from nw_knowledge_bases where id=?",Integer.class,base.id()));
            assertEquals(2,queryValue(jdbc,tenantId,"select count(*) from nw_knowledge_document_versions where document_id=?",Integer.class,document.documentId()));
            assertEquals(1,queryValue(jdbc,tenantId,"select count(*) from knowledge_chunk_values where source_version=? and vector_dims(embedding)=1536",Integer.class,document.sourceVersion()));
            assertEquals(2,queryValue(jdbc,tenantId,"select count(*) from nw_knowledge_chunk_embeddings where tenant_id=?",Integer.class,Long.parseLong(tenantId)));
            assertEquals(0,queryValue(jdbc,tenantId,"select count(*) from nw_knowledge_rebuild_batch_embeddings where tenant_id=?",Integer.class,Long.parseLong(tenantId)));
            org.mockito.Mockito.clearInvocations(embeddingProvider);
            knowledgeService.search(tenantId,base.id().toString(),"embedding",5,new KnowledgeSearchRequest(base.id().toString(),"embedding",5,"semantic",false,.1,.5,false,document.sourceVersion()));
            org.mockito.Mockito.verify(embeddingProvider).embed(anyString(),org.mockito.ArgumentMatchers.argThat(id->!id.equals(model.id())),anyLong(),anyString());
        } finally {TenantContext.clear();}
    }

    @Test
    void incompleteEmbeddingIsUnknownAndExplicitCostConsentIsRequiredToRecompute() {
        float[] vector=new float[1536];vector[0]=1;whenEmbeddingIsAvailable(vector);
        try {
            TenantContext.setTenantId(tenantId);var base=knowledgeService.createBase(tenantId,"未知回执",null);
            knowledgeService.uploadFile(tenantId,base.id(),"test",textFile("test.txt","embedding unknown result"));var model=rebuildModel(1536);
            when(embeddingProvider.embedBatch(anyString(),org.mockito.ArgumentMatchers.eq(model.id()),anyLong(),anyList())).thenReturn(null);
            var created=rebuilds.create(tenantId,base.id(),model.id(),model.revision(),"unknown",rebuilds.latest(tenantId,base.id()).baseRevision());rebuilds.run(tenantId,created.jobId());
            var unknown=rebuilds.latest(tenantId,base.id());assertEquals("UNKNOWN",unknown.status());assertFalse(unknown.writeLocked());assertEquals(1,unknown.unknownBatches());
            assertEquals(1,unknown.documentCount());assertEquals(0,unknown.readyDocuments());
            assertEquals(new com.skillforge.core.knowledge.mapper.KnowledgeRebuildJobMapper.Progress(1,0,1),rebuildJobs.progress(Long.parseLong(tenantId),created.jobId()));
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->rebuilds.retry(tenantId,base.id(),unknown.jobId(),unknown.revision(),false));
            var retried=rebuilds.retry(tenantId,base.id(),unknown.jobId(),unknown.revision(),true);assertEquals("QUEUED",retried.status());
            assertEquals(1,jdbc.queryForObject("select jsonb_array_length(receipt_history) from nw_knowledge_rebuild_batches where job_id=?",Integer.class,unknown.jobId()));
            assertThrows(Exception.class,()->jdbc.update("update nw_knowledge_rebuild_batches set receipt_history='[]' where job_id=?",unknown.jobId()));
        }finally{TenantContext.clear();}
    }

    @Test
    void expiredStartedBatchIsNotSentAgainAndOldFenceCannotContinue() {
        float[] vector=new float[1536];vector[0]=1;whenEmbeddingIsAvailable(vector);
        try {
            TenantContext.setTenantId(tenantId);var base=knowledgeService.createBase(tenantId,"重启回执",null);
            knowledgeService.uploadFile(tenantId,base.id(),"test",textFile("test.txt","embedding restart"));var model=rebuildModel(768);
            var created=rebuilds.create(tenantId,base.id(),model.id(),model.revision(),"restart",rebuilds.latest(tenantId,base.id()).baseRevision());
            jdbc.update("update nw_knowledge_rebuild_jobs set status='RUNNING',fence=1,lease_until=now()-interval '1 minute' where id=?",created.jobId());
            jdbc.update("update nw_knowledge_rebuild_batches set status='STARTED' where job_id=?",created.jobId());
            org.mockito.Mockito.clearInvocations(embeddingProvider);rebuilds.run(tenantId,created.jobId());
            org.mockito.Mockito.verify(embeddingProvider,org.mockito.Mockito.never()).embedBatch(anyString(),anyString(),anyLong(),anyList());
            assertEquals("UNKNOWN",rebuilds.latest(tenantId,base.id()).status());
        }finally{TenantContext.clear();}
    }

    @Test
    void cancelledRebuildCannotPublishAndCannotRetryAfterSourceEdits() {
        float[] vector=new float[1536];vector[0]=1;whenEmbeddingIsAvailable(vector);
        try {
            TenantContext.setTenantId(tenantId);var base=knowledgeService.createBase(tenantId,"取消重建",null);
            var document=knowledgeService.uploadFile(tenantId,base.id(),"test",textFile("test.txt","embedding cancel"));var model=rebuildModel(768);
            var created=rebuilds.create(tenantId,base.id(),model.id(),model.revision(),"cancel",rebuilds.latest(tenantId,base.id()).baseRevision());var cancelled=rebuilds.cancel(tenantId,base.id(),created.jobId(),created.revision());
            assertFalse(cancelled.writeLocked());assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->rebuilds.publish(tenantId,base.id(),created.jobId(),cancelled.revision(),cancelled.baseRevision(),policy()));
            knowledgeService.updateDocument(tenantId,document.documentId(),null,"edited content",document.sourceVersion());
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->rebuilds.retry(tenantId,base.id(),created.jobId(),cancelled.revision(),true));
        }finally{TenantContext.clear();}
    }

    @Test
    void rebuildPublicationBridgeFailureRollsBackEveryVisiblePointerAndJobState() {
        float[] vector=new float[1536];vector[0]=1;whenEmbeddingIsAvailable(vector);
        try {
            TenantContext.setTenantId(tenantId);var base=knowledgeService.createBase(tenantId,"发布回滚",null);
            var document=knowledgeService.uploadFile(tenantId,base.id(),"test",textFile("test.txt","embedding publish rollback"));var model=rebuildModel(1536);
            Long originalModel=queryValue(jdbc,tenantId,"select embedding_model_config_id from nw_knowledge_bases where id=?",Long.class,base.id());
            var created=rebuilds.create(tenantId,base.id(),model.id(),model.revision(),"rollback",rebuilds.latest(tenantId,base.id()).baseRevision());rebuilds.run(tenantId,created.jobId());
            var ready=rebuilds.latest(tenantId,base.id());assertEquals("READY",ready.status());
            org.mockito.Mockito.doThrow(new IllegalStateException("catalog publication failed"))
                    .when(catalogPublication).publish(org.mockito.ArgumentMatchers.any());
            assertThrows(IllegalStateException.class,()->rebuilds.publish(tenantId,base.id(),ready.jobId(),ready.revision(),ready.baseRevision(),policy()));
            assertEquals("READY",rebuilds.latest(tenantId,base.id()).status());
            assertEquals(ready.baseRevision(),rebuilds.latest(tenantId,base.id()).baseRevision());
            assertTrue(rebuilds.latest(tenantId,base.id()).writeLocked());
            assertEquals(document.sourceVersion(),queryValue(jdbc,tenantId,"select current_source_version from nw_knowledge_documents where id=?",String.class,document.documentId()));
            assertEquals(originalModel,queryValue(jdbc,tenantId,"select embedding_model_config_id from nw_knowledge_bases where id=?",Long.class,base.id()));
            assertEquals(0,queryValue(jdbc,tenantId,"select count(*) from nw_knowledge_base_revisions where kb_id=?",Integer.class,base.id()));
            assertEquals(0,queryValue(jdbc,tenantId,"select count(*) from nw_capability_definitions where tenant_id=? and capability_key=?",Integer.class,Long.parseLong(tenantId),"knowledge-base:"+base.id()));
        }finally{org.mockito.Mockito.doCallRealMethod().when(catalogPublication).publish(org.mockito.ArgumentMatchers.any());TenantContext.clear();}
    }

    @Test
    void cancellationDuringEmbeddingRejectsLateWorkerResultWithoutCreatingReadySource() {
        float[] vector=new float[1536];vector[0]=1;whenEmbeddingIsAvailable(vector);
        try {
            TenantContext.setTenantId(tenantId);var base=knowledgeService.createBase(tenantId,"迟到worker",null);
            knowledgeService.uploadFile(tenantId,base.id(),"test",textFile("test.txt","embedding late worker"));var model=rebuildModel(1536);
            var created=rebuilds.create(tenantId,base.id(),model.id(),model.revision(),"late",rebuilds.latest(tenantId,base.id()).baseRevision());
            java.util.concurrent.atomic.AtomicBoolean invoked=new java.util.concurrent.atomic.AtomicBoolean();
            org.mockito.Mockito.doAnswer(call->{
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                var running=rebuilds.latest(tenantId,base.id());assertEquals("RUNNING",running.status());
                rebuilds.cancel(tenantId,base.id(),created.jobId(),running.revision());invoked.set(true);
                return ((List<String>)call.getArgument(3)).stream().map(text->vector).toList();
            }).when(embeddingProvider).embedBatch(anyString(),org.mockito.ArgumentMatchers.eq(model.id()),anyLong(),anyList());
            rebuilds.run(tenantId,created.jobId());assertTrue(invoked.get());
            var cancelled=rebuilds.latest(tenantId,base.id());assertEquals("CANCELLED",cancelled.status());assertEquals(1,cancelled.unknownBatches());
            assertEquals(1,jdbc.queryForObject("select count(*) from nw_knowledge_document_versions where kb_id=?",Integer.class,base.id()));
            assertEquals(0,jdbc.queryForObject("select count(*) from nw_knowledge_rebuild_batches where job_id=? and status='READY'",Integer.class,created.jobId()));
            rebuilds.run(tenantId,created.jobId());assertEquals("CANCELLED",rebuilds.latest(tenantId,base.id()).status());
        }finally{TenantContext.clear();}
    }

    @Test
    void rebuildJobItemsAndReceiptsAreInvisibleToAnotherTenant() {
        float[] vector=new float[1536];vector[0]=1;whenEmbeddingIsAvailable(vector);
        try {
            TenantContext.setTenantId(tenantId);var base=knowledgeService.createBase(tenantId,"RLS重建",null);
            knowledgeService.uploadFile(tenantId,base.id(),"test",textFile("test.txt","embedding tenant"));var model=rebuildModel(1536);
            var created=rebuilds.create(tenantId,base.id(),model.id(),model.revision(),"rls",rebuilds.latest(tenantId,base.id()).baseRevision());
            TenantContext.clear();String other=createTenant(tenantMapper,planMapper,"rebuild-other-"+SnowflakeIds.nextId());TenantContext.setTenantId(other);
            // Testcontainers 管理连接为超级用户；显式降权并在同一事务绑定租户才验证真实 RLS。
            jdbc.execute("CREATE ROLE knowledge_rebuild_reader NOLOGIN NOSUPERUSER NOBYPASSRLS");
            jdbc.execute("GRANT USAGE ON SCHEMA public TO knowledge_rebuild_reader");
            jdbc.execute("GRANT SELECT ON nw_knowledge_rebuild_jobs,nw_knowledge_rebuild_items,nw_knowledge_rebuild_batches TO knowledge_rebuild_reader");
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                boolean autoCommit=connection.getAutoCommit();connection.setAutoCommit(false);
                try(var role=connection.createStatement()) {
                    role.execute("SET LOCAL ROLE knowledge_rebuild_reader");
                    for(String selectedTenant:List.of(tenantId,other)) {
                        try(var bind=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                            bind.setString(1,selectedTenant);bind.execute();
                        }
                        for(String table:List.of("nw_knowledge_rebuild_jobs","nw_knowledge_rebuild_items","nw_knowledge_rebuild_batches")) {
                            String column=table.equals("nw_knowledge_rebuild_jobs")?"id":"job_id";
                            try(var query=connection.prepareStatement("select count(*) from "+table+" where "+column+"=?")) {
                                query.setLong(1,created.jobId());
                                try(var rows=query.executeQuery()) {
                                    assertTrue(rows.next());assertEquals(selectedTenant.equals(tenantId)?1:0,rows.getInt(1));
                                }
                            }
                        }
                        var parameters=Map.<String,Object>of("tenantId",Long.parseLong(tenantId),"jobId",created.jobId());
                        var statement=knowledgeSqlSessions.getConfiguration().getMappedStatement("com.skillforge.core.knowledge.mapper.KnowledgeRebuildJobMapper.progress");
                        var bound=statement.getBoundSql(parameters);
                        try(var query=connection.prepareStatement(bound.getSql())) {
                            new org.apache.ibatis.scripting.defaults.DefaultParameterHandler(statement,parameters,bound).setParameters(query);
                            try(var rows=query.executeQuery()) {
                                assertTrue(rows.next());assertEquals(selectedTenant.equals(tenantId)?1:0,rows.getInt("document_count"));
                                assertEquals(0,rows.getLong("ready_documents"));assertEquals(0,rows.getLong("unknown_batches"));
                            }
                        }
                    }
                } finally {connection.rollback();connection.setAutoCommit(autoCommit);}
                return null;
            });
            assertEquals(new com.skillforge.core.knowledge.mapper.KnowledgeRebuildJobMapper.Progress(0,0,0),rebuildJobs.progress(Long.parseLong(other),created.jobId()));
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->rebuilds.latest(other,base.id()));
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->rebuilds.run(other,created.jobId()));
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->rebuilds.cancel(other,base.id(),created.jobId(),created.revision()));
            TenantContext.setTenantId(tenantId);assertEquals("QUEUED",rebuilds.latest(tenantId,base.id()).status());
        }finally{TenantContext.clear();}
    }

    @Test
    void staleBaseControlRevisionCannotCreateOrPublishFrozenSourceSet() {
        float[] vector=new float[1536];vector[0]=1;whenEmbeddingIsAvailable(vector);
        try {
            TenantContext.setTenantId(tenantId);var base=knowledgeService.createBase(tenantId,"控制CAS",null);
            var document=knowledgeService.uploadFile(tenantId,base.id(),"test",textFile("test.txt","embedding control"));var model=rebuildModel(1536);
            long stale=rebuilds.latest(tenantId,base.id()).baseRevision();
            knowledgeService.updateDocument(tenantId,document.documentId(),null,"changed content",document.sourceVersion());
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->rebuilds.create(tenantId,base.id(),model.id(),model.revision(),"stale",stale));
            long beforeLock=rebuilds.latest(tenantId,base.id()).baseRevision();
            var created=rebuilds.create(tenantId,base.id(),model.id(),model.revision(),"correct",beforeLock);rebuilds.run(tenantId,created.jobId());
            var ready=rebuilds.latest(tenantId,base.id());assertEquals("READY",ready.status());
            assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->rebuilds.publish(tenantId,base.id(),ready.jobId(),ready.revision(),beforeLock,policy()));
            assertEquals("READY",rebuilds.latest(tenantId,base.id()).status());
        }finally{TenantContext.clear();}
    }

    private com.skillforge.core.model.ModelView rebuildModel(int dimensions){
        var model = models.create(new ModelWrite(null,"目标重建模型","EMBEDDING",true,"custom","knowledge-embedding-fixture","knowledge-fixture-v1","openai-embeddings","bearer-api-key",
                "https://embedding.example/v1","target-embedding","test-secret",null,null,null,null,null,30,dimensions,null,null,null,null,"CNY"),"knowledge-rebuild-integration");
        return models.publish(model.id(), model.revision(), "knowledge-rebuild-integration");
    }

    private com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal institutionalProposal(String proposalId, Long kbId) {
        var payload = java.util.Map.<String, Object>of("projection_type", "institutional_knowledge",
                "institutional_knowledge", java.util.Map.of("kb_id", kbId, "title", "审批知识", "content", "完整审批知识正文",
                        "capability_policy", java.util.Map.of("model_invocable", true, "user_invocable", false,
                                "allowed_operators", List.of("VECTOR"), "freshness_policy", "PINNED", "citation_required", true)));
        return new com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal(proposalId,
                com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal.Target.INSTITUTIONAL_KNOWLEDGE,
                "event-" + proposalId, "bundle-" + proposalId, payload,
                com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal.Status.APPROVED);
    }

    /** 完整三版来源集合，发布清单独立选择各文档第二版，最新版为冲突干扰。 */
    private record ComplexSources(long baseId, List<Long> documentIds, List<String> oldSources,
            List<String> publishedSources, List<String> latestSources, List<String> expectedContents) { }

    /** 创建六文档各三版；Oracle 正文和指定来源由输入保存，不从检索输出反推。 */
    private ComplexSources complexSources() {
        whenEmbeddingIsAvailable(new float[1536]);
        TenantContext.setTenantId(tenantId);
        var base = knowledgeService.createBase(tenantId, "复杂多来源政策", null);
        List<Long> documents = new java.util.ArrayList<>();
        List<String> old = new java.util.ArrayList<>(), published = new java.util.ArrayList<>(), latest = new java.util.ArrayList<>();
        List<String> expected = new java.util.ArrayList<>();
        for (int document = 1; document <= 6; document++) {
            var first = knowledgeService.uploadFile(tenantId, base.id(), "政策" + document,
                    textFile("policy-" + document + ".txt", "complexneedle 文档" + document + "旧版：预算100元，未生效，需财务审批。"));
            String approved = "complexneedle 文档" + document + "批准版：预算" + (document * 125) + ".50元，2026年10月1日生效，例外需负责人复核。";
            var second = knowledgeService.updateDocument(tenantId, first.documentId(), "批准政策" + document, approved, first.sourceVersion());
            var third = knowledgeService.updateDocument(tenantId, first.documentId(), "未授权新版" + document,
                    "complexneedle 文档" + document + "新版：预算999999元，尚未批准，不可替代当前政策。", second.sourceVersion());
            documents.add(first.documentId()); old.add(first.sourceVersion()); published.add(second.sourceVersion()); latest.add(third.sourceVersion()); expected.add(approved);
        }
        var draft = revisionService.create(tenantId, base.id());
        revisionService.markReady(tenantId, base.id(), draft.revision(), published,
                new KnowledgeCapabilityPolicyRequest(true, false, Set.of("KEYWORD", "VECTOR"), "PINNED", true));
        revisionService.publish(tenantId, base.id(), draft.revision());
        return new ComplexSources(base.id(), List.copyOf(documents), List.copyOf(old), List.copyOf(published), List.copyOf(latest), List.copyOf(expected));
    }

    @Test
    void complexEighteenVersionsQueryEveryAuthorizedDocumentAndPreserveOldPinnedFacts() {
        var fixture = complexSources();
        var hits = knowledgeService.search(tenantId, String.valueOf(fixture.baseId()), "complexneedle", 100,
                new KnowledgeSearchRequest(String.valueOf(fixture.baseId()), "complexneedle", 100, "full_text", false, null, null, false));
        assertEquals(Set.copyOf(fixture.publishedSources()), hits.stream().map(hit -> hit.sourceVersion()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.copyOf(fixture.expectedContents()), hits.stream().map(hit -> hit.content()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(6, hits.size());
        assertEquals(18, queryCount(jdbc, tenantId, "select count(*) from nw_knowledge_document_versions where tenant_id=? and kb_id=?", Long.valueOf(tenantId), fixture.baseId()));
        var pinned = knowledgeService.search(tenantId, String.valueOf(fixture.baseId()), "complexneedle", 100,
                new KnowledgeSearchRequest(String.valueOf(fixture.baseId()), "complexneedle", 100, "full_text", false, null, null, false, fixture.oldSources().get(4)));
        assertEquals(1, pinned.size());
        assertEquals(fixture.oldSources().get(4), pinned.getFirst().sourceVersion());
        assertTrue(pinned.getFirst().content().contains("旧版"));
    }

    @Test
    void complexManifestRejectsForeignTenantAndKeepsTopKInsideTheAuthorizedPool() {
        var fixture = complexSources();
        String foreign = createTenant(tenantMapper, planMapper, "complex-foreign-" + SnowflakeIds.nextId());
        TenantContext.setTenantId(foreign);
        var foreignBase = knowledgeService.createBase(foreign, "同名政策干扰", null);
        var foreignSource = knowledgeService.uploadFile(foreign, foreignBase.id(), "错误预算",
                textFile("foreign.txt", "complexneedle 预算999999元，其他租户信息不得注入。"));
        assertThrows(com.skillforge.core.shared.exception.ApiException.class, () -> knowledgeService.getBase(foreign, fixture.baseId()));
        TenantContext.setTenantId(tenantId);
        var draft = revisionService.create(tenantId, fixture.baseId());
        List<String> invalid = new java.util.ArrayList<>(fixture.publishedSources()); invalid.add(foreignSource.sourceVersion());
        assertThrows(com.skillforge.core.shared.exception.ApiException.class, () -> revisionService.markReady(tenantId, fixture.baseId(), draft.revision(), invalid,
                new KnowledgeCapabilityPolicyRequest(true, false, Set.of("KEYWORD"), "PINNED", true)));
        var hits = knowledgeService.search(tenantId, String.valueOf(fixture.baseId()), "complexneedle", 2,
                new KnowledgeSearchRequest(String.valueOf(fixture.baseId()), "complexneedle", 2, "full_text", false, null, null, false));
        assertEquals(2, hits.size());
        assertTrue(hits.stream().allMatch(hit -> fixture.publishedSources().contains(hit.sourceVersion())));
        assertFalse(hits.stream().anyMatch(hit -> hit.content().contains("999999")));
    }

    @Test
    void complexPublicationSwitchesWholeManifestWhileStaleDocumentCasLeavesEverySourceUntouched() {
        var fixture = complexSources();
        for (int i = 0; i < 6; i++) {
            final int document = i;
            assertThrows(com.skillforge.core.shared.exception.ApiException.class, () -> knowledgeService.updateDocument(tenantId,
                    fixture.documentIds().get(document), "stale overwrite", "complexneedle 错误覆盖", fixture.publishedSources().get(document)));
        }
        var next = revisionService.create(tenantId, fixture.baseId());
        revisionService.markReady(tenantId, fixture.baseId(), next.revision(), fixture.latestSources(),
                new KnowledgeCapabilityPolicyRequest(true, false, Set.of("KEYWORD"), "PINNED", true));
        revisionService.publish(tenantId, fixture.baseId(), next.revision());
        var after = knowledgeService.search(tenantId, String.valueOf(fixture.baseId()), "complexneedle", 100,
                new KnowledgeSearchRequest(String.valueOf(fixture.baseId()), "complexneedle", 100, "full_text", false, null, null, false));
        assertEquals(Set.copyOf(fixture.latestSources()), after.stream().map(hit -> hit.sourceVersion()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(18, queryCount(jdbc, tenantId, "select count(*) from nw_knowledge_document_versions where tenant_id=? and kb_id=?", Long.valueOf(tenantId), fixture.baseId()));
        assertEquals(6, queryCount(jdbc, tenantId, "select count(*) from nw_knowledge_documents where tenant_id=? and kb_id=?", Long.valueOf(tenantId), fixture.baseId()));
    }

    /** 分块变更只读取版本元数据，新正文与未变向量引用保持真实版本契约。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"high_quality", "economical"})
    void chunkMutationsDeriveNewBodiesWithoutReadingOldBodyAndPreserveHistoricalFacts(String indexingMode) {
        boolean highQuality = "high_quality".equals(indexingMode);
        if (highQuality) whenEmbeddingIsAvailable(new float[1536]);
        TenantContext.setTenantId(tenantId);
        var base = highQuality ? knowledgeService.createBase(tenantId, "分块正文按需读取", null)
                : knowledgeService.createBase(tenantId, "经济分块正文按需读取", null, "economical", "full_text", false);
        knowledgeService.updateBase(tenantId, base.id(), null, null, null,
                new KnowledgeService.KnowledgeBaseSettingsUpdate(20, 0, null, null, null, null, null, null, null));
        String source = "alpha body first\n\nbeta body second\n\ngamma body third";
        var uploaded = knowledgeService.uploadFile(tenantId, base.id(), "原始元数据", textFile("chunk-body.txt", source));
        var original = knowledgeService.getDocument(tenantId, uploaded.documentId());
        var chunks = knowledgeService.listChunks(tenantId, uploaded.documentId(), 1, 200).items();
        assertTrue(chunks.size() > 1);
        var originalFacts = queryRows(jdbc, tenantId,
                "SELECT chunk_index,content,embedding_id FROM knowledge_chunk_values WHERE tenant_id=? AND document_id=? AND source_version=? ORDER BY chunk_index",
                Long.parseLong(tenantId), uploaded.documentId(), uploaded.sourceVersion());
        for (var row : originalFacts) assertEquals(highQuality, row.get("embedding_id") != null);
        org.mockito.Mockito.clearInvocations(contentReads);
        var changed = knowledgeService.updateChunk(tenantId, chunks.getFirst().id(), "changed first body", uploaded.sourceVersion());
        org.mockito.Mockito.verify(contentReads, org.mockito.Mockito.never()).documentContent(anyLong(), anyLong(), anyLong());
        var expectedChanged = new java.util.ArrayList<>(chunks.stream().map(chunk -> chunk.content()).toList());
        expectedChanged.set(0, "changed first body");
        var edited = knowledgeService.getDocument(tenantId, uploaded.documentId());
        assertEquals(String.join("\n\n", expectedChanged), edited.content());
        assertEquals(changed.sourceVersion(), edited.sourceVersion());
        assertEquals(original.title(), edited.title()); assertEquals(original.sourceType(), edited.sourceType());
        assertEquals(original.originalFilename(), edited.originalFilename()); assertEquals(original.extension(), edited.extension());
        assertEquals(original.sizeBytes(), edited.sizeBytes()); assertEquals(original.parsedBy(), edited.parsedBy());
        org.mockito.Mockito.verify(contentReads).documentContent(anyLong(), anyLong(), anyLong());
        var editedFacts = queryRows(jdbc, tenantId,
                "SELECT chunk_index,content,embedding_id FROM knowledge_chunk_values WHERE tenant_id=? AND document_id=? AND source_version=? ORDER BY chunk_index",
                Long.parseLong(tenantId), uploaded.documentId(), changed.sourceVersion());
        assertEquals(originalFacts.size(), editedFacts.size());
        for (var row : editedFacts) assertEquals(highQuality, row.get("embedding_id") != null);
        assertEquals(originalFacts.subList(1, originalFacts.size()), editedFacts.subList(1, editedFacts.size()));
        org.mockito.Mockito.clearInvocations(contentReads);
        knowledgeService.deleteChunk(tenantId, changed.id(), changed.sourceVersion());
        org.mockito.Mockito.verify(contentReads, org.mockito.Mockito.never()).documentContent(anyLong(), anyLong(), anyLong());
        var deleted = knowledgeService.getDocument(tenantId, uploaded.documentId());
        assertEquals(String.join("\n\n", expectedChanged.subList(1, expectedChanged.size())), deleted.content());
        assertFalse(changed.sourceVersion().equals(deleted.sourceVersion()));
        assertEquals(original.title(), deleted.title()); assertEquals(original.originalFilename(), deleted.originalFilename());
        org.mockito.Mockito.verify(contentReads).documentContent(anyLong(), anyLong(), anyLong());
        var retained = queryRows(jdbc, tenantId,
                "SELECT chunk_index,content,embedding_id FROM knowledge_chunk_values WHERE tenant_id=? AND document_id=? AND source_version=? ORDER BY chunk_index",
                Long.parseLong(tenantId), uploaded.documentId(), deleted.sourceVersion());
        assertEquals(originalFacts.size() - 1, retained.size());
        for (int index = 0; index < retained.size(); index++) {
            assertEquals(index, ((Number) retained.get(index).get("chunk_index")).intValue());
            assertEquals(originalFacts.get(index + 1).get("content"), retained.get(index).get("content"));
            assertEquals(originalFacts.get(index + 1).get("embedding_id"), retained.get(index).get("embedding_id"));
        }
        assertEquals(originalFacts, queryRows(jdbc, tenantId,
                "SELECT chunk_index,content,embedding_id FROM knowledge_chunk_values WHERE tenant_id=? AND document_id=? AND source_version=? ORDER BY chunk_index",
                Long.parseLong(tenantId), uploaded.documentId(), uploaded.sourceVersion()));
        assertEquals(source, queryValue(jdbc, tenantId,
                "SELECT c.content FROM nw_knowledge_document_versions v JOIN nw_knowledge_document_contents c ON c.tenant_id=v.tenant_id AND c.document_id=v.document_id AND c.id=v.content_id WHERE v.tenant_id=? AND v.document_id=? AND v.source_version=?",
                String.class, Long.parseLong(tenantId), uploaded.documentId(), uploaded.sourceVersion()));
        assertEquals(chunks.size() - 1, knowledgeService.listChunks(tenantId, uploaded.documentId(), 1, 200).total());
    }

    /** 配置额度后只拒绝真实新增正文，标题复用允许；拒绝使整个新版本及关系回滚。 */
    @Test void configuredStorageBudgetRejectsNewBodiesButAllowsTitleReferencesAtomically() {
        whenEmbeddingIsAvailable(new float[1536]);
        TenantContext.setTenantId(tenantId);
        var base=knowledgeService.createBase(tenantId,"正文预算",null);
        var initial=knowledgeService.uploadFile(tenantId,base.id(),"初始标题",textFile("budget.txt","protected source body"));
        long tenant=Long.parseLong(tenantId);
        var before=storage.capacity(tenant);
        assertEquals(null,before.maxContentBytes(),"默认只观测，不推断生产额度");
        storage.configureBudget(tenant,before.payloadBytes()-1,before.chunkBodies());
        var title=knowledgeService.updateDocument(tenantId,initial.documentId(),"只改变标题",null,initial.sourceVersion());
        assertEquals(before.payloadBytes(),storage.capacity(tenant).payloadBytes());
        var chunk=knowledgeService.listChunks(tenantId,initial.documentId(),1,20).items().getFirst();
        assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                ()->knowledgeService.updateChunk(tenantId,chunk.id(),"larger changed source body",title.sourceVersion()));
        var after=storage.capacity(tenant);
        assertEquals(before.payloadBytes(),after.payloadBytes());assertEquals(before.chunkBodies(),after.chunkBodies());
        assertEquals(before.sourceVersions()+1,after.sourceVersions());
        assertEquals(title.sourceVersion(),knowledgeService.getDocument(tenantId,initial.documentId()).sourceVersion());
        storage.configureBudget(tenant,null,null);
        assertEquals(null,storage.capacity(tenant).maxContentBytes());
    }

    /** 明确截止时间只移除无引用元数据；当前和历史发布继续持有唯一正文与集合。 */
    @Test void explicitHistoryRecyclingPreservesPublishedAndCurrentReferencesAndSharedContent() {
        whenEmbeddingIsAvailable(new float[1536]);TenantContext.setTenantId(tenantId);
        var base=knowledgeService.createBase(tenantId,"引用保护",null);
        var initial=knowledgeService.uploadFile(tenantId,base.id(),"初始标题",textFile("retain.txt","immutable protected knowledge"));
        var draft=revisionService.create(tenantId,base.id());
        revisionService.markReady(tenantId,base.id(),draft.revision(),List.of(initial.sourceVersion()),policy());
        revisionService.publish(tenantId,base.id(),draft.revision());
        var retired=knowledgeService.updateDocument(tenantId,initial.documentId(),"已替换标题",null,initial.sourceVersion());
        var current=knowledgeService.updateDocument(tenantId,initial.documentId(),"当前标题",null,retired.sourceVersion());
        long tenant=Long.parseLong(tenantId);var before=storage.capacity(tenant);
        var boundary=java.time.Instant.now().plusSeconds(1);
        var candidates=storage.previewRecycling(tenant,base.id(),boundary,100);
        assertEquals(1,candidates.size());
        var reclaimed=storage.recycle(tenant,base.id(),boundary,100);
        assertEquals(1,reclaimed.versions());assertEquals(0,reclaimed.sets());
        assertEquals(0,reclaimed.embeddings());assertEquals(0,reclaimed.chunkContents());assertEquals(0,reclaimed.documentContents());
        assertEquals(before.payloadBytes(),storage.capacity(tenant).payloadBytes());
        assertEquals(current.sourceVersion(),knowledgeService.getDocument(tenantId,initial.documentId()).sourceVersion());
        assertEquals(1,queryCount(jdbc,tenantId,"SELECT count(*) FROM knowledge_chunk_values WHERE tenant_id=? AND source_version=?",tenant,initial.sourceVersion()));
        assertEquals(0,storage.recycle(tenant,base.id(),boundary,100).versions());
    }

    /** 未确认索引阻止历史回收；得到原持有者确认后精确删除意图才可重试并收尾。 */
    @Test void projectionUnknownHoldProtectsSourceAndCleanupIntentSurvivesUnconfirmedDelete() {
        whenEmbeddingIsAvailable(new float[1536]);TenantContext.setTenantId(tenantId);
        when(searchIndexer.indexingEnabled()).thenReturn(true);
        when(searchIndexer.indexChunks(anyString(),anyList())).thenReturn(false);
        var base=knowledgeService.createBase(tenantId,"索引引用保护",null);
        var initial=knowledgeService.uploadFile(tenantId,base.id(),"初始标题",textFile("index.txt","immutable index source"));
        long tenant=Long.parseLong(tenantId);
        assertEquals(1,storage.capacity(tenant).indexProjectionHolds());
        when(searchIndexer.indexingEnabled()).thenReturn(false);
        knowledgeService.updateDocument(tenantId,initial.documentId(),"当前标题",null,initial.sourceVersion());
        var boundary=java.time.Instant.now().plusSeconds(1);
        assertTrue(storage.previewRecycling(tenant,base.id(),boundary,100).isEmpty());
        when(searchIndexer.indexingEnabled()).thenReturn(true);
        when(searchIndexer.indexChunks(anyString(),anyList())).thenReturn(true);
        knowledgeService.repairProjection(tenantId,base.id(),initial.sourceVersion());
        assertEquals(1,storage.capacity(tenant).indexProjectionHolds(),"新修复确认不能解除旧UNKNOWN持有者");
        long originalHold=queryValue(jdbc,tenantId,"SELECT id FROM nw_knowledge_index_projection_holds WHERE tenant_id=?",Long.class,tenant);
        // 受控夹具提供原操作的明确确认；生产路径不能从新修复或时间推定此确认。
        indexes.finish(tenant,originalHold,true);
        assertEquals(1,storage.recycle(tenant,base.id(),boundary,100).versions());
        assertEquals(1,storage.capacity(tenant).indexCleanupIntents());
        when(searchIndexer.deleteSourceVersion(tenantId,base.id().toString(),initial.sourceVersion())).thenReturn(false);
        assertEquals(0,indexes.drain(tenant,1));assertEquals(1,storage.capacity(tenant).indexCleanupIntents());
        int scheduled=tenantTransactions(jdbc).execute(ignored -> {
            AbstractIntegrationTest.queryCount(jdbc,tenantId,"SELECT 1");
            return jdbc.update("UPDATE nw_knowledge_index_cleanup_intents SET next_attempt_at=now() WHERE tenant_id=?",tenant);
        });
        assertEquals(1,scheduled);
        when(searchIndexer.deleteSourceVersion(tenantId,base.id().toString(),initial.sourceVersion())).thenReturn(true);
        assertEquals(1,indexes.drain(tenant,1));assertEquals(0,storage.capacity(tenant).indexCleanupIntents());
    }

    /** 本类诊断沿用原业务DataSource，事务级租户绑定与读取共用事务。 */
    protected static long queryCount(JdbcTemplate jdbc, String tenantId, String sql, Object... args) {
        return tenantTransactions(jdbc).execute(
                ignored -> AbstractIntegrationTest.queryCount(jdbc, tenantId, sql, args));
    }

    protected static List<Map<String, Object>> queryRows(
            JdbcTemplate jdbc, String tenantId, String sql, Object... args) {
        return tenantTransactions(jdbc).execute(
                ignored -> AbstractIntegrationTest.queryRows(jdbc, tenantId, sql, args));
    }

    /** 保留JdbcTemplate原有单行及类型检查，在同事务内先绑定诊断租户。 */
    private static <T> T queryValue(JdbcTemplate jdbc, String tenantId, String sql, Class<T> type, Object... args) {
        return tenantTransactions(jdbc).execute(ignored -> {
            AbstractIntegrationTest.queryCount(jdbc, tenantId, "SELECT 1");
            return jdbc.queryForObject(sql, type, args);
        });
    }

    private static TransactionTemplate tenantTransactions(JdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "knowledge_test_datasource_missing")));
    }

    private void whenEmbeddingIsAvailable(float[] vector) {
        var published = models.create(new ModelWrite(null, "知识检索测试模型", "EMBEDDING", true,
                "custom", "knowledge-embedding-fixture", "knowledge-fixture-v1", "openai-embeddings", "bearer-api-key", "https://embedding.example/v1", "test-embedding",
                "test-secret", null, null, null, null, null, 30, vector.length,
                null, null, null, null, "CNY"), "knowledge-integration");
        models.publish(published.id(), published.revision(), "knowledge-integration");
        when(embeddingProvider.config(anyString())).thenReturn(models.readRevision(published.id(), published.revision()));
        when(embeddingProvider.embed(anyString(), anyString(), anyLong(), anyString())).thenReturn(vector);
        when(embeddingProvider.embedBatch(anyString(), anyString(), anyLong(), anyList()))
                .thenAnswer(invocation -> {
                    List<String> texts = invocation.getArgument(3);
                    return texts.stream().map(ignored -> vector).toList();
                });
    }

    private KnowledgeCapabilityPolicyRequest policy() {
        return new KnowledgeCapabilityPolicyRequest(
                true, false, Set.of("VECTOR"), "PINNED", true);
    }

    private MockMultipartFile textFile(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
