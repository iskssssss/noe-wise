package com.skillforge.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.core.knowledge.dto.query.KnowledgeDocumentQuery;
import com.skillforge.core.knowledge.dto.request.KnowledgeSearchRequest;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeChunkMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeDocumentMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.skillforge.core.knowledge.model.KnowledgeBaseDO;
import com.skillforge.core.knowledge.model.KnowledgeBaseCount;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.knowledge.model.KnowledgeBaseRevisionDO;
import com.skillforge.core.knowledge.model.KnowledgeChunkDO;
import com.skillforge.core.knowledge.model.KnowledgeDocumentDO;
import com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO;
import com.skillforge.core.knowledge.constant.KnowledgeConstants;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.knowledge.search.SearchIndexer;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.mock.web.MockMultipartFile;

class KnowledgeServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseDO.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentDO.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentVersionDO.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeChunkDO.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseRevisionDO.class);
    }

    private KnowledgeBaseMapper baseMapper;
    private KnowledgeBaseRevisionMapper revisionMapper;
    private KnowledgeDocumentMapper documentMapper;
    private KnowledgeDocumentVersionMapper documentVersionMapper;
    private KnowledgeChunkMapper chunkMapper;
    private EmbeddingProvider embeddingProvider;
    private RerankProvider rerankProvider;
    private SearchIndexer searchIndexer;
    private KnowledgeOperationalSettings operationalSettings;
    private com.skillforge.core.knowledge.mapper.KnowledgeStorageMapper storageMapper;
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        baseMapper = mock(KnowledgeBaseMapper.class);
        revisionMapper = mock(KnowledgeBaseRevisionMapper.class);
        documentMapper = mock(KnowledgeDocumentMapper.class);
        documentVersionMapper = mock(KnowledgeDocumentVersionMapper.class);
        org.mockito.Mockito.lenient().when(documentVersionMapper.liveSourceVersions(anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.<String>anyList())).thenAnswer(invocation -> invocation.getArgument(2));
        chunkMapper = mock(KnowledgeChunkMapper.class);
        embeddingProvider = mock(EmbeddingProvider.class);
        rerankProvider = mock(RerankProvider.class);
        searchIndexer = mock(SearchIndexer.class);
        operationalSettings = mock(KnowledgeOperationalSettings.class);
        storageMapper = mock(com.skillforge.core.knowledge.mapper.KnowledgeStorageMapper.class);
        org.mockito.Mockito.lenient().when(storageMapper.lockBudget(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient().when(storageMapper.capacity(anyLong())).thenReturn(
                new com.skillforge.core.knowledge.model.KnowledgeStorageCapacity(0,0,0,0,0,null,null,0,0,0,0,0,0));
        org.mockito.Mockito.lenient().when(storageMapper.documentContent(anyLong(),anyLong(),anyLong())).thenReturn("chunk content");
        org.mockito.Mockito.lenient().when(revisionMapper.lockBase(anyLong(),anyLong())).thenReturn(base());
        when(operationalSettings.capture()).thenReturn(settings());
        when(embeddingProvider.config("7")).thenReturn(embeddingModel());
        service = new KnowledgeService(baseMapper, revisionMapper, documentMapper, documentVersionMapper, chunkMapper,
                embeddingProvider, rerankProvider, searchIndexer, operationalSettings,
                new KnowledgeStorageService(storageMapper,revisionMapper,mock(KnowledgeIndexLifecycleService.class)), new TestTransactionManager());
        when(documentMapper.compareAndSetCurrentVersion(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(1);
        when(documentMapper.installInitialVersion(anyLong(), anyLong(), anyString())).thenReturn(1);
        when(documentMapper.softDelete(anyLong(), anyLong(), anyString())).thenReturn(1);
        when(documentVersionMapper.updateById(any(KnowledgeDocumentVersionDO.class))).thenReturn(1);
        org.mockito.Mockito.lenient().when(documentVersionMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            LambdaQueryWrapper<KnowledgeDocumentVersionDO> query=invocation.getArgument(0);query.getSqlSegment();
            return query.getParamNameValuePairs().values().stream().filter(value->value instanceof String text && !text.equals("ready"))
                .map(value->{var row=version();row.setSourceVersion(value.toString());return row;}).toList();
        });
    }

    @Test
    void economicalCreationNeedsNoEmbeddingAndRejectsInvalidModesBeforeInsert() {
        service.createBase("7", "keyword-only", "no vector", "economical", "full_text", false);
        var inserted = ArgumentCaptor.forClass(KnowledgeBaseDO.class);
        verify(baseMapper).insert(inserted.capture());
        assertEquals("economical", inserted.getValue().getIndexingMode());
        assertEquals("full_text", inserted.getValue().getRetrievalMode());
        assertNull(inserted.getValue().getEmbeddingModelConfigId());
        assertNull(inserted.getValue().getEmbeddingModelRevision());
        assertNull(inserted.getValue().getEmbeddingDimensions());
        verifyNoInteractions(embeddingProvider);
        clearInvocations(baseMapper);
        assertThrows(ApiException.class, () -> service.createBase("7", "invalid", null, "economical", "semantic", false));
        assertThrows(ApiException.class, () -> service.createBase("7", "invalid", null, "economical", "full_text", true));
        assertThrows(ApiException.class, () -> service.createBase("7", "invalid", null, "invalid", "full_text", false));
        verifyNoInteractions(baseMapper, embeddingProvider);
    }

    @Test
    void managesBasesAndRejectsInvalidUpdates() {
        KnowledgeBaseDO base = base();
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        when(baseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(base));
        when(documentMapper.countByBase(eq(7L), any())).thenReturn(List.of(new KnowledgeBaseCount(10L, 1L)));
        when(documentMapper.baseIdsWithDocuments(eq(7L), any())).thenReturn(List.of(10L));
        when(chunkMapper.countCurrentByBase(eq(7L), any())).thenReturn(List.of(new KnowledgeBaseCount(10L, 1L)));

        var listed = service.listBases("7");
        assertEquals(1, listed.total());
        assertEquals(1L, listed.items().get(0).documentCount());
        assertFalse(listed.items().getFirst().canDelete());
        assertEquals(10L, service.getBase("7", 10L).id());
        assertFalse(service.getBase("7", 10L).canDelete());

        KnowledgeService.KnowledgeBaseSettingsUpdate settings =
                new KnowledgeService.KnowledgeBaseSettingsUpdate(20, 2, "economical", "full_text",
                        3, true, .7, .8, true);
        var updated = service.updateBase("7", 10L, "renamed", "new description",
                KnowledgeConstants.BASE_STATUS_ARCHIVED, settings);
        assertEquals("renamed", updated.name());
        assertEquals("economical", updated.indexingMode());
        verify(baseMapper).updateById(base);

        assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                () -> service.updateBase("7", 10L, null, null, "invalid", null));

        var created = service.createBase("7", "new base", "desc");
        assertEquals("new base", created.name());
        var createdBase = org.mockito.ArgumentCaptor.forClass(KnowledgeBaseDO.class);
        verify(baseMapper).insert(createdBase.capture());
        assertEquals(81L, createdBase.getValue().getEmbeddingModelConfigId());
        assertEquals(3L, createdBase.getValue().getEmbeddingModelRevision());
        assertEquals(2, createdBase.getValue().getEmbeddingDimensions());

        when(documentMapper.baseIdsWithDocuments(7L, List.of(10L))).thenReturn(List.of());
        service.deleteBase("7", 10L);
        verify(baseMapper).deleteById(10L);
        assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                () -> service.createBase("7", " ", null));
    }

    @Test
    void chunkManagementReadsEmbeddingIdentityWithoutHydratingVectorBodies() {
        var document = document();
        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(document);
        var embedded = chunk(); embedded.setEmbedding(null); embedded.setEmbeddingId(91L);
        var keywordOnly = chunk(); keywordOnly.setId(32L); keywordOnly.setChunkIndex(1); keywordOnly.setEmbedding(null); keywordOnly.setEmbeddingId(null);
        var page = new Page<KnowledgeChunkDO>(1, 2); page.setTotal(2); page.setRecords(List.of(embedded, keywordOnly));
        when(chunkMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);
        var result = service.listChunks("7", document.getId(), 1, 2);
        assertEquals(2, result.total()); assertTrue(result.items().getFirst().hasEmbedding());
        org.junit.jupiter.api.Assertions.assertFalse(result.items().getLast().hasEmbedding());
        assertEquals(embedded.getContent(), result.items().getFirst().content());
        assertEquals(document.getCurrentSourceVersion(), result.items().getFirst().sourceVersion());
        var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(chunkMapper).selectPage(any(Page.class), query.capture());
        var selected = java.util.Arrays.asList(query.getValue().getSqlSelect().split(","));
        assertTrue(selected.contains("embedding_id")); org.junit.jupiter.api.Assertions.assertFalse(selected.contains("embedding"));
        verifyNoInteractions(embeddingProvider, searchIndexer);
    }

    @Test
    void documentTitleFilterStripsWhitespaceAndKeepsLiteralCharactersWithoutRetrieval() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(documentVersionMapper.selectCurrentPage(any(Page.class), eq(7L), eq(10L), any(), any()))
                .thenAnswer(call -> new Page<com.skillforge.core.knowledge.model.KnowledgeDocumentSummary>(1, 20));
        service.listDocuments("7", 10L, new KnowledgeDocumentQuery(1, 20, "failed", "  Budget%_\\  "));
        verify(documentVersionMapper).selectCurrentPage(any(Page.class), eq(7L), eq(10L), eq("failed"), eq("Budget%_\\"));
        service.listDocuments("7", 10L, new KnowledgeDocumentQuery(1, 20, null, " \t\u2003 "));
        verify(documentVersionMapper).selectCurrentPage(any(Page.class), eq(7L), eq(10L), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());
        verifyNoInteractions(embeddingProvider, searchIndexer);
    }

    @Test
    void managesDocumentsChunksAndFileUpload() {
        KnowledgeBaseDO base = base();
        KnowledgeDocumentDO document = document();
        KnowledgeDocumentVersionDO version = version();
        KnowledgeChunkDO chunk = chunk();
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(document);
        when(documentVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);
        when(documentVersionMapper.selectCurrentPage(any(Page.class), anyLong(), anyLong(), any(), any()))
                .thenAnswer(invocation -> {
                    Page<com.skillforge.core.knowledge.model.KnowledgeDocumentSummary> page = new Page<>(1, 20);
                    page.setTotal(1);
                    page.setRecords(List.of(new com.skillforge.core.knowledge.model.KnowledgeDocumentSummary(
                            version.getDocumentId(), version.getTitle(), version.getSourceType(), version.getStatus(),
                            version.getSourceVersion(), version.getChunkCount(), version.getCreatedAt())));
                    return page;
                });
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk));
        when(chunkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(chunk);
        Page<KnowledgeChunkDO> chunkPage = new Page<>(1, 20);
        chunkPage.setTotal(1);
        chunkPage.setRecords(List.of(chunk));
        when(chunkMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(chunkPage);

        var docs = service.listDocuments("7", 10L,
                new KnowledgeDocumentQuery(0, 500, KnowledgeConstants.DOC_STATUS_READY, null));
        assertEquals(1L, docs.total());
        assertEquals(1, docs.items().get(0).chunkCount());
        assertEquals("title", service.getDocument("7", 20L).title());
        assertEquals(1L, service.listChunks("7", 20L, 1, 20).total());

        service.updateDocument("7", 20L, "new title", null, "v1");
        verify(documentMapper).compareAndSetCurrentVersion(eq(7L), eq(20L), eq("v1"), anyString());

        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello knowledge".getBytes());
        when(embeddingProvider.embedBatch(eq("7"), eq("81"), eq(3L), any()))
                .thenReturn(List.of(new float[]{.1f, .2f}));
        var upload = service.uploadFile("7", 10L, null, file);
        assertNotNull(upload.documentId());
        verify(searchIndexer, org.mockito.Mockito.atLeastOnce()).indexChunks(eq("7"), any());

        service.deleteDocument("7", 20L, document.getCurrentSourceVersion());
        verify(documentMapper).softDelete(eq(7L), eq(20L), anyString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "chunk content")
    void titleChangeReusesOriginalChunkSetWhenBodyIsOmittedOrUnchanged(String submittedContent) {
        var document = document();
        var current = version();
        current.setChunkCount(2);
        var first = chunk();
        first.setContent("chunk ");
        var second = chunk();
        second.setId(31L);
        second.setChunkIndex(1);
        second.setContent("content");
        second.setContentId(95L);
        second.setEmbeddingId(96L);
        second.setEmbedding("[0.3,0.4]");
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(document);
        when(documentVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(current);
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(first, second));

        service.updateDocument("7", 20L, "new title", submittedContent, "v1");

        var inserted = ArgumentCaptor.forClass(KnowledgeDocumentVersionDO.class);
        verify(documentVersionMapper).insert(inserted.capture());
        var next = inserted.getValue();
        assertEquals("new title", next.getTitle());
        assertEquals(current.getContent(), next.getContent());
        assertEquals(current.getContentId(), next.getContentId());
        assertEquals(current.getChunkSetId(), next.getChunkSetId());
        assertEquals(2, next.getChunkCount());
        assertEquals(KnowledgeConstants.DOC_STATUS_READY, next.getStatus());
        assertNotEquals("v1", next.getSourceVersion());
        verify(documentMapper).compareAndSetCurrentVersion(7L, 20L, "v1", next.getSourceVersion());
        ArgumentCaptor<List<Map<String, Object>>> indexed = ArgumentCaptor.forClass(List.class);
        verify(searchIndexer).indexChunks(eq("7"), indexed.capture());
        assertEquals(List.of(30L, 31L), indexed.getValue().stream().map(row -> row.get("chunk_id")).toList());
        assertEquals(List.of("chunk ", "content"), indexed.getValue().stream().map(row -> row.get("content")).toList());
        assertEquals(List.of("[0.1,0.2]", "[0.3,0.4]"), indexed.getValue().stream().map(row -> row.get("embedding")).toList());
        assertTrue(indexed.getValue().stream().allMatch(row -> next.getSourceVersion().equals(row.get("source_version"))));
        verify(storageMapper, never()).insertSet(anyLong(), anyLong(), anyLong(), anyLong());
        verify(storageMapper, never()).insertChunk(any(KnowledgeChunkDO.class));
        verify(storageMapper, never()).insertDocumentContent(anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(storageMapper, never()).insertChunkContent(anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(storageMapper, never()).insertEmbedding(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyString());
        verify(embeddingProvider, never()).embed(anyString(), anyString(), anyLong(), anyString());
        verify(embeddingProvider, never()).embedBatch(anyString(), anyString(), anyLong(), any());
        assertEquals("v1", current.getSourceVersion());
        assertEquals("title", current.getTitle());
        assertEquals("v1", first.getSourceVersion());
        assertEquals("v1", second.getSourceVersion());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "chunk content")
    void unchangedTitleAndBodyDoNotCreateAnotherSourceVersion(String submittedContent) {
        var document = document();
        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(document);
        when(documentVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version());

        var result = service.updateDocument("7", 20L, "title", submittedContent, "v1");

        assertEquals("v1", result.sourceVersion());
        assertEquals("title", result.title());
        assertEquals("chunk content", result.content());
        assertEquals("v1", document.getCurrentSourceVersion());
        verify(documentVersionMapper, never()).insert(any(KnowledgeDocumentVersionDO.class));
        verify(documentVersionMapper, never()).updateById(any(KnowledgeDocumentVersionDO.class));
        verify(documentMapper, never()).compareAndSetCurrentVersion(anyLong(), anyLong(), anyString(), anyString());
        verify(storageMapper, never()).insertDocumentContent(anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(storageMapper, never()).insertSet(anyLong(), anyLong(), anyLong(), anyLong());
        verifyNoInteractions(chunkMapper, searchIndexer);
        verify(embeddingProvider, never()).embed(anyString(), anyString(), anyLong(), anyString());
        verify(embeddingProvider, never()).embedBatch(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void uploadParseRejectionIsIdentifiedBeforeDocumentIsWritten() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        var file = new MockMultipartFile("file", "unsupported.bin", "application/octet-stream", new byte[]{1});
        var rejected = assertThrows(ApiException.class, () -> service.uploadFile("7", 10L, null, file));
        assertEquals("KNOWLEDGE_UPLOAD_REJECTED", rejected.getCode());
        verify(documentMapper, org.mockito.Mockito.never()).insert(any(KnowledgeDocumentDO.class));
    }

    @Test
    void uploadToRebuildingBaseIsRejectedBeforeParsingOrEmbedding() {
        var base = base();
        base.setRebuildJobId(99L);
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        var file = new MockMultipartFile("file", "unsupported.bin", "application/octet-stream", new byte[]{1});

        var rejected = assertThrows(ApiException.class, () -> service.uploadFile("7", 10L, null, file));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, rejected.getStatus());
        assertEquals("KNOWLEDGE_UPLOAD_REJECTED", rejected.getCode());
        assertEquals("知识库正在重建，请发布重建结果或取消重建后再试", rejected.getDetail());
        verifyNoInteractions(embeddingProvider, documentMapper, documentVersionMapper, chunkMapper, searchIndexer);
    }

    @Test
    void uploadModelFailureKeepsItsOriginalUnconfirmedContract() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        var failure = new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "embedding_dimensions_mismatch");
        when(embeddingProvider.embedBatch(eq("7"), eq("81"), eq(3L), any())).thenThrow(failure);
        var file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello knowledge".getBytes());

        var actual = assertThrows(ApiException.class, () -> service.uploadFile("7", 10L, null, file));

        org.junit.jupiter.api.Assertions.assertSame(failure, actual);
        verifyNoInteractions(documentMapper, documentVersionMapper, chunkMapper, searchIndexer);
    }

    @Test
    void uploadChunkLimitRejectsBeforeEmbeddingAndDocumentWrite() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(operationalSettings.capture()).thenReturn(new KnowledgeOperationalSettings.Snapshot(
                1, 500, 50, 10, 1, "high_quality", "semantic", 5, .5, .5));
        var file = new MockMultipartFile("file", "large.txt", "text/plain", "a".repeat(250).getBytes());
        var rejected = assertThrows(ApiException.class, () -> service.uploadFile("7", 10L, null, file));
        assertEquals("KNOWLEDGE_DOCUMENT_CHUNK_LIMIT", rejected.getCode());
        verify(documentMapper, org.mockito.Mockito.never()).insert(any(KnowledgeDocumentDO.class));
        verify(embeddingProvider, org.mockito.Mockito.never()).embedBatch(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void ingestsReindexesAndUpdatesChunksWithProviderFailuresTolerated() {
        KnowledgeBaseDO base = base();
        base.setChunkSize(100);
        KnowledgeDocumentDO document = document();
        KnowledgeDocumentVersionDO version = version();
        KnowledgeChunkDO chunk = chunk();
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(document);
        when(documentVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk));
        when(chunkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(chunk);
        when(embeddingProvider.embedBatch(eq("7"), eq("81"), eq(3L), any()))
                .thenReturn(List.of(new float[]{.1f, .2f}));
        when(embeddingProvider.embed(eq("7"), eq("81"), eq(3L), anyString()))
                .thenReturn(new float[]{.3f, .4f});
        doThrow(new IllegalStateException("optional index unavailable"))
                .when(searchIndexer).indexChunks(eq("7"), any());

        MockMultipartFile uploadFile = new MockMultipartFile(
                "file", "manual.txt", "text/plain", "a short document".getBytes());
        var ingested = service.uploadFile("7", 10L, "manual", uploadFile);
        assertEquals(1, ingested.chunks());
        assertTrue(String.valueOf(ingested.sourceVersion()).startsWith("kbv-"));

        var reindexed = service.reindexDocument("7", 20L, "v1");
        assertEquals(1, reindexed.chunks());
        assertTrue(((KnowledgeChunkDO) chunk).getEmbedding() != null);

        var changed = service.updateChunk("7", 30L, "changed text", document.getCurrentSourceVersion());
        assertEquals("changed text", changed.content());
        assertEquals(true, changed.hasEmbedding());
        verify(storageMapper, org.mockito.Mockito.atLeastOnce()).insertChunk(any(KnowledgeChunkDO.class));
        verify(chunkMapper, org.mockito.Mockito.never()).updateById(any(KnowledgeChunkDO.class));
    }

    @Test
    void unavailableEmbeddingStillWritesCompleteKeywordSearchableSourceVersions() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(document());
        when(documentVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version());
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk()));
        when(embeddingProvider.embedBatch(eq("7"), eq("81"), eq(3L), any())).thenReturn(null);

        var uploaded = service.uploadFile("7", 10L, "uploaded",
                new MockMultipartFile("file", "notes.txt", "text/plain", "first source".getBytes()));
        var projected = service.ingestProjectionDocument("7", 10L, "projected", "second source");
        service.updateDocument("7", 20L, "updated", "third source", "v1");

        assertEquals(1, uploaded.chunks());
        assertEquals(1, projected.chunks());
        var chunks = ArgumentCaptor.forClass(KnowledgeChunkDO.class);
        verify(storageMapper, times(3)).insertChunk(chunks.capture());
        assertEquals(List.of("first source", "second source", "third source"),
                chunks.getAllValues().stream().map(KnowledgeChunkDO::getContent).toList());
        chunks.getAllValues().forEach(chunk -> assertNull(chunk.getEmbedding()));
        verify(documentMapper, times(2)).installInitialVersion(eq(7L), anyLong(), anyString());
        verify(documentMapper).compareAndSetCurrentVersion(eq(7L), eq(20L), eq("v1"), anyString());
    }

    @Test
    void economicalChunkEditWithoutFrozenModelWritesKeywordBodyWithoutEmbeddingCalls() {
        var economical = base();
        economical.setIndexingMode("economical");
        economical.setRetrievalMode("full_text");
        economical.setRerankEnabled(false);
        economical.setEmbeddingModelConfigId(null);
        economical.setEmbeddingModelRevision(null);
        economical.setEmbeddingDimensions(null);
        var document = document();
        var selected = chunk(); selected.setEmbedding(null); selected.setEmbeddingId(null);
        var metadata = version(); metadata.setContent(null);
        metadata.setEmbeddingModelConfigId(null); metadata.setEmbeddingModelRevision(null); metadata.setEmbeddingDimensions(null);
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(economical);
        when(revisionMapper.lockBase(7L, 10L)).thenReturn(economical);
        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(document);
        when(documentVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(metadata);
        when(chunkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(selected);
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(selected));

        var result = service.updateChunk("7", selected.getId(), "changed keyword body", "v1");

        assertEquals("changed keyword body", result.content());
        org.junit.jupiter.api.Assertions.assertFalse(result.hasEmbedding());
        verifyNoInteractions(embeddingProvider);
        verify(storageMapper, never()).documentContent(anyLong(), anyLong(), anyLong());
        verify(storageMapper).findDocumentContent(eq(7L), eq(20L), anyString(), eq("changed keyword body"));
        var inserted = ArgumentCaptor.forClass(KnowledgeChunkDO.class);
        verify(storageMapper).insertChunk(inserted.capture());
        assertNull(inserted.getValue().getEmbedding()); assertNull(inserted.getValue().getEmbeddingId());
        assertNotEquals("v1", inserted.getValue().getSourceVersion());
    }

    @Test
    void changingOneOfMultipleChunksCreatesACompleteVersionAndReusesUnchangedEmbedding() {
        KnowledgeDocumentDO document = document();
        KnowledgeChunkDO first = chunk();
        KnowledgeChunkDO second = chunk();
        second.setId(31L);
        second.setChunkIndex(1);
        second.setContent("unchanged second");
        second.setEmbedding("[0.8,0.9]");
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(document);
        var metadata = version(); metadata.setContent(null);
        when(documentVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(metadata);
        when(chunkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(first);
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(first, second));
        when(embeddingProvider.embed(eq("7"), eq("81"), eq(3L), eq("changed first")))
                .thenReturn(new float[]{.3f, .4f});

        service.updateChunk("7", 30L, "changed first", "v1");

        ArgumentCaptor<KnowledgeChunkDO> inserted = ArgumentCaptor.forClass(KnowledgeChunkDO.class);
        verify(storageMapper, org.mockito.Mockito.times(2)).insertChunk(inserted.capture());
        assertEquals("changed first", inserted.getAllValues().get(0).getContent());
        assertEquals("[0.8,0.9]", inserted.getAllValues().get(1).getEmbedding());
        assertNotEquals("v1", inserted.getAllValues().get(0).getSourceVersion());
        assertEquals(inserted.getAllValues().get(0).getSourceVersion(),
                inserted.getAllValues().get(1).getSourceVersion());
        verify(storageMapper, never()).documentContent(anyLong(), anyLong(), anyLong());
        verify(storageMapper).findDocumentContent(eq(7L), eq(20L), anyString(), eq("changed first\n\nunchanged second"));
        var savedVersion = ArgumentCaptor.forClass(KnowledgeDocumentVersionDO.class);
        verify(documentVersionMapper).insert(savedVersion.capture());
        assertEquals("changed first\n\nunchanged second", savedVersion.getValue().getContent());
        assertEquals(metadata.getTitle(), savedVersion.getValue().getTitle());
        assertEquals(metadata.getSourceType(), savedVersion.getValue().getSourceType());
        assertEquals(metadata.getOriginalFilename(), savedVersion.getValue().getOriginalFilename());
        assertEquals(metadata.getExtension(), savedVersion.getValue().getExtension());
        assertEquals(metadata.getSizeBytes(), savedVersion.getValue().getSizeBytes());
        assertEquals(metadata.getParsedBy(), savedVersion.getValue().getParsedBy());
    }

    @Test
    void deletingAChunkCreatesACompleteRenumberedVersionWithoutDeletingHistory() {
        KnowledgeDocumentDO document = document();
        KnowledgeChunkDO first = chunk();
        KnowledgeChunkDO second = chunk();
        second.setId(31L);
        second.setChunkIndex(1);
        second.setContent("second");
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(document);
        var metadata = version(); metadata.setContent(null);
        when(documentVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(metadata);
        when(chunkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(first);
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(first, second));

        service.deleteChunk("7", 30L, "v1");

        ArgumentCaptor<KnowledgeChunkDO> inserted = ArgumentCaptor.forClass(KnowledgeChunkDO.class);
        verify(storageMapper).insertChunk(inserted.capture());
        assertEquals(0, inserted.getValue().getChunkIndex());
        assertEquals("second", inserted.getValue().getContent());
        verify(chunkMapper, org.mockito.Mockito.never()).deleteById(anyLong());
        verify(storageMapper, never()).documentContent(anyLong(), anyLong(), anyLong());
        verify(storageMapper).findDocumentContent(eq(7L), eq(20L), anyString(), eq("second"));
        var savedVersion = ArgumentCaptor.forClass(KnowledgeDocumentVersionDO.class);
        verify(documentVersionMapper).insert(savedVersion.capture());
        assertEquals("second", savedVersion.getValue().getContent());
        assertEquals(metadata.getTitle(), savedVersion.getValue().getTitle());
        assertEquals(metadata.getSourceType(), savedVersion.getValue().getSourceType());
        assertEquals(metadata.getOriginalFilename(), savedVersion.getValue().getOriginalFilename());
        assertEquals(metadata.getExtension(), savedVersion.getValue().getExtension());
        assertEquals(metadata.getSizeBytes(), savedVersion.getValue().getSizeBytes());
        assertEquals(metadata.getParsedBy(), savedVersion.getValue().getParsedBy());
    }

    @Test
    void competingCurrentVersionCasReturnsConflictAndLeavesOldPointer() {
        KnowledgeDocumentDO document = document();
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(document);
        when(documentVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version());
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk()));
        when(documentMapper.compareAndSetCurrentVersion(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(0);

        assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                () -> service.updateDocument("7", 20L, "changed", null, "v1"));
        assertEquals("v1", document.getCurrentSourceVersion());
        verify(searchIndexer, never()).indexChunks(anyString(), any());
    }

    @Test
    void searchesSemanticFullTextHybridAndPinnedActiveRevision() {
        KnowledgeBaseDO base = base();
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        when(documentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(document()));
        when(embeddingProvider.embed(eq("7"), eq("81"), eq(3L), eq("q")))
                .thenReturn(new float[]{.1f, .2f});
        Map<String, Object> vector = item("v", .9, "v1");
        Map<String, Object> keyword = item("k", 1.0, "v1");
        when(searchIndexer.searchKnnVersions(eq("7"), eq("10"), anyString(), eq(3), eq(List.of("v1"))))
                .thenReturn(List.of(vector));
        var semantic = service.search("7", "10", "q", 3,
                new KnowledgeSearchRequest("10", "q", 3, "semantic", true, .5, null, false));
        assertEquals(1, (semantic).size());

        base.setRetrievalMode(KnowledgeConstants.RETRIEVAL_MODE_FULL_TEXT);
        when(searchIndexer.searchVersions(eq("7"), eq("10"), eq("q"), eq(3), eq(List.of("v1"))))
                .thenReturn(List.of(keyword));
        assertEquals(List.of(new KnowledgeHit("10", "20", "k", "v1", "content k", 1.0, null)), service.search("7", "10", "q", 3, null));

        base.setRetrievalMode(KnowledgeConstants.RETRIEVAL_MODE_HYBRID);
        when(searchIndexer.searchKnnVersions(eq("7"), eq("10"), anyString(), eq(10), eq(List.of("v1"))))
                .thenReturn(List.of(vector));
        when(searchIndexer.searchVersions(eq("7"), eq("10"), eq("q"), eq(10), eq(List.of("v1"))))
                .thenReturn(List.of(keyword));
        var hybrid = service.search("7", "10", "q", 3,
                new KnowledgeSearchRequest("10", "q", 3, "hybrid", false, null, .5, false));
        assertEquals(2, hybrid.size());

        base.setActiveRevisionId(90L);
        KnowledgeBaseRevisionDO revision = new KnowledgeBaseRevisionDO();
        revision.setId(90L);
        revision.setTenantId(7L);
        revision.setKbId(10L);
        revision.setStatus(KnowledgeConstants.REVISION_STATUS_PUBLISHED);
        revision.setSourceVersions(List.of("v1", "v2"));
        when(revisionMapper.selectById(90L)).thenReturn(revision);
        when(searchIndexer.searchVersions("7", "10", "q", 3, List.of("v1", "v2")))
                .thenReturn(List.of(item("v1", .8, "v1"), item("v2", .7, "v2")));
        var pinned = service.search("7", "10", "q", 3,
                new KnowledgeSearchRequest("10", "q", 3, "full_text", false, null, null, false));
        assertEquals(2, pinned.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "pinned"})
    void semanticThresholdDoesNotTurnRejectedVectorCandidatesIntoKeywordHits(String scope) {
        activeBase("v1");
        when(embeddingProvider.embed("7", "81", 3L, "q")).thenReturn(new float[]{.1f, .2f});
        var source = scope.equals("pinned") ? "v1" : null;
        var request = new KnowledgeSearchRequest("10", "q", 2, "semantic", true, .5, null, false, source);
        if (source == null) {
            when(chunkMapper.vectorSearchVersions(eq("7"), eq("10"), anyString(), eq(2), eq(null), eq(List.of("v1"))))
                    .thenReturn(List.of(item("below", .49999, "v1")), List.of(item("above", .50001, "v1")), List.of());
        } else {
            when(chunkMapper.vectorSearchPinned(eq("7"), eq("10"), anyString(), eq(2), eq(null), eq("v1")))
                    .thenReturn(List.of(item("below", .49999, "v1")), List.of(item("above", .50001, "v1")), List.of());
        }
        var keyword = chunk();
        when(chunkMapper.searchKeyword("7", "10", "q", 2, List.of("v1"))).thenReturn(List.of(keyword));

        assertTrue(service.search("7", "10", "q", 2, request).isEmpty());
        verify(chunkMapper, never()).searchKeyword(anyString(), anyString(), anyString(), anyInt(), any());
        assertEquals(List.of("above"), service.search("7", "10", "q", 2, request).stream()
                .map(KnowledgeHit::chunkId).toList());
        assertEquals(List.of(String.valueOf(keyword.getId())), service.search("7", "10", "q", 2, request).stream()
                .map(KnowledgeHit::chunkId).toList());
        verify(chunkMapper).searchKeyword("7", "10", "q", 2, List.of("v1"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {
            "active,2,NULL,0,true,2",
            "pinned,2,NULL,0,true,2",
            "active,2,3,99,true,3",
            "pinned,2,3,99,true,3",
            "active,NULL,NULL,0,true,5",
            "pinned,NULL,NULL,0,true,5",
            "active,2,NULL,4,false,4"
    }, nullValues = "NULL")
    void searchLimitResolvesRequestThenBaseThenOperationalDefault(
            String scope, Integer baseLimit, Integer requestedLimit, int internalLimit,
            boolean requestPresent, int expectedLimit) {
        var base = activeBase("v1");
        base.setTopK(baseLimit);
        base.setRetrievalMode(KnowledgeConstants.RETRIEVAL_MODE_FULL_TEXT);
        String source = "pinned".equals(scope) ? "v1" : null;
        var expected = List.of(new KnowledgeHit("10", "20", "selected", "v1", "content selected", 1.0, null));
        if (source == null) {
            when(searchIndexer.searchVersions("7", "10", "q", expectedLimit, List.of("v1")))
                    .thenReturn(List.of(item("selected", 1.0, "v1")));
        } else {
            when(searchIndexer.search("7", "10", "q", expectedLimit, source))
                    .thenReturn(List.of(item("selected", 1.0, "v1")));
        }
        var request = requestPresent ? new KnowledgeSearchRequest("10", "q", requestedLimit,
                "full_text", false, null, null, false, source) : null;

        assertEquals(expected, service.search("7", "10", "q", internalLimit, request));

        if (source == null) verify(searchIndexer).searchVersions("7", "10", "q", expectedLimit, List.of("v1"));
        else verify(searchIndexer).search("7", "10", "q", expectedLimit, source);
        verifyNoInteractions(embeddingProvider, rerankProvider);
    }

    @Test
    void allKeywordFallbacksUseOneQueryWithThePinnedFilterAndCandidateLimit() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        KnowledgeChunkDO row = chunk();
        row.setSourceVersion("v1");
        when(chunkMapper.searchKeyword(eq("7"), eq("10"), eq("q"), anyInt(), eq(List.of("v1"))))
                .thenReturn(List.of(row));
        for (String mode : List.of("semantic", "full_text", "hybrid")) {
            org.mockito.Mockito.clearInvocations(chunkMapper);
            var request = new KnowledgeSearchRequest("10", "q", 2, mode,
                    false, null, 0.0, false, "v1");

            var items = service.search("7", "10", "q", 2, request);

            assertEquals(List.of(new KnowledgeHit("10", String.valueOf(row.getDocumentId()), String.valueOf(row.getId()),
                    "v1", row.getContent(), 1.0, null)), items);
            verify(chunkMapper).searchKeyword("7", "10", "q", mode.equals("hybrid") ? 10 : 2,
                    List.of("v1"));
            verify(chunkMapper, never()).selectList(any(LambdaQueryWrapper.class));
        }
    }

    @Test
    void reportsActualRerankStageWithoutChangingFallbackItems() {
        KnowledgeBaseDO base = base();
        base.setRetrievalMode(KnowledgeConstants.RETRIEVAL_MODE_FULL_TEXT);
        base.setRerankEnabled(false);
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        var request = new KnowledgeSearchRequest("10", "q", 2, "full_text", false, null, null, null, "v1");
        when(searchIndexer.search("7", "10", "q", 2, "v1")).thenReturn(List.of(item("r", .4, "v1")));
        var disabled = service.searchWithStatus("7", "10", "q", 2, request);
        assertEquals(KnowledgeRerankStatus.DISABLED, disabled.rerankStatus());
        assertEquals(1, disabled.items().size());
        verifyNoInteractions(rerankProvider);

        base.setRerankEnabled(true);
        when(rerankProvider.rerank("7", "q", List.of("content r"))).thenReturn(null);
        var degraded = service.searchWithStatus("7", "10", "q", 2, request);
        assertEquals(KnowledgeRerankStatus.DEGRADED, degraded.rerankStatus());
        assertEquals(disabled.items(), degraded.items());
        assertEquals(degraded.items(), service.search("7", "10", "q", 2, request));

        when(rerankProvider.rerank("7", "q", List.of("content r")))
                .thenReturn(List.of(new RerankProvider.RerankResult(0, .95)));
        var applied = service.searchWithStatus("7", "10", "q", 2, request);
        assertEquals(KnowledgeRerankStatus.APPLIED, applied.rerankStatus());
        assertEquals(.95, applied.items().get(0).score());
        when(rerankProvider.rerank("7", "q", List.of("content r"))).thenReturn(List.of());
        var emptyRanking = service.searchWithStatus("7", "10", "q", 2, request);
        assertEquals(KnowledgeRerankStatus.APPLIED, emptyRanking.rerankStatus());
        assertTrue(emptyRanking.items().isEmpty());

        clearInvocations(rerankProvider);
        when(searchIndexer.search("7", "10", "q", 2, "v1")).thenReturn(List.of());
        when(chunkMapper.searchKeyword("7", "10", "q", 2, List.of("v1"))).thenReturn(List.of());
        assertEquals(KnowledgeRerankStatus.NO_CANDIDATES,
                service.searchWithStatus("7", "10", "q", 2, request).rerankStatus());
        verifyNoInteractions(rerankProvider);
    }

    @Test
    void fallsBackToSqlAndAppliesRerankAndArchiveFailClosed() {
        KnowledgeBaseDO base = base();
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        when(documentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(document()));
        KnowledgeChunkDO row = chunk();
        row.setSourceVersion("v1");
        when(embeddingProvider.embed(eq("7"), eq("81"), eq(3L), eq("q")))
                .thenReturn(new float[]{.1f, .2f});
        when(searchIndexer.searchKnnVersions(eq("7"), eq("10"), anyString(), eq(2), eq(List.of("v1"))))
                .thenReturn(List.of());
        when(chunkMapper.vectorSearchVersions(eq("7"), eq("10"), anyString(), eq(2), any(), eq(List.of("v1"))))
                .thenThrow(new IllegalStateException("vector unavailable"));
        when(chunkMapper.searchKeyword("7", "10", "q", 2, List.of("v1"))).thenReturn(List.of(row));
        var fallback = service.search("7", "10", "q", 2,
                new KnowledgeSearchRequest("10", "q", 2, "semantic", false, null, null, false));
        assertEquals(1, fallback.size());

        base.setRetrievalMode(KnowledgeConstants.RETRIEVAL_MODE_FULL_TEXT);
        doThrow(new IllegalStateException("search unavailable"))
                .when(searchIndexer).searchVersions(eq("7"), eq("10"), eq("q"), eq(2), eq(List.of("v1")));
        assertEquals(1, (service.search("7", "10", "q", 2, null)).size());

        base.setRetrievalMode(KnowledgeConstants.RETRIEVAL_MODE_FULL_TEXT);
        base.setRerankEnabled(true);
        when(searchIndexer.searchVersions(eq("7"), eq("10"), eq("q"), eq(2), eq(List.of("v1"))))
                .thenReturn(List.of(item("r", .4, "v1")));
        when(rerankProvider.rerank(eq("7"), eq("q"), any()))
                .thenReturn(List.of(new RerankProvider.RerankResult(0, .95)));
        var ranked = service.search("7", "10", "q", 2, null);
        assertEquals(.95, ranked.get(0).score());
        assertEquals(.4, ranked.get(0).vectorScore().doubleValue());
        assertEquals("10", ranked.get(0).kbId());
        assertEquals("v1", ranked.get(0).sourceVersion());
        assertEquals("r", ranked.get(0).chunkId());
        verify(rerankProvider).rerank("7", "q", List.of("content r"));

        base.setStatus(KnowledgeConstants.BASE_STATUS_ARCHIVED);
        assertEquals(List.of(), service.search("7", "10", "q", 2, null));
    }

    @Test
    void returnsEmptyForInvalidActiveRevisionAndHandlesEmptyIngest() {
        KnowledgeBaseDO base = base();
        base.setActiveRevisionId(90L);
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        when(revisionMapper.selectById(90L)).thenReturn(null);
        assertEquals(List.of(), service.search("7", "10", "q", 2, null));

        base.setActiveRevisionId(null);
        base.setIndexingMode(KnowledgeConstants.INDEXING_MODE_ECONOMICAL);
        base.setChunkSize(10);
        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(document());
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.txt", "text/plain", "   ".getBytes());
        var result = service.uploadFile("7", 10L, "empty", emptyFile);
        assertEquals(0, result.chunks());
    }

    @Test
    void countsAreAggregatedAsLongsAndDetailRestrictsTheRequestedBase() {
        KnowledgeBaseDO base = base();
        KnowledgeBaseDO empty = base();
        empty.setId(11L);
        when(baseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(base, empty));
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        long largeCount = (long) Integer.MAX_VALUE + 10L;
        when(documentMapper.countByBase(7L, List.of(10L, 11L))).thenReturn(List.of(new KnowledgeBaseCount(10L, largeCount)));
        when(chunkMapper.countCurrentByBase(7L, List.of(10L, 11L))).thenReturn(List.of(new KnowledgeBaseCount(10L, largeCount + 1)));
        when(documentMapper.countByBase(7L, List.of(10L))).thenReturn(List.of(new KnowledgeBaseCount(10L, 1L)));
        when(chunkMapper.countCurrentByBase(7L, List.of(10L))).thenReturn(List.of(new KnowledgeBaseCount(10L, 2L)));
        when(documentMapper.baseIdsWithDocuments(7L, List.of(10L, 11L))).thenReturn(List.of(10L));
        when(documentMapper.baseIdsWithDocuments(7L, List.of(10L))).thenReturn(List.of(10L));

        var items = service.listBases("7").items();
        assertEquals(largeCount, items.get(0).documentCount());
        assertEquals(largeCount + 1, items.get(0).chunkCount());
        assertFalse(items.get(0).canDelete());
        assertEquals(0L, items.get(1).documentCount());
        assertEquals(0L, items.get(1).chunkCount());
        assertTrue(items.get(1).canDelete());
        var detail = service.getBase("7", 10L);
        assertEquals(1L, detail.documentCount());
        assertEquals(2L, detail.chunkCount());
        assertFalse(detail.canDelete());
        verify(documentMapper).countByBase(7L, List.of(10L));
        verify(chunkMapper).countCurrentByBase(7L, List.of(10L));
        verify(documentMapper).baseIdsWithDocuments(7L, List.of(10L));
        verify(documentMapper, never()).selectList(any());
        verify(chunkMapper, never()).selectList(any());
    }

    @Test
    void baseListCountsOnlyActualVisibleRowsAndSkipsAggregatesWhenNoneRemain() {
        when(baseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(base()));
        when(documentMapper.countByBase(7L, List.of(10L))).thenReturn(List.of(new KnowledgeBaseCount(10L, 3L)));
        when(chunkMapper.countCurrentByBase(7L, List.of(10L))).thenReturn(List.of(new KnowledgeBaseCount(10L, 4L)));
        when(documentMapper.baseIdsWithDocuments(7L, List.of(10L))).thenReturn(List.of(10L));

        var result = service.listBases("7", List.of(10L, 99L));
        assertEquals(1, result.total());
        assertEquals(3L, result.items().getFirst().documentCount());
        assertFalse(result.items().getFirst().canDelete());
        verify(documentMapper).countByBase(7L, List.of(10L));
        verify(chunkMapper).countCurrentByBase(7L, List.of(10L));
        verify(documentMapper).baseIdsWithDocuments(7L, List.of(10L));
        clearInvocations(documentMapper, chunkMapper);
        when(baseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertEquals(0, service.listBases("7", List.of(99L)).total());
        assertEquals(0, service.listBases("7", List.of()).total());
        verifyNoInteractions(documentMapper, chunkMapper);
    }

    @Test
    void queriesWholeAuthorizedManifestOnceAndReusesOnlyWithinOneSearch() {
        KnowledgeBaseDO base = activeBase("v1", "v2");
        when(embeddingProvider.embed("7", "81", 3L, "q")).thenAnswer(invocation -> {
            assertEquals("knowledge.embed", MeterScope.current().callPoint());
            return new float[]{.1f, .2f};
        });
        when(searchIndexer.searchKnnVersions(eq("7"), eq("10"), anyString(), eq(2), eq(List.of("v1", "v2"))))
                .thenReturn(List.of(item("second", .9, "v2"), item("first", .8, "v1"), item("old", .95, "obsolete")));
        var request = new KnowledgeSearchRequest("10", "q", 2, "semantic", true, .75, null, false);
        assertEquals(List.of("second", "first"), service.search("7", "10", "q", 2, request)
                .stream().map(KnowledgeHit::chunkId).toList());
        verify(embeddingProvider).embed("7", "81", 3L, "q");
        verify(searchIndexer).searchKnnVersions(eq("7"), eq("10"), anyString(), eq(2), eq(List.of("v1", "v2")));
        verify(searchIndexer, never()).searchKnn(anyString(), anyString(), anyString(), anyInt(), any());
        base.setEmbeddingModelRevision(4L);
        service.search("7", "10", "q", 2, request);
        // 发布版本固定原模型身份；下一次搜索重新计算向量，但不采用新的默认模型。
        verify(embeddingProvider, times(2)).embed("7", "81", 3L, "q");
        verify(embeddingProvider, never()).embed("7", "81", 4L, "q");
    }

    @Test
    void hybridFusesTheWholeManifestCandidatePoolOnce() {
        activeBase("v1", "v2");
        when(embeddingProvider.embed("7", "81", 3L, "q")).thenReturn(new float[]{.1f, .2f});
        when(searchIndexer.searchKnnVersions(eq("7"), eq("10"), anyString(), eq(10), eq(List.of("v1", "v2"))))
                .thenReturn(List.of(item("first", .9, "v1"), item("low-first", .1, "v1"),
                        item("second", .3, "v2"), item("low-second", .2, "v2")));
        when(searchIndexer.searchVersions("7", "10", "q", 10, List.of("v1", "v2")))
                .thenReturn(List.of(item("first", 1.0, "v1"), item("second", 1.0, "v2")));
        var items = service.search("7", "10", "q", 2,
                new KnowledgeSearchRequest("10", "q", 2, "hybrid", false, null, .5, false));
        assertEquals(List.of("first", "second"), items.stream().map(KnowledgeHit::chunkId).toList());
        assertEquals(List.of(1.0, .625), items.stream().map(KnowledgeHit::score).toList());
        verify(embeddingProvider).embed("7", "81", 3L, "q");
    }

    @Test
    void hybridKeepsSharedChunkVersionsAndFusesOnlyTheirOwnScores() {
        activeBase("v1", "v2");
        when(embeddingProvider.embed("7", "81", 3L, "q")).thenReturn(new float[]{.1f, .2f});
        when(searchIndexer.searchKnnVersions(eq("7"), eq("10"), anyString(), eq(10), eq(List.of("v1", "v2"))))
                .thenReturn(List.of(item("shared", .9, "v1"), item("shared", .1, "v2")));
        when(searchIndexer.searchVersions("7", "10", "q", 10, List.of("v1", "v2")))
                .thenReturn(List.of(item("shared", 1.0, "v1"), item("shared", 1.0, "v2")));

        var items = service.search("7", "10", "q", 2,
                new KnowledgeSearchRequest("10", "q", 2, "hybrid", false, null, .5, false));

        assertEquals(List.of("v1", "v2"), items.stream().map(KnowledgeHit::sourceVersion).toList());
        assertEquals(List.of("shared", "shared"), items.stream().map(KnowledgeHit::chunkId).toList());
        assertEquals(List.of("20", "20"), items.stream().map(KnowledgeHit::documentId).toList());
        assertEquals(List.of(1.0, .5), items.stream().map(KnowledgeHit::score).toList());
        verify(embeddingProvider).embed("7", "81", 3L, "q");
        verify(searchIndexer).searchKnnVersions(eq("7"), eq("10"), anyString(), eq(10), eq(List.of("v1", "v2")));
        verify(searchIndexer).searchVersions("7", "10", "q", 10, List.of("v1", "v2"));
    }

    @Test
    void failedQueryVectorFallsBackAcrossWholeManifestWithoutPerVersionRetries() {
        activeBase("v1", "v2", "v3", "v4");
        KnowledgeChunkDO keyword = chunk();
        when(chunkMapper.searchKeyword("7", "10", "q", 4, List.of("v1", "v2", "v3", "v4")))
                .thenReturn(List.of(keyword));
        var items = service.search("7", "10", "q", 4, null);
        assertEquals(List.of("30"), items.stream().map(KnowledgeHit::chunkId).toList());
        verify(embeddingProvider).embed("7", "81", 3L, "q");
        verify(searchIndexer, never()).searchKnnVersions(anyString(), anyString(), anyString(), anyInt(), any());
        verify(chunkMapper).searchKeyword("7", "10", "q", 4, List.of("v1", "v2", "v3", "v4"));
    }

    @Test
    void hybridEmbeddingFailureKeepsOneManifestKeywordLeg() {
        activeBase("v1", "v2");
        when(searchIndexer.searchVersions("7", "10", "q", 10, List.of("v1", "v2")))
                .thenReturn(List.of(item("first", 1.0, "v1"), item("second", 1.0, "v2")));
        var items = service.search("7", "10", "q", 2,
                new KnowledgeSearchRequest("10", "q", 2, "hybrid", false, null, .5, false));
        assertEquals(2, items.size());
        verify(embeddingProvider).embed("7", "81", 3L, "q");
        verify(searchIndexer).searchVersions("7", "10", "q", 10, List.of("v1", "v2"));
    }

    @Test
    void fullTextAndEmptyManifestDoNotConstructVectorsAndDimensionsStillFailClosed() {
        activeBase("v1", "v2");
        service.search("7", "10", "q", 2,
                new KnowledgeSearchRequest("10", "q", 2, "full_text", false, null, null, false));
        activeBase();
        service.search("7", "10", "q", 2, null);
        verify(embeddingProvider, never()).embed(anyString(), anyString(), anyLong(), anyString());

        activeBase("v1", "v2");
        when(embeddingProvider.embed("7", "81", 3L, "q")).thenReturn(new float[]{.1f});
        var failure = assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                () -> service.search("7", "10", "q", 2, null));
        assertEquals("embedding_dimensions_mismatch", failure.getMessage());
    }

    /** 十二文档各三版；只授权前六文档的第二版，旧版与后续文档均为干扰。 */
    private List<Map<String, Object>> complexThirtySixSourceRows() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int document = 1; document <= 12; document++) {
            for (int revision = 1; revision <= 3; revision++) {
                rows.add(item("chunk-" + document + "-" + revision, 1.0,
                        "document-" + document + "-v" + revision));
            }
        }
        return List.copyOf(rows);
    }

    /** 预期身份直接由输入发布清单定义，不使用检索结果构建 oracle。 */
    private List<String> complexPublishedSources() {
        return java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> "document-" + index + "-v2").toList();
    }

    @Test
    void complexThirtySixSourcesKeepEveryPublishedDocumentAndDiscardHistoricalDecoys() {
        List<String> manifest = complexPublishedSources();
        activeBase(manifest.toArray(String[]::new));
        when(searchIndexer.searchVersions("7", "10", "跨版本例外政策", 100, manifest))
                .thenReturn(complexThirtySixSourceRows());
        List<String> expectedIds = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> "chunk-" + index + "-2").toList();
        var actual = service.search("7", "10", "跨版本例外政策", 100,
                new KnowledgeSearchRequest("10", "跨版本例外政策", 100, "full_text", false, null, null, false));
        assertEquals(expectedIds, actual.stream().map(KnowledgeHit::chunkId).toList());
        assertEquals(manifest, actual.stream().map(KnowledgeHit::sourceVersion).toList());
        verify(embeddingProvider, never()).embed(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void complexManifestKeywordFallbackRetainsTenantBaseAndWholeVersionSetBeforeLimit() {
        List<String> manifest = complexPublishedSources();
        activeBase(manifest.toArray(String[]::new));
        when(searchIndexer.searchVersions(eq("7"), eq("10"), anyString(), eq(100), eq(manifest)))
                .thenThrow(new IllegalStateException("projection unavailable"));
        List<KnowledgeChunkDO> rows = new java.util.ArrayList<>();
        for (int document = 1; document <= 12; document++) {
            for (int revision = 1; revision <= 3; revision++) {
                var row = chunk(); row.setId(1000L + document * 10 + revision);
                row.setDocumentId(2000L + document); row.setSourceVersion("document-" + document + "-v" + revision);
                row.setContent("例外比例50%_原始\\路径：以已发布政策为准"); rows.add(row);
            }
        }
        when(chunkMapper.searchKeyword("7", "10", "50%_原始\\路径", 100, manifest)).thenReturn(rows);
        var actual = service.search("7", "10", "50%_原始\\路径", 100,
                new KnowledgeSearchRequest("10", "50%_原始\\路径", 100, "full_text", false, null, null, false));
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 6).mapToObj(i -> String.valueOf(1000L + i * 10 + 2)).toList(),
                actual.stream().map(KnowledgeHit::chunkId).toList());
        verify(chunkMapper).searchKeyword("7", "10", "50%_原始\\路径", 100, manifest);
    }

    @Test
    void complexRerankUsesTheAuthorizedPoolAndPreservesThresholdAndSourceProvenance() {
        List<String> manifest = complexPublishedSources();
        activeBase(manifest.toArray(String[]::new));
        when(searchIndexer.searchVersions("7", "10", "例外审批", 100, manifest)).thenReturn(complexThirtySixSourceRows());
        when(rerankProvider.rerank(eq("7"), eq("例外审批"), any())).thenReturn(List.of(
                new RerankProvider.RerankResult(5, .92), new RerankProvider.RerankResult(3, .83),
                new RerankProvider.RerankResult(1, .49), new RerankProvider.RerankResult(99, .99),
                new RerankProvider.RerankResult(-1, .99)));
        var actual = service.search("7", "10", "例外审批", 100,
                new KnowledgeSearchRequest("10", "例外审批", 100, "full_text", true, .6, null, true));
        assertEquals(List.of("chunk-6-2", "chunk-4-2"), actual.stream().map(KnowledgeHit::chunkId).toList());
        assertEquals(List.of("document-6-v2", "document-4-v2"), actual.stream().map(KnowledgeHit::sourceVersion).toList());
        assertEquals(List.of(.92, .83), actual.stream().map(KnowledgeHit::score).toList());
        verify(rerankProvider).rerank("7", "例外审批", java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(i -> "content chunk-" + i + "-2").toList());
    }

    @Test
    void unpublishedManagementSearchReportsCurrentDocumentsEvenWithoutHits() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(documentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(document()));
        var result = service.searchWithStatus("7", "10", "q", 2,
                new KnowledgeSearchRequest("10", "q", 2, "full_text", false, null, null, false));
        assertEquals(new KnowledgeService.SearchScope(KnowledgeService.SearchScopeKind.CURRENT_DOCUMENTS, null, null), result.scope());
        verify(documentMapper).selectList(any(LambdaQueryWrapper.class));
        verifyNoInteractions(revisionMapper);
    }

    @Test
    void validPublishedAndArchivedEmptyManifestsRemainPublishedScopesWithoutPreviewFallback() {
        for (String status : List.of("published", "archived")) {
            activeBase();
            var revision = new KnowledgeBaseRevisionDO();
            revision.setId(90L); revision.setTenantId(7L); revision.setKbId(10L);
            revision.setRevision(4L); revision.setStatus(status); revision.setSourceVersions(List.of());
            when(revisionMapper.selectById(90L)).thenReturn(revision);
            var result = service.searchWithStatus("7", "10", "q", 2,
                    new KnowledgeSearchRequest("10", "q", 2, "full_text", false, null, null, false));
            assertEquals(new KnowledgeService.SearchScope(KnowledgeService.SearchScopeKind.PUBLISHED_REVISION, 4L, null), result.scope());
            assertTrue(result.items().isEmpty());
        }
        verifyNoInteractions(documentMapper, searchIndexer);
        verify(revisionMapper, org.mockito.Mockito.times(2)).selectById(90L);
    }

    @Test
    void invalidActiveRevisionReportsUnavailableWithoutCurrentDocumentFallback() {
        var base = base(); base.setActiveRevisionId(90L);
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        for (String invalid : List.of("missing", "tenant", "base", "status", "manifest")) {
            var revision = new KnowledgeBaseRevisionDO();
            revision.setTenantId(invalid.equals("tenant") ? 8L : 7L);
            revision.setKbId(invalid.equals("base") ? 11L : 10L);
            revision.setStatus(invalid.equals("status") ? "ready" : "published");
            revision.setSourceVersions(invalid.equals("manifest") ? null : List.of("v1"));
            when(revisionMapper.selectById(90L)).thenReturn(invalid.equals("missing") ? null : revision);
            var result = service.searchWithStatus("7", "10", "q", 2,
                    new KnowledgeSearchRequest("10", "q", 2, "full_text", false, null, null, false));
            assertEquals(new KnowledgeService.SearchScope(KnowledgeService.SearchScopeKind.UNAVAILABLE, null, null), result.scope());
            assertTrue(result.items().isEmpty());
        }
        verifyNoInteractions(documentMapper, searchIndexer);
    }

    @Test
    void searchReceiptKeepsTheRevisionResolvedBeforeRetrieval() {
        activeBase("v1");
        var revision = new KnowledgeBaseRevisionDO();
        revision.setTenantId(7L); revision.setKbId(10L); revision.setRevision(3L);
        revision.setStatus("published"); revision.setSourceVersions(List.of("v1"));
        when(revisionMapper.selectById(90L)).thenReturn(revision);
        when(searchIndexer.searchVersions("7", "10", "q", 2, List.of("v1"))).thenAnswer(call -> {
            revision.setRevision(4L);
            revision.setSourceVersions(List.of("v2"));
            return List.of(item("hit", .8, "v1"));
        });
        var result = service.searchWithStatus("7", "10", "q", 2,
                new KnowledgeSearchRequest("10", "q", 2, "full_text", false, null, null, false));
        assertEquals(3L, result.scope().revision());
        assertEquals("v1", result.items().getFirst().sourceVersion());
        verify(revisionMapper).selectById(90L);
        verify(searchIndexer).searchVersions("7", "10", "q", 2, List.of("v1"));
    }

    @Test
    void explicitSourceBypassesRevisionPointerButInactiveBaseDoesNotSearch() {
        var base = base(); base.setActiveRevisionId(90L);
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        var request = new KnowledgeSearchRequest("10", "q", 2, "full_text", false, null, null, false, "exact-source");
        var result = service.searchWithStatus("7", "10", "q", 2, request);
        assertEquals(new KnowledgeService.SearchScope(KnowledgeService.SearchScopeKind.SOURCE_VERSION, null, "exact-source"), result.scope());
        verifyNoInteractions(revisionMapper, documentMapper);
        org.mockito.Mockito.clearInvocations(searchIndexer, chunkMapper);
        base.setStatus(KnowledgeConstants.BASE_STATUS_ARCHIVED);
        var inactive = service.searchWithStatus("7", "10", "q", 2, request);
        assertEquals(new KnowledgeService.SearchScope(KnowledgeService.SearchScopeKind.INACTIVE, null, null), inactive.scope());
        assertTrue(inactive.items().isEmpty());
        verifyNoInteractions(revisionMapper, documentMapper, searchIndexer, chunkMapper);
    }

    @Test
    void managementSearchExcludesDeletedManifestSourceBeforeElasticsearchLimitAndKeepsScope() {
        activeBase("deleted", "live");
        when(documentVersionMapper.liveSourceVersions(7L, 10L, List.of("deleted", "live"))).thenReturn(List.of("live"));
        when(searchIndexer.searchVersions("7", "10", "q", 1, List.of("live")))
                .thenReturn(List.of(item("remaining", .8, "live")));
        var result = service.searchWithStatus("7", "10", "q", 1,
                new KnowledgeSearchRequest("10", "q", 1, "full_text", false, null, null, false));
        assertEquals(List.of("live"), result.items().stream().map(KnowledgeHit::sourceVersion).toList());
        assertEquals(KnowledgeService.SearchScopeKind.PUBLISHED_REVISION, result.scope().kind());
        assertEquals(1L, result.scope().revision());
        verify(searchIndexer).searchVersions("7", "10", "q", 1, List.of("live"));
        verify(searchIndexer, never()).searchVersions("7", "10", "q", 1, List.of("deleted", "live"));
    }

    @Test
    void explicitlyDeletedSourceReturnsEmptyBeforeEmbeddingOrSearchWithoutChangingRequestedScope() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(documentVersionMapper.liveSourceVersions(7L, 10L, List.of("deleted"))).thenReturn(List.of());
        for (String mode : List.of("full_text", "semantic", "hybrid")) {
            var result = service.searchWithStatus("7", "10", "q", 1,
                    new KnowledgeSearchRequest("10", "q", 1, mode, false, null, null, false, "deleted"));
            assertTrue(result.items().isEmpty());
            assertEquals(new KnowledgeService.SearchScope(KnowledgeService.SearchScopeKind.SOURCE_VERSION, null, "deleted"), result.scope());
        }
        verifyNoInteractions(searchIndexer, embeddingProvider, chunkMapper);
    }

    @Test
    void economicalKeywordFallbackUsesOnlySurvivingSources() {
        var base = activeBase("deleted", "live"); base.setIndexingMode("economical");
        when(documentVersionMapper.liveSourceVersions(7L, 10L, List.of("deleted", "live"))).thenReturn(List.of("live"));
        var live = chunk(); live.setSourceVersion("live");
        when(chunkMapper.searchKeyword("7", "10", "q", 1, List.of("live"))).thenReturn(List.of(live));
        var result = service.searchWithStatus("7", "10", "q", 1, null);
        assertEquals(List.of("live"), result.items().stream().map(KnowledgeHit::sourceVersion).toList());
        verify(chunkMapper).searchKeyword("7", "10", "q", 1, List.of("live"));
        verifyNoInteractions(embeddingProvider);
    }

    @Test
    void deletionDuringRetrievalIsRecheckedBeforeReturningAnySourceText() {
        activeBase("deleted", "live");
        when(documentVersionMapper.liveSourceVersions(7L, 10L, List.of("deleted", "live")))
                .thenReturn(List.of("deleted", "live"), List.of("live"));
        when(searchIndexer.searchVersions("7", "10", "q", 2, List.of("deleted", "live")))
                .thenReturn(List.of(item("removed", .9, "deleted"), item("remaining", .8, "live")));
        var result = service.searchWithStatus("7", "10", "q", 2,
                new KnowledgeSearchRequest("10", "q", 2, "full_text", false, null, null, false));
        assertEquals(List.of("live"), result.items().stream().map(KnowledgeHit::sourceVersion).toList());
        verify(documentVersionMapper, times(2)).liveSourceVersions(7L, 10L, List.of("deleted", "live"));
    }

    @Test
    void semanticAndHybridPostgresFallbacksReceiveOnlySurvivingManifestSources() {
        activeBase("deleted", "live");
        when(documentVersionMapper.liveSourceVersions(7L, 10L, List.of("deleted", "live"))).thenReturn(List.of("live"));
        when(embeddingProvider.embed("7", "81", 3L, "q")).thenReturn(new float[]{1f, 0f});
        when(searchIndexer.searchKnnVersions(eq("7"), eq("10"), anyString(), anyInt(), eq(List.of("live"))))
                .thenThrow(new IllegalStateException("isolated_index_unavailable"));
        when(chunkMapper.vectorSearchVersions(eq("7"), eq("10"), anyString(), anyInt(), any(), eq(List.of("live"))))
                .thenReturn(List.of(item("remaining", .8, "live")));
        for (String mode : List.of("semantic", "hybrid")) {
            var result = service.searchWithStatus("7", "10", "q", 1,
                    new KnowledgeSearchRequest("10", "q", 1, mode, false, null, null, false));
            assertEquals(List.of("live"), result.items().stream().map(KnowledgeHit::sourceVersion).toList());
        }
        verify(chunkMapper, times(2)).vectorSearchVersions(eq("7"), eq("10"), anyString(), anyInt(), any(), eq(List.of("live")));
    }

    private KnowledgeBaseDO activeBase(String... sourceVersions) {
        KnowledgeBaseDO base = base();
        base.setActiveRevisionId(90L);
        KnowledgeBaseRevisionDO revision = new KnowledgeBaseRevisionDO();
        revision.setId(90L);
        revision.setRevision(1L);
        revision.setTenantId(7L);
        revision.setKbId(10L);
        revision.setStatus(KnowledgeConstants.REVISION_STATUS_PUBLISHED);
        revision.setSourceVersions(List.of(sourceVersions));
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        when(revisionMapper.selectById(90L)).thenReturn(revision);
        return base;
    }

    @Test
    void projectionRepairRejectsIncompleteSourceAndReportsUnconfirmedWriteWithoutModelOrPointerMutation() {
        when(baseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        var source = version();
        when(documentVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(source);
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk()));
        assertThrows(ApiException.class, () -> service.repairProjection("7", 10L, "v1"));
        verify(searchIndexer, never()).indexChunks(anyString(), any());
        when(documentVersionMapper.countCompleteVectorVersion(7L, 10L, "v1", 2)).thenReturn(1);
        var failed = assertThrows(ApiException.class, () -> service.repairProjection("7", 10L, "v1"));
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, failed.getStatus());
        when(searchIndexer.indexChunks(eq("7"), any())).thenReturn(true);
        assertEquals(1, service.repairProjection("7", 10L, "v1").chunkCount());
        var missingVector = chunk(); missingVector.setEmbedding(null);
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(missingVector));
        assertThrows(ApiException.class, () -> service.repairProjection("7", 10L, "v1"));
        org.mockito.Mockito.verifyNoInteractions(embeddingProvider);
        verify(baseMapper, never()).updateById(any(KnowledgeBaseDO.class));
        verify(documentMapper, never()).updateById(any(KnowledgeDocumentDO.class));
    }

    private KnowledgeBaseDO base() {
        KnowledgeBaseDO base = new KnowledgeBaseDO();
        base.setId(10L);
        base.setTenantId(7L);
        base.setName("base");
        base.setDescription("description");
        base.setStatus(KnowledgeConstants.BASE_STATUS_ACTIVE);
        base.setEmbeddingModelConfigId(81L);
        base.setEmbeddingModelRevision(3L);
        base.setEmbeddingDimensions(2);
        base.setChunkSize(100);
        base.setChunkOverlap(10);
        base.setIndexingMode("high_quality");
        base.setRetrievalMode("semantic");
        base.setTopK(5);
        base.setScoreThresholdEnabled(false);
        base.setScoreThreshold(.5);
        base.setVectorWeight(.5);
        base.setRerankEnabled(false);
        base.setCreatedAt(Instant.now());
        return base;
    }

    private KnowledgeOperationalSettings.Snapshot settings() {
        return new KnowledgeOperationalSettings.Snapshot(
                1, 500, 50, 10, 2000,
                "high_quality", "semantic", 5, .5, .5);
    }

    private PlatformModelConfig embeddingModel() {
        return new PlatformModelConfig("81", 3, "Embedding", "EMBEDDING", true,
                "openai", null, null, null, "bearer-api-key", null,
                "https://embedding.example/v1", "embed-v1", "encrypted",
                null, null, null, null, null, 20, 2, null,
                null, null, null, "CNY");
    }

    private KnowledgeDocumentDO document() {
        KnowledgeDocumentDO document = new KnowledgeDocumentDO();
        document.setId(20L);
        document.setTenantId(7L);
        document.setKbId(10L);
        document.setCurrentSourceVersion("v1");
        document.setCreatedAt(Instant.now());
        return document;
    }

    private KnowledgeDocumentVersionDO version() {
        KnowledgeDocumentVersionDO version = new KnowledgeDocumentVersionDO();
        version.setEmbeddingModelConfigId(81L); version.setEmbeddingModelRevision(3L); version.setEmbeddingDimensions(2);
        version.setId(21L);
        version.setTenantId(7L);
        version.setKbId(10L);
        version.setDocumentId(20L);
        version.setTitle("title");
        version.setSourceType(KnowledgeConstants.SOURCE_FILE);
        version.setContent("chunk content");
        version.setContentId(91L);
        version.setChunkSetId(92L);
        version.setOriginalFilename("title.txt");
        version.setExtension("txt");
        version.setSizeBytes(16L);
        version.setParsedBy("direct");
        version.setStatus(KnowledgeConstants.DOC_STATUS_READY);
        version.setSourceVersion("v1");
        version.setChunkCount(1);
        version.setCreatedAt(Instant.now());
        return version;
    }

    private KnowledgeChunkDO chunk() {
        KnowledgeChunkDO chunk = new KnowledgeChunkDO();
        chunk.setId(30L);
        chunk.setTenantId(7L);
        chunk.setKbId(10L);
        chunk.setDocumentId(20L);
        chunk.setContent("chunk content");
        chunk.setContentId(93L);
        chunk.setEmbeddingId(94L);
        chunk.setChunkSetId(92L);
        chunk.setChunkIndex(0);
        chunk.setSourceVersion("v1");
        chunk.setEmbedding("[0.1,0.2]");
        chunk.setCreatedAt(Instant.now());
        return chunk;
    }

    private Map<String, Object> item(String id, double score, String sourceVersion) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("chunk_id", id);
        item.put("document_id", "20");
        item.put("content", "content " + id);
        item.put("score", score);
        item.put("source_version", sourceVersion);
        return item;
    }
}
