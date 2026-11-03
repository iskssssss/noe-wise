package com.skillforge.core.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.knowledge.constant.KnowledgeConstants;
import com.skillforge.core.shared.constant.ApiConstants;
import com.skillforge.core.knowledge.dto.request.KnowledgeSearchRequest;
import com.skillforge.core.knowledge.dto.response.*;
import com.skillforge.core.knowledge.enums.KnowledgeBaseStatus;
import com.skillforge.core.knowledge.dto.query.KnowledgeDocumentQuery;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.knowledge.ChunkingStrategy;
import com.skillforge.core.knowledge.DocumentParser;
import com.skillforge.core.knowledge.EmbeddingProvider;
import com.skillforge.core.knowledge.RerankProvider;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeChunkMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeDocumentMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.skillforge.core.knowledge.model.KnowledgeBaseDO;
import com.skillforge.core.knowledge.model.KnowledgeBaseCount;
import com.skillforge.core.knowledge.model.KnowledgeBaseRevisionDO;
import com.skillforge.core.knowledge.model.KnowledgeChunkDO;
import com.skillforge.core.knowledge.model.KnowledgeDocumentDO;
import com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO;
import com.skillforge.core.knowledge.model.KnowledgeDocumentSummary;
import com.skillforge.core.knowledge.search.SearchIndexer;
import com.skillforge.core.shared.logging.SafeFailureDiagnostics;
import com.skillforge.core.model.PlatformModelConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库服务。
 *
 * <p>封装知识库与文档管理、分段参数化切分（段落感知）、批量 embedding 写入、
 * 向量检索与关键词回退；版本引用不可变正文/分块集合，外部索引仅作提交后的派生投影。
 */
@Service
public class KnowledgeService {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /** 知识库表映射器。 */
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    /** Knowledge Base active revision 映射器。 */
    private final KnowledgeBaseRevisionMapper revisionMapper;

    /** 知识文档表映射器。 */
    private final KnowledgeDocumentMapper documentMapper;

    /** 文档不可变来源版本映射器。 */
    private final KnowledgeDocumentVersionMapper documentVersionMapper;

    /** 知识分块表映射器。 */
    private final KnowledgeChunkMapper chunkMapper;

    /** 机构级 Embedding 客户端。 */
    private final EmbeddingProvider embeddingProvider;

    /** 机构级 Rerank 客户端。 */
    private final RerankProvider rerankProvider;

    /** 外部检索索引器（Elasticsearch）。 */
    private final SearchIndexer searchIndexer;

    /** 知识库任务级运营参数快照入口。 */
    private final KnowledgeOperationalSettings operationalSettings;

    /** 不可变正文、分块集合和向量的唯一领域写入入口。 */
    private final KnowledgeStorageService storage;

    private final TransactionTemplate writer;
    private final TransactionTemplate outsideTransaction;

    /**
     * 构造知识库服务。
     *
     * @param knowledgeBaseMapper 知识库表映射器
     * @param documentMapper      知识文档表映射器
     * @param chunkMapper         知识分块表映射器
     * @param embeddingProvider   Embedding 客户端
     * @param rerankProvider       Rerank 客户端
     * @param searchIndexer        外部检索索引器
     * @param operationalSettings 运营参数快照入口
     */
    public KnowledgeService(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseRevisionMapper revisionMapper,
            KnowledgeDocumentMapper documentMapper,
            KnowledgeDocumentVersionMapper documentVersionMapper,
            KnowledgeChunkMapper chunkMapper,
            EmbeddingProvider embeddingProvider,
            RerankProvider rerankProvider,
            SearchIndexer searchIndexer,
            KnowledgeOperationalSettings operationalSettings,
            KnowledgeStorageService storage,
            PlatformTransactionManager transactionManager) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.revisionMapper = revisionMapper;
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingProvider = embeddingProvider;
        this.rerankProvider = rerankProvider;
        this.searchIndexer = searchIndexer;
        this.operationalSettings = operationalSettings;
        this.storage = storage;
        this.writer = new TransactionTemplate(transactionManager);
        this.outsideTransaction = new TransactionTemplate(transactionManager);
        this.outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /**
     * 列出当前租户的知识库（含文档数/分块数统计）。
     *
     * @param tenantId 租户 ID
     * @return 知识库列表响应
     */
    public KnowledgeBaseListResponse listBases(String tenantId) {
        return listBases(tenantId, null);
    }

    /** 管理层传入已授权 ID；null 表示完整租户范围，空集合表示无可见对象。 */
    public KnowledgeBaseListResponse listBases(String tenantId, List<Long> permittedBaseIds) {
        if (permittedBaseIds != null && permittedBaseIds.isEmpty()) return new KnowledgeBaseListResponse(0, List.of());
        List<KnowledgeBaseDO> rows = knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getTenantId, Long.parseLong(tenantId))
                        .in(permittedBaseIds != null, KnowledgeBaseDO::getId, permittedBaseIds)
                        .orderByDesc(KnowledgeBaseDO::getCreatedAt));
        if (rows.isEmpty()) return new KnowledgeBaseListResponse(0, List.of());
        List<Long> baseIds = rows.stream().map(KnowledgeBaseDO::getId).toList();
        Map<Long, Long> documents = countsByKb(documentMapper.countByBase(Long.parseLong(tenantId), baseIds));
        Map<Long, Long> chunks = countsByKb(chunkMapper.countCurrentByBase(Long.parseLong(tenantId), baseIds));
        return new KnowledgeBaseListResponse(rows.size(), rows.stream().map(row ->
                new KnowledgeBaseItemResponse(row.getId(), row.getName(), row.getDescription(),
                        KnowledgeBaseStatus.fromCode(row.getStatus()), documents.getOrDefault(row.getId(), 0L),
                        chunks.getOrDefault(row.getId(), 0L))).toList());
    }

    /**
     * 查询知识库详情。
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @return 知识库详情响应
     */
    public KnowledgeBaseDetailResponse getBase(String tenantId, Long kbId) {
        KnowledgeBaseDO base = requireBase(tenantId, kbId);
        Map<Long, Long> documents = countsByKb(documentMapper.countByBase(Long.parseLong(tenantId), List.of(kbId)));
        Map<Long, Long> chunks = countsByKb(chunkMapper.countCurrentByBase(Long.parseLong(tenantId), List.of(kbId)));
        return new KnowledgeBaseDetailResponse(base.getId(), base.getName(), base.getDescription(),
                base.getStatus(), base.getActiveRevisionId(), base.getChunkSize(), base.getChunkOverlap(),
                base.getIndexingMode(), base.getRetrievalMode(), base.getTopK(), base.getScoreThresholdEnabled(),
                base.getScoreThreshold(), base.getVectorWeight(), base.getRerankEnabled(),
                documents.getOrDefault(base.getId(), 0L), chunks.getOrDefault(base.getId(), 0L), base.getCreatedAt(),
                operationalSettings.effectiveIndexingMode(base.getIndexingMode()));
    }

    /**
     * 更新知识库（名称/描述/状态/分段参数），空值字段不修改。
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @param name     名称，可空
     * @param description 描述，可空
     * @param status   状态（active / archived），可空
     * @param settings 分段参数等配置，可空
     * @return 更新后的详情
     */
    @Transactional
    public KnowledgeBaseDetailResponse updateBase(
            String tenantId, Long kbId, String name, String description, String status,
            KnowledgeBaseSettingsUpdate settings) {
        KnowledgeBaseDO base = requireWritableBase(tenantId, kbId);
        if (name != null && !name.isBlank()) {
            base.setName(name);
        }
        if (description != null) {
            base.setDescription(description);
        }
        if (status != null && !status.isBlank()) {
            if (!KnowledgeConstants.BASE_STATUS_ACTIVE.equals(status)
                    && !KnowledgeConstants.BASE_STATUS_ARCHIVED.equals(status)) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "知识库状态仅支持 active / archived");
            }
            base.setStatus(status);
        }
        if (settings != null) {
            if (settings.chunkSize() != null) {
                base.setChunkSize(settings.chunkSize());
            }
            if (settings.chunkOverlap() != null) {
                base.setChunkOverlap(settings.chunkOverlap());
            }
            if (settings.indexingMode() != null) {
                base.setIndexingMode(settings.indexingMode());
            }
            if (settings.retrievalMode() != null) {
                base.setRetrievalMode(settings.retrievalMode());
            }
            if (settings.topK() != null) {
                base.setTopK(settings.topK());
            }
            if (settings.scoreThresholdEnabled() != null) {
                base.setScoreThresholdEnabled(settings.scoreThresholdEnabled());
            }
            if (settings.scoreThreshold() != null) {
                base.setScoreThreshold(settings.scoreThreshold());
            }
            if (settings.vectorWeight() != null) {
                base.setVectorWeight(settings.vectorWeight());
            }
            if (settings.rerankEnabled() != null) {
                base.setRerankEnabled(settings.rerankEnabled());
            }
        }
        base.setUpdatedAt(Instant.now());
        knowledgeBaseMapper.updateById(base);
        return getBase(tenantId, kbId);
    }

    /**
     * 删除空知识库。存在文档来源版本时拒绝硬删除，避免销毁已发布引用。
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     */
    @Transactional
    public void deleteBase(String tenantId, Long kbId) {
        KnowledgeBaseDO base = requireWritableBase(tenantId, kbId);
        Long documents = documentMapper.selectCount(new LambdaQueryWrapper<KnowledgeDocumentDO>()
                .eq(KnowledgeDocumentDO::getTenantId, base.getTenantId())
                .eq(KnowledgeDocumentDO::getKbId, base.getId()));
        if (documents != null && documents > 0) {
            throw conflict("知识库已有不可变来源版本，不能硬删除；请归档知识库");
        }
        knowledgeBaseMapper.deleteById(base.getId());
    }

    /**
     * 创建知识库。
     *
     * @param tenantId    租户 ID
     * @param name        知识库名称
     * @param description 知识库描述
     * @return 创建结果
     */
    public CreateKnowledgeBaseResponse createBase(String tenantId, String name, String description) {
        return createBase(tenantId, name, description, null, null, null);
    }

    /** 模式与模型资格先于任何持久写入；经济索引明确不需要向量空间。 */
    public CreateKnowledgeBaseResponse createBase(String tenantId, String name, String description,
            String indexingMode, String retrievalMode, Boolean rerankEnabled) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "知识库名称不能为空");
        }
        var settings = operationalSettings.capture();
        String indexing = indexingMode == null ? settings.defaultIndexingMode() : indexingMode;
        if (!List.of("high_quality", "economical").contains(indexing))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "knowledge_indexing_mode_invalid");
        boolean economical = KnowledgeConstants.INDEXING_MODE_ECONOMICAL.equals(indexing);
        String retrieval = retrievalMode == null ? (economical ? "full_text" : settings.defaultRetrievalMode()) : retrievalMode;
        if (!List.of("semantic", "full_text", "hybrid").contains(retrieval)
                || economical && (!"full_text".equals(retrieval) || Boolean.TRUE.equals(rerankEnabled)))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "knowledge_retrieval_mode_incompatible");
        if (Boolean.TRUE.equals(rerankEnabled)) rerankProvider.config(tenantId);
        PlatformModelConfig embedding = economical ? null : embeddingProvider.config(tenantId);
        if (embedding != null && (embedding.dimensions() == null || embedding.dimensions() <= 0))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "platform_embedding_dimensions_required");
        KnowledgeBaseDO base = new KnowledgeBaseDO();
        base.setId(SnowflakeIds.nextId());
        base.setTenantId(Long.parseLong(tenantId));
        base.setName(name); base.setDescription(description);
        base.setStatus(KnowledgeConstants.BASE_STATUS_ACTIVE);
        base.setIndexingMode(indexing); base.setRetrievalMode(retrieval);
        base.setRerankEnabled(Boolean.TRUE.equals(rerankEnabled));
        if (embedding != null) {
            base.setEmbeddingModelConfigId(Long.parseLong(embedding.id()));
            base.setEmbeddingModelRevision(embedding.revision());
            base.setEmbeddingDimensions(embedding.dimensions());
        }
        base.setCreatedAt(Instant.now());
        base.setUpdatedAt(Instant.now());
        knowledgeBaseMapper.insert(base);
        return new CreateKnowledgeBaseResponse(base.getId(), base.getName());
    }

    /**
     * 文件上传文档（txt/md/pdf/docx），解析文本后按知识库分段参数入库。
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @param title    文档标题，可空（缺省取文件名去扩展名）
     * @param file     上传文件
     * @return 文档 ID 与分块数量
     */
    @Transactional(propagation = Propagation.NEVER)
    public UploadDocumentResponse uploadFile(String tenantId, Long kbId, String title, MultipartFile file) {
        return persistPreparedDocument(tenantId, prepareUploadDocument(tenantId, kbId, title, file));
    }

    /** 文件解析和模型调用先于短事务；上传回执与文档提交由事务内的调用方统一完成。 */
    PreparedDocument prepareUploadDocument(String tenantId, Long kbId, String title, MultipartFile file) {
        KnowledgeOperationalSettings.Snapshot settings = operationalSettings.capture();
        DocumentParser.ParsedDocument parsed;
        try {
            requireBase(tenantId, kbId);
            long maxBytes = (long) settings.maxUploadMb() * 1024 * 1024;
            if (file.getSize() > maxBytes) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "文件超过大小上限（" + settings.maxUploadMb() + "MB）");
            }
            parsed = DocumentParser.parse(file);
        } catch (ApiException rejected) {
            throw new ApiException(rejected.getStatus(), "KNOWLEDGE_UPLOAD_REJECTED",
                    String.valueOf(rejected.getDetail()), false);
        }
        String docTitle = title == null || title.isBlank()
                ? defaultTitle(file.getOriginalFilename()) : title;
        return prepareDocument(tenantId, kbId, docTitle, parsed.content(),
                KnowledgeConstants.SOURCE_FILE, parsed, settings);
    }

    /**
     * 写入审核通过的机构知识投影。
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @param title    文档标题
     * @param content  文档正文
     * @return 文档 ID、来源版本与分块数量
     */
    @Transactional(propagation = Propagation.NEVER)
    public UploadDocumentResponse ingestProjectionDocument(
            String tenantId, Long kbId, String title, String content) {
        return persistDocument(tenantId, kbId, title, content,
                KnowledgeConstants.SOURCE_PROJECTION, null, operationalSettings.capture());
    }

    /**
     * 由文件名生成默认文档标题（去扩展名）。
     *
     * @param filename 文件名
     * @return 标题
     */
    private String defaultTitle(String filename) {
        if (filename == null || filename.isBlank()) {
            return "未命名文档";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * 统一文档入库：插入文档、按知识库分段参数切分、批量 embedding 写入分块。
     *
     * @param tenantId   租户 ID
     * @param kbId       知识库 ID
     * @param title      文档标题
     * @param content    文档正文
     * @param sourceType 当前内部来源类型
     * @param meta       文档元数据
     * @return 文档 ID 与分块数量
     */
    private UploadDocumentResponse persistDocument(
            String tenantId, Long kbId, String title, String content,
            String sourceType, DocumentParser.ParsedDocument parsed,
            KnowledgeOperationalSettings.Snapshot settings) {
        return persistPreparedDocument(tenantId,
                prepareDocument(tenantId, kbId, title, content, sourceType, parsed, settings));
    }

    private PreparedDocument prepareDocument(
            String tenantId, Long kbId, String title, String content,
            String sourceType, DocumentParser.ParsedDocument parsed,
            KnowledgeOperationalSettings.Snapshot settings) {
        KnowledgeBaseDO base = requireWritableBase(tenantId, kbId);
        KnowledgeDocumentDO document = new KnowledgeDocumentDO();
        document.setId(SnowflakeIds.nextId());
        document.setTenantId(Long.parseLong(tenantId));
        document.setKbId(base.getId());
        document.setCreatedAt(Instant.now());
        KnowledgeDocumentVersionDO version = newVersion(document, title, content, sourceType,
                parsed == null ? null : parsed.originalFilename(),
                parsed == null ? null : parsed.extension(),
                parsed == null ? null : parsed.sizeBytes(),
                parsed == null ? null : parsed.parsedBy());
        freezeModel(version, base);
        List<KnowledgeChunkDO> chunks = splitAndEmbed(tenantId, base, document, version, content, settings, List.of());
        return new PreparedDocument(document, version, chunks);
    }

    /** 仅准备模型与完整版本，供治理投影在启动原子写事务前调用。 */
    PreparedDocument prepareProjectionDocument(String tenantId, Long kbId, String title, String content) {
        return prepareDocument(tenantId, kbId, title, content,
                KnowledgeConstants.SOURCE_PROJECTION, null, operationalSettings.capture());
    }

    /** 加入治理投影的原子事务；数据库主存储提交后才发布外部索引。 */
    UploadDocumentResponse persistPreparedDocument(String tenantId, PreparedDocument prepared) {
        return writer.execute(status -> {
            KnowledgeDocumentDO document = prepared.document();
            requireWritableBase(tenantId, document.getKbId());
            if (!Objects.equals(document.getTenantId(), Long.valueOf(tenantId))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "来源版本所属机构不一致");
            }
            documentMapper.insert(document);
            writeVersion(prepared.version(), prepared.chunks());
            if (documentMapper.installInitialVersion(document.getTenantId(), document.getId(),
                    prepared.version().getSourceVersion()) != 1) {
                throw conflict("文档初始来源版本安装冲突");
            }
            indexAfterCommit(tenantId, prepared.chunks());
            return new UploadDocumentResponse(document.getId(), prepared.chunks().size(),
                    prepared.version().getSourceVersion());
        });
    }

    record PreparedDocument(KnowledgeDocumentDO document, KnowledgeDocumentVersionDO version,
                            List<KnowledgeChunkDO> chunks) { }

    /**
     * 对文档正文分块并批量写入向量分块（复用相同内容的冻结向量，持久化由短事务统一执行）。
     *
     * @param tenantId 租户 ID
     * @param base     知识库
     * @param document 文档身份
     * @param content  文档正文
     * @return 分块数
     */
    private List<KnowledgeChunkDO> splitAndEmbed(
            String tenantId, KnowledgeBaseDO base, KnowledgeDocumentDO document,
            KnowledgeDocumentVersionDO version, String content,
            KnowledgeOperationalSettings.Snapshot settings, List<KnowledgeChunkDO> reusable) {
        int chunkSize = chunkSizeOf(base, settings);
        int chunkOverlap = chunkOverlapOf(base, settings);
        List<String> texts = ChunkingStrategy.split(content, chunkSize, chunkOverlap);
        if (texts.size() > settings.maxChunksPerDocument()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "KNOWLEDGE_DOCUMENT_CHUNK_LIMIT",
                    "文档分块数超过上限（" + settings.maxChunksPerDocument()
                            + "），请拆分文档后重试", false);
        }
        Map<Integer, String> reusableVectors = new HashMap<>();
        List<String> changedTexts = new ArrayList<>();
        List<Integer> changedIndexes = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) {
            KnowledgeChunkDO previous = index < reusable.size() ? reusable.get(index) : null;
            if (previous != null && Objects.equals(previous.getContent(), texts.get(index))) {
                reusableVectors.put(index, previous.getEmbedding());
            } else {
                changedIndexes.add(index);
                changedTexts.add(texts.get(index));
            }
        }
        if (!changedTexts.isEmpty() && !economical(base, settings)) {
            List<String> embedded;
            // 嵌套作用域继承已冻结 Run 或控制操作来源，只覆盖调用点，不能退化成无归属调用。
            try (MeterScope scope = MeterScope.open(CostContext.builder()
                    .callPoint("knowledge.embed").build())) {
                embedded = embedBatch(tenantId, base, changedTexts);
            }
            for (int index = 0; index < changedIndexes.size(); index++) {
                reusableVectors.put(changedIndexes.get(index), embedded == null ? null : embedded.get(index));
            }
        }
        List<KnowledgeChunkDO> chunks = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) {
            KnowledgeChunkDO chunk = new KnowledgeChunkDO();
            chunk.setId(SnowflakeIds.nextId());
            chunk.setTenantId(document.getTenantId());
            chunk.setKbId(base.getId());
            chunk.setDocumentId(document.getId());
            chunk.setContent(texts.get(index));
            chunk.setChunkIndex(index);
            chunk.setSourceVersion(version.getSourceVersion());
            chunk.setEmbedding(reusableVectors.get(index));
            KnowledgeChunkDO previous = index < reusable.size() ? reusable.get(index) : null;
            if (previous != null && Objects.equals(previous.getContent(), chunk.getContent())) {
                chunk.setContentId(previous.getContentId());
                if (Objects.equals(previous.getEmbedding(), chunk.getEmbedding())) chunk.setEmbeddingId(previous.getEmbeddingId());
            }
            chunk.setCreatedAt(Instant.now());
            chunks.add(chunk);
        }
        return List.copyOf(chunks);
    }

    private KnowledgeDocumentVersionDO newVersion(
            KnowledgeDocumentDO document, String title, String content, String sourceType,
            String originalFilename, String extension, Long sizeBytes, String parsedBy) {
        KnowledgeDocumentVersionDO version = new KnowledgeDocumentVersionDO();
        version.setId(SnowflakeIds.nextId());
        version.setTenantId(document.getTenantId());
        version.setKbId(document.getKbId());
        version.setDocumentId(document.getId());
        version.setSourceVersion(newSourceVersion());
        version.setTitle(title);
        version.setContent(content);
        version.setSourceType(sourceType);
        version.setOriginalFilename(originalFilename);
        version.setExtension(extension);
        version.setSizeBytes(sizeBytes);
        version.setParsedBy(parsedBy);
        version.setStatus(KnowledgeConstants.DOC_STATUS_PROCESSING);
        version.setChunkCount(0);
        version.setCreatedAt(Instant.now());
        return version;
    }

    private void writeVersion(KnowledgeDocumentVersionDO version, List<KnowledgeChunkDO> chunks) {
        KnowledgeBaseDO currentModel=requireWritableBase(version.getTenantId().toString(), version.getKbId());
        if (version.getEmbeddingModelConfigId() == null) freezeModel(version,currentModel);
        if(!Objects.equals(version.getEmbeddingModelConfigId(),currentModel.getEmbeddingModelConfigId())
                ||!Objects.equals(version.getEmbeddingModelRevision(),currentModel.getEmbeddingModelRevision())
                ||!Objects.equals(version.getEmbeddingDimensions(),currentModel.getEmbeddingDimensions()))
            throw conflict("knowledge_source_model_stale");
        storage.prepareVersion(version, chunks);
        documentVersionMapper.insert(version);
        finishVersion(version, chunks.size());
    }

    private void replaceVersion(String tenantId, KnowledgeDocumentDO document,
            String expectedSourceVersion, KnowledgeDocumentVersionDO version, List<KnowledgeChunkDO> chunks) {
        writer.executeWithoutResult(status -> {
            requireWritableBase(tenantId, document.getKbId());
            KnowledgeDocumentDO current = requireDocument(tenantId, document.getId());
            assertExpectedVersion(current, expectedSourceVersion);
            writeVersion(version, chunks);
            activateVersion(document, expectedSourceVersion, version.getSourceVersion());
            indexAfterCommit(tenantId, chunks);
        });
    }

    private void indexAfterCommit(String tenantId, List<KnowledgeChunkDO> chunks) {
        Long hold=chunks.isEmpty()?null:storage.protectIndex(Long.parseLong(tenantId),chunks.getFirst().getKbId(),chunks.getFirst().getSourceVersion());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // afterCommit 仍绑定原事务资源，显式挂起后执行可降级的外部投影。
                try {
                    boolean confirmed=Boolean.TRUE.equals(outsideTransaction.execute(status -> indexChunksQuietly(tenantId, chunks)));
                    storage.finishIndex(Long.parseLong(tenantId),hold,confirmed);
                } catch (RuntimeException exception) {
                    log.warn("来源版本已提交，外部索引调度失败 tenantId={} chunks={} reason=knowledge_index_schedule_failed diagnostic={}",
                            tenantId, chunks.size(), SafeFailureDiagnostics.of(exception));
                }
            }
        });
    }

    private void finishVersion(KnowledgeDocumentVersionDO version, int chunkCount) {
        version.setChunkCount(chunkCount);
        version.setStatus(chunkCount == 0
                ? KnowledgeConstants.DOC_STATUS_FAILED : KnowledgeConstants.DOC_STATUS_READY);
        if (documentVersionMapper.updateById(version) != 1) {
            throw conflict("来源版本状态写入冲突");
        }
    }

    private boolean indexChunksQuietly(String tenantId, List<KnowledgeChunkDO> chunks) {
        if (chunks.isEmpty()) {
            return true;
        }
        List<Map<String, Object>> indexed = chunks.stream().map(chunk -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunk_id", chunk.getId());
            item.put("kb_id", chunk.getKbId());
            item.put("document_id", chunk.getDocumentId());
            item.put("chunk_index", chunk.getChunkIndex());
            item.put("content", chunk.getContent());
            item.put("source_version", chunk.getSourceVersion());
            item.put("embedding", chunk.getEmbedding());
            return item;
        }).toList();
        try {
            if (!searchIndexer.indexChunks(tenantId, indexed)) {
                log.warn("ES来源投影未确认，可从已完成PG来源版本显式修复 tenantId={} chunks={} reason=knowledge_projection_unconfirmed", tenantId, indexed.size());
                return false;
            }
            return true;
        } catch (Exception exception) {
            log.warn("ES 来源版本索引写入失败 tenantId={} chunks={} reason=knowledge_index_write_failed diagnostic={}",
                    tenantId, chunks.size(), SafeFailureDiagnostics.of(exception));
            return false;
        }
    }

    /** 显式重放完整PG来源投影；不重新生成向量，也不改变文档或发布指针。 */
    @Transactional(propagation = Propagation.NEVER)
    public ProjectionRepair repairProjection(String tenantId, long kbId, String sourceVersion) {
        requireBase(tenantId, kbId);
        long tenant = Long.parseLong(tenantId);
        var version = documentVersionMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocumentVersionDO>()
                .eq(KnowledgeDocumentVersionDO::getTenantId, tenant)
                .eq(KnowledgeDocumentVersionDO::getKbId, kbId)
                .eq(KnowledgeDocumentVersionDO::getSourceVersion, sourceVersion));
        if (version == null) throw new ApiException(HttpStatus.NOT_FOUND, "知识来源版本不存在");
        if (version.getEmbeddingDimensions() == null || version.getEmbeddingDimensions() <= 0
                || documentVersionMapper.countCompleteVectorVersion(tenant, kbId, sourceVersion, version.getEmbeddingDimensions()) != 1) {
            throw conflict("knowledge_projection_source_incomplete");
        }
        var chunks = chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getTenantId, tenant).eq(KnowledgeChunkDO::getKbId, kbId)
                .eq(KnowledgeChunkDO::getDocumentId, version.getDocumentId())
                .eq(KnowledgeChunkDO::getSourceVersion, sourceVersion).orderByAsc(KnowledgeChunkDO::getChunkIndex));
        if (chunks.isEmpty() || chunks.size() != version.getChunkCount()
                || chunks.stream().anyMatch(chunk -> chunk.getEmbedding() == null)) {
            throw conflict("knowledge_projection_source_incomplete");
        }
        Long hold=storage.startIndex(tenant,kbId,sourceVersion);
        try {
        for (int start = 0; start < chunks.size(); start += 200) {
            var indexed = chunks.subList(start, Math.min(start + 200, chunks.size())).stream().map(chunk -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chunk_id", chunk.getId()); item.put("kb_id", chunk.getKbId());
                item.put("document_id", chunk.getDocumentId()); item.put("chunk_index", chunk.getChunkIndex());
                item.put("content", chunk.getContent()); item.put("source_version", chunk.getSourceVersion());
                item.put("embedding", chunk.getEmbedding()); return item;
            }).toList();
            if (!searchIndexer.indexChunks(tenantId, indexed)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "知识投影修复未完成，可对同一来源版本重试");
            }
        }
        storage.finishIndex(tenant,hold,true);
        } catch(RuntimeException failure) {
            storage.finishIndex(tenant,hold,false);throw failure;
        }
        return new ProjectionRepair(kbId, sourceVersion, chunks.size());
    }
    public record ProjectionRepair(long kbId, String sourceVersion, int chunkCount) {}

    /**
     * 分页查询知识库文档。
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @param page     页码，从 1 起
     * @param pageSize 每页条数
     * @param status   状态过滤（ready / processing / failed），可空
     * @return 文档分页列表响应
     */
    public DocumentListResponse listDocuments(
            String tenantId, Long kbId, KnowledgeDocumentQuery query) {
        requireBase(tenantId, kbId);
        long page = query.page() == null || query.page() < 1 ? ApiConstants.DEFAULT_PAGE : query.page();
        long pageSize = query.pageSize() == null
                ? ApiConstants.DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(ApiConstants.MAX_PAGE_SIZE, query.pageSize()));
        String titleQuery = query.titleQuery() == null ? null : query.titleQuery().strip();
        if (titleQuery != null && titleQuery.isEmpty()) titleQuery = null;
        Page<KnowledgeDocumentSummary> result = documentVersionMapper.selectCurrentPage(
                new Page<>(page, pageSize), Long.parseLong(tenantId), kbId, query.status(), titleQuery);
        var items = result.getRecords().stream().map(version -> new DocumentItemResponse(
                version.documentId(), version.title(), version.sourceType(), version.status(),
                version.sourceVersion(), version.chunkCount(), version.createdAt())).toList();
        return new DocumentListResponse(result.getTotal(), items);
    }

    /** 为发布范围选择读取当前最新的就绪来源，不复制文档正文。 */
    public List<String> latestReadySources(String tenantId, Long kbId) {
        requireBase(tenantId, kbId);
        return documentVersionMapper.selectLatestReadySources(Long.parseLong(tenantId), kbId);
    }

    /**
     * 查询文档详情。
     *
     * @param tenantId 租户 ID
     * @param docId    文档 ID
     * @return 文档详情响应
     */
    public DocumentDetailResponse getDocument(String tenantId, Long docId) {
        KnowledgeDocumentDO document = requireDocument(tenantId, docId);
        KnowledgeDocumentVersionDO version = requireVersion(tenantId, document.getId(), document.getCurrentSourceVersion());
        return new DocumentDetailResponse(document.getId(), document.getKbId(), version.getTitle(),
                version.getSourceType(), version.getStatus(), version.getSourceVersion(), version.getContent(),
                version.getOriginalFilename(), version.getExtension(), version.getSizeBytes(), version.getParsedBy(),
                version.getCreatedAt());
    }

    /**
     * 更新文档：仅标题不触分块；传 content 时按知识库分段参数重新分块并重算向量。
     *
     * @param tenantId 租户 ID
     * @param docId    文档 ID
     * @param title    新标题，可空
     * @param content  新正文，可空（传入即重新分块）
     * @return 更新后的详情
     */
    @Transactional(propagation = Propagation.NEVER)
    public DocumentDetailResponse updateDocument(
            String tenantId, Long docId, String title, String content, String expectedSourceVersion) {
        KnowledgeOperationalSettings.Snapshot settings = operationalSettings.capture();
        KnowledgeDocumentDO document = requireDocument(tenantId, docId);
        assertExpectedVersion(document, expectedSourceVersion);
        KnowledgeDocumentVersionDO current = requireVersion(
                tenantId, document.getId(), expectedSourceVersion);
        if ((title == null || Objects.equals(title, current.getTitle()))
                && (content == null || Objects.equals(content, current.getContent()))) return getDocument(tenantId, docId);
        KnowledgeBaseDO base = requireWritableBase(tenantId, document.getKbId());
        List<KnowledgeChunkDO> previous = chunksOf(tenantId, document.getId(), expectedSourceVersion);
        String nextContent = content == null ? current.getContent() : content;
        KnowledgeDocumentVersionDO next = newVersion(document,
                title == null ? current.getTitle() : title,
                nextContent, current.getSourceType(), current.getOriginalFilename(),
                current.getExtension(), current.getSizeBytes(), current.getParsedBy());
        if (Objects.equals(nextContent, current.getContent())) next.setContentId(current.getContentId());
        List<KnowledgeChunkDO> chunks;
        if (content == null) {
            next.setChunkSetId(current.getChunkSetId());
            chunks = new ArrayList<>();
            for (KnowledgeChunkDO old : previous) {
                KnowledgeChunkDO shared = copyChunk(old, next.getSourceVersion(), old.getChunkIndex(), old.getEmbedding());
                shared.setId(old.getId());
                shared.setChunkSetId(current.getChunkSetId());
                chunks.add(shared);
            }
        } else {
            chunks = splitAndEmbed(
                    tenantId, base, document, next, nextContent, settings, previous);
        }
        freezeModel(next, base);
        replaceVersion(tenantId, document, expectedSourceVersion, next, chunks);
        return getDocument(tenantId, docId);
    }

    /**
     * 软删除文档身份；历史来源版本、分块与外部索引继续保留。
     *
     * @param tenantId 租户 ID
     * @param docId    文档 ID
     */
    @Transactional
    public void deleteDocument(String tenantId, Long docId, String expectedSourceVersion) {
        KnowledgeDocumentDO document = requireDocument(tenantId, docId);
        requireWritableBase(tenantId, document.getKbId());
        assertExpectedVersion(document, expectedSourceVersion);
        if (documentMapper.softDelete(document.getTenantId(), document.getId(), expectedSourceVersion) != 1) {
            throw conflict("文档已被其他请求修改，请刷新后重试");
        }
    }

    /**
     * 重新索引文档：复用原正文并建立新向量集合，生成新的当前来源版本。
     *
     * @param tenantId 租户 ID
     * @param docId    文档 ID
     * @return 重算的分块数
     */
    @Transactional(propagation = Propagation.NEVER)
    public UploadDocumentResponse reindexDocument(
            String tenantId, Long docId, String expectedSourceVersion) {
        KnowledgeDocumentDO document = requireDocument(tenantId, docId);
        assertExpectedVersion(document, expectedSourceVersion);
        KnowledgeDocumentVersionDO current = requireVersion(
                tenantId, document.getId(), expectedSourceVersion);
        KnowledgeBaseDO base = requireWritableBase(tenantId, document.getKbId());
        List<KnowledgeChunkDO> oldChunks = chunksOf(tenantId, document.getId(), expectedSourceVersion);
        KnowledgeDocumentVersionDO next = newVersion(document, current.getTitle(), current.getContent(),
                current.getSourceType(), current.getOriginalFilename(), current.getExtension(),
                current.getSizeBytes(), current.getParsedBy());
        next.setContentId(current.getContentId());
        List<String> texts = oldChunks.stream().map(KnowledgeChunkDO::getContent).toList();
        List<String> vectors = null;
        try (MeterScope scope = MeterScope.open(CostContext.builder()
                .callPoint("knowledge.embed")
                .build())) {
            if (!economical(base, operationalSettings.capture())) {
                vectors = embedBatch(tenantId, base, texts);
            }
        }
        List<KnowledgeChunkDO> chunks = new ArrayList<>();
        for (int index = 0; index < oldChunks.size(); index++) {
            chunks.add(copyChunk(oldChunks.get(index), next.getSourceVersion(), index,
                    vectors == null ? null : vectors.get(index)));
        }
        freezeModel(next, base);
        replaceVersion(tenantId, document, expectedSourceVersion, next, chunks);
        return new UploadDocumentResponse(document.getId(), chunks.size(), next.getSourceVersion());
    }

    /**
     * 分页查询文档分段（排除 embedding 列）。
     *
     * @param tenantId 租户 ID
     * @param docId    文档 ID
     * @param page     页码，从 1 起
     * @param pageSize 每页条数
     * @return 分段分页列表响应
     */
    public ChunkListResponse listChunks(String tenantId, Long docId, long page, long pageSize) {
        KnowledgeDocumentDO document = requireDocument(tenantId, docId);
        String sourceVersion = document.getCurrentSourceVersion();
        Page<KnowledgeChunkDO> result = chunkMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<KnowledgeChunkDO>()
                        .eq(KnowledgeChunkDO::getTenantId, Long.parseLong(tenantId))
                        .eq(KnowledgeChunkDO::getDocumentId, docId)
                        .eq(KnowledgeChunkDO::getSourceVersion, sourceVersion)
                        .orderByAsc(KnowledgeChunkDO::getChunkIndex)
                        .select(KnowledgeChunkDO::getId, KnowledgeChunkDO::getChunkIndex,
                                KnowledgeChunkDO::getContent, KnowledgeChunkDO::getEmbeddingId,
                                KnowledgeChunkDO::getCreatedAt));
        var items = result.getRecords().stream().map(chunk -> new ChunkItemResponse(chunk.getId(),
                chunk.getChunkIndex(), chunk.getContent(), chunk.getEmbeddingId() != null, sourceVersion,
                chunk.getCreatedAt())).toList();
        return new ChunkListResponse(result.getTotal(), items);
    }

    /**
     * 更新分段内容并重算向量（embedding 失败置空，仅关键词可命中）。
     *
     * @param tenantId 租户 ID
     * @param chunkId  分段 ID
     * @param content  新内容
     * @return 更新后的分段
     */
    @Transactional(propagation = Propagation.NEVER)
    public ChunkItemResponse updateChunk(
            String tenantId, Long chunkId, String content, String expectedSourceVersion) {
        KnowledgeChunkDO selected = requireChunk(tenantId, chunkId, expectedSourceVersion);
        if (Objects.equals(content, selected.getContent())) return chunkItem(selected);
        KnowledgeBaseDO base = requireWritableBase(tenantId, selected.getKbId());
        KnowledgeDocumentDO document = requireDocument(tenantId, selected.getDocumentId());
        assertExpectedVersion(document, expectedSourceVersion);
        KnowledgeDocumentVersionDO current = requireVersionMetadata(
                tenantId, document.getId(), expectedSourceVersion);
        List<KnowledgeChunkDO> oldChunks = chunksOf(tenantId, document.getId(), expectedSourceVersion);
        String embedding = null;
        if (!economical(base, operationalSettings.capture())) {
            try (MeterScope scope = MeterScope.open(CostContext.builder()
                    .callPoint("knowledge.embed")
                    .build())) {
                embedding = embed(tenantId, base, content);
            }
        }
        KnowledgeDocumentVersionDO next = newVersion(document, current.getTitle(), null,
                current.getSourceType(), current.getOriginalFilename(), current.getExtension(),
                current.getSizeBytes(), current.getParsedBy());
        List<KnowledgeChunkDO> chunks = new ArrayList<>();
        for (KnowledgeChunkDO old : oldChunks) {
            boolean changed = old.getId().equals(selected.getId());
            chunks.add(copyChunk(old, next.getSourceVersion(), old.getChunkIndex(),
                    changed ? embedding : old.getEmbedding(), changed ? content : old.getContent()));
        }
        next.setContent(joinChunkContent(chunks));
        freezeModel(next, base);
        replaceVersion(tenantId, document, expectedSourceVersion, next, chunks);
        return chunkItem(chunks.stream()
                .filter(chunk -> chunk.getChunkIndex().equals(selected.getChunkIndex()))
                .findFirst().orElseThrow());
    }

    /**
     * 删除分段：生成不含目标分段的完整新版本，旧版本保持不变。
     *
     * @param tenantId 租户 ID
     * @param chunkId  分段 ID
     */
    @Transactional(propagation = Propagation.NEVER)
    public void deleteChunk(String tenantId, Long chunkId, String expectedSourceVersion) {
        KnowledgeChunkDO selected = requireChunk(tenantId, chunkId, expectedSourceVersion);
        KnowledgeBaseDO base = requireWritableBase(tenantId, selected.getKbId());
        KnowledgeDocumentDO document = requireDocument(tenantId, selected.getDocumentId());
        assertExpectedVersion(document, expectedSourceVersion);
        KnowledgeDocumentVersionDO current = requireVersionMetadata(
                tenantId, document.getId(), expectedSourceVersion);
        List<KnowledgeChunkDO> oldChunks = chunksOf(tenantId, document.getId(), expectedSourceVersion);
        KnowledgeDocumentVersionDO next = newVersion(document, current.getTitle(), null,
                current.getSourceType(), current.getOriginalFilename(), current.getExtension(),
                current.getSizeBytes(), current.getParsedBy());
        List<KnowledgeChunkDO> chunks = new ArrayList<>();
        int nextIndex = 0;
        for (KnowledgeChunkDO old : oldChunks) {
            if (!old.getId().equals(selected.getId())) {
                chunks.add(copyChunk(old, next.getSourceVersion(), nextIndex++, old.getEmbedding()));
            }
        }
        next.setContent(joinChunkContent(chunks));
        freezeModel(next, base);
        replaceVersion(tenantId, document, expectedSourceVersion, next, chunks);
    }

    /**
     * 检索知识库：优先向量检索，失败或无结果时回退关键词检索；归档库返回空。
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @param query    查询文本
     * @param topK     返回条数上限
     * @return 检索结果列表
     */
    public List<KnowledgeHit> search(
            String tenantId, String kbId, String query, int topK, KnowledgeSearchRequest request) {
        KnowledgeOperationalSettings.Snapshot settings = operationalSettings.capture();
        KnowledgeBaseDO base = requireBase(tenantId, Long.parseLong(kbId));
        if (!KnowledgeConstants.BASE_STATUS_ACTIVE.equals(base.getStatus())) {
            return List.of();
        }
        int effectiveTopK = request != null && request.topK() == null
                ? settings.defaultTopK()
                : (topK > 0 ? topK : settings.defaultTopK());
        String mode = economical(base, settings)
                ? KnowledgeConstants.RETRIEVAL_MODE_FULL_TEXT
                : retrievalModeOf(base, request, settings);
        boolean thresholdEnabled = economical(base, settings)
                ? false : scoreThresholdEnabled(base, request);
        double threshold = thresholdEnabled ? scoreThresholdOf(base, request, settings) : 0.0;
        double vectorWeight = vectorWeightOf(base, request, settings);
        boolean rerankEnabled = rerankEnabledOf(base, request);
        String sourceVersion = request == null ? null : request.sourceVersion();
        List<String> activeSourceVersions = sourceVersion == null
                ? activeSourceVersions(base, tenantId, kbId) : null;
        KnowledgeBaseDO searchModel = KnowledgeConstants.RETRIEVAL_MODE_FULL_TEXT.equals(mode) ? base
                : selectedModel(base, tenantId, kbId, sourceVersion, activeSourceVersions);
        // 一次检索共享查询向量；manifest 统一查询，失败后统一回退关键词。
        Supplier<String> queryVector = new Supplier<>() {
            private String successfulVector;

            @Override
            public String get() {
                if (successfulVector == null) {
                    try (MeterScope scope = MeterScope.open(CostContext.builder()
                            .callPoint("knowledge.embed")
                            .build())) {
                        successfulVector = embed(tenantId, searchModel, query);
                    }
                }
                return successfulVector;
            }
        };
        List<KnowledgeHit> items = activeSourceVersions == null
                ? searchForSourceVersion(mode, tenantId, kbId, query, effectiveTopK,
                        vectorWeight, thresholdEnabled, threshold, sourceVersion, queryVector)
                : searchForActiveRevision(mode, tenantId, kbId, query, effectiveTopK,
                        vectorWeight, thresholdEnabled, threshold, activeSourceVersions, queryVector);
        items = filterBySourceVersion(items, sourceVersion);
        if (rerankEnabled && !items.isEmpty()) {
            List<RerankProvider.RerankResult> reranked;
            // Provider 自动计量继承父 Run/控制操作来源，保持机构、应用与业务范围归属。
            try (MeterScope scope = MeterScope.open(CostContext.builder()
                    .callPoint("knowledge.rerank")
                    .build())) {
                reranked = rerankProvider.rerank(tenantId, query, items.stream().map(KnowledgeHit::content).toList());
            }
            if (reranked != null) {
                items = applyRerank(items, reranked, thresholdEnabled, threshold);
            }
        }
        return List.copyOf(items);
    }

    private List<KnowledgeHit> searchForSourceVersion(
            String mode, String tenantId, String kbId, String query, int topK,
            double vectorWeight, boolean thresholdEnabled, double threshold, String sourceVersion,
            Supplier<String> queryVector) {
        return switch (mode) {
            case KnowledgeConstants.RETRIEVAL_MODE_FULL_TEXT -> fullTextSearch(
                    tenantId, kbId, query, topK, sourceVersion);
            case KnowledgeConstants.RETRIEVAL_MODE_HYBRID -> hybridSearch(
                    tenantId, kbId, query, topK, vectorWeight, thresholdEnabled, threshold, sourceVersion, queryVector);
            default -> semanticSearch(tenantId, kbId, query, topK,
                    thresholdEnabled, threshold, sourceVersion, queryVector);
        };
    }

    /** 授权 manifest 一次过滤后取 topK，历史版本不会挤占当前候选池。 */
    private List<KnowledgeHit> searchForActiveRevision(
            String mode, String tenantId, String kbId, String query, int topK,
            double vectorWeight, boolean thresholdEnabled, double threshold,
            List<String> activeSourceVersions, Supplier<String> queryVector) {
        if (activeSourceVersions.isEmpty()) return List.of();
        if (KnowledgeConstants.RETRIEVAL_MODE_FULL_TEXT.equals(mode)) {
            return fullTextSearchVersions(tenantId, kbId, query, topK, activeSourceVersions);
        }
        boolean hybrid = KnowledgeConstants.RETRIEVAL_MODE_HYBRID.equals(mode);
        int pool = hybrid ? Math.max(topK * 2, 10) : topK;
        List<KnowledgeHit> vectors = new ArrayList<>();
        String queryVec = queryVector.get();
        if (queryVec != null) {
            try {
                vectors = manifestHits(decodeSearchRows(searchIndexer.searchKnnVersions(
                        tenantId, kbId, queryVec, pool, activeSourceVersions), kbId, false), activeSourceVersions);
            } catch (RuntimeException exception) {
                log.warn("ES 授权来源向量检索不可用 tenantId={} kbId={}", tenantId, kbId, exception);
            }
            if (vectors.isEmpty()) {
                try {
                    vectors = decodeSearchRows(chunkMapper.vectorSearchVersions(tenantId, kbId,
                            queryVec, pool, thresholdEnabled ? threshold : null, activeSourceVersions), kbId, true);
                } catch (RuntimeException exception) {
                    log.warn("授权来源向量检索不可用，回退关键词 tenantId={} kbId={}", tenantId, kbId, exception);
                }
            }
        }
        if (!hybrid) {
            return vectors.isEmpty()
                    ? keywordSearchVersions(tenantId, kbId, query, topK, activeSourceVersions)
                    : vectors.stream().filter(hit -> !thresholdEnabled || hit.score() >= threshold).limit(topK).toList();
        }
        List<KnowledgeHit> keywords = fullTextSearchVersions(tenantId, kbId, query, pool, activeSourceVersions);
        return fuseHybrid(vectors, keywords, topK, vectorWeight).stream()
                .filter(hit -> !thresholdEnabled || hit.score() >= threshold).toList();
    }

    private List<KnowledgeHit> fullTextSearchVersions(
            String tenantId, String kbId, String query, int topK, List<String> versions) {
        try {
            List<KnowledgeHit> items = manifestHits(decodeSearchRows(searchIndexer.searchVersions(
                    tenantId, kbId, query, topK, versions), kbId, false), versions);
            if (!items.isEmpty()) return items;
        } catch (RuntimeException exception) {
            log.warn("ES 授权来源全文检索不可用 tenantId={} kbId={}", tenantId, kbId, exception);
        }
        return keywordSearchVersions(tenantId, kbId, query, topK, versions);
    }

    private List<KnowledgeHit> keywordSearchVersions(
            String tenantId, String kbId, String query, int topK, List<String> versions) {
        if (versions.isEmpty()) return List.of();
        List<KnowledgeChunkDO> rows = chunkMapper.searchKeyword(tenantId, kbId, query, topK, versions);
        return manifestHits(rows.stream().map(row -> new KnowledgeHit(kbId, String.valueOf(row.getDocumentId()),
                String.valueOf(row.getId()), row.getSourceVersion(), row.getContent(), 1.0, null)).toList(), versions);
    }

    private List<KnowledgeHit> manifestHits(List<KnowledgeHit> rows, List<String> versions) {
        java.util.Set<String> allowed = new java.util.HashSet<>(versions);
        return rows.stream().filter(row -> allowed.contains(row.sourceVersion())).toList();
    }

    /** 解析并校验 active revision；指针损坏或 manifest 不一致时 fail closed。 */
    private List<String> activeSourceVersions(KnowledgeBaseDO base, String tenantId, String kbId) {
        if (base.getActiveRevisionId() == null) {
            return documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentDO>()
                            .eq(KnowledgeDocumentDO::getTenantId, Long.parseLong(tenantId))
                            .eq(KnowledgeDocumentDO::getKbId, Long.parseLong(kbId))
                            .isNull(KnowledgeDocumentDO::getDeletedAt)
                            .select(KnowledgeDocumentDO::getCurrentSourceVersion))
                    .stream().map(KnowledgeDocumentDO::getCurrentSourceVersion)
                    .filter(Objects::nonNull).distinct().toList();
        }
        KnowledgeBaseRevisionDO revision = revisionMapper.selectById(base.getActiveRevisionId());
        if (revision == null
                || !Objects.equals(revision.getTenantId(), Long.valueOf(tenantId))
                || !Objects.equals(revision.getKbId(), Long.valueOf(kbId))
                || !java.util.Set.of("published", "archived").contains(revision.getStatus())
                || revision.getSourceVersions() == null) {
            return List.of();
        }
        return revision.getSourceVersions().stream()
                .filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
    }

    /**
     * 向量检索（语义）：优先 ES kNN，空/异常回退 pgvector，再空回退关键词。
     */
    private List<KnowledgeHit> semanticSearch(
            String tenantId, String kbId, String query, int topK,
            boolean thresholdEnabled, double threshold, String sourceVersion,
            Supplier<String> queryVector) {
        String queryVec = queryVector.get();
        if (queryVec != null) {
            List<KnowledgeHit> esItems = decodeSearchRows(searchIndexer.searchKnn(
                    tenantId, kbId, queryVec, topK, sourceVersion), kbId, false);
            if (!esItems.isEmpty()) {
                List<KnowledgeHit> filtered = esItems;
                if (thresholdEnabled) {
                    filtered = esItems.stream()
                            .filter(item -> scoreOf(item) >= threshold)
                            .toList();
                }
                return filtered;
            }
            try {
                List<Map<String, Object>> rows = sourceVersion == null
                        ? chunkMapper.vectorSearch(tenantId, kbId, queryVec, topK,
                                thresholdEnabled ? threshold : null)
                        : chunkMapper.vectorSearchPinned(tenantId, kbId, queryVec, topK,
                                thresholdEnabled ? threshold : null, sourceVersion);
                if (!rows.isEmpty()) {
                    return decodeSearchRows(rows, kbId, true);
                }
            } catch (Exception e) {
                log.warn("向量检索不可用，回退关键词检索: {}", e.getMessage(), e);
            }
        }
        return keywordSearch(tenantId, kbId, query, topK, sourceVersion);
    }

    /**
     * 全文检索：ES 可用走 ES，否则 SQL 字面子串检索。
     */
    private List<KnowledgeHit> fullTextSearch(
            String tenantId, String kbId, String query, int topK, String sourceVersion) {
        try {
            List<KnowledgeHit> esItems = decodeSearchRows(searchIndexer.search(
                    tenantId, kbId, query, topK, sourceVersion), kbId, false);
            if (!esItems.isEmpty()) {
                return esItems;
            }
        } catch (Exception e) {
            // ES 不可用时回退 SQL 字面子串检索
            log.warn("ES 检索不可用，回退 SQL 字面子串检索: {}", e.getMessage(), e);
        }
        return keywordSearch(tenantId, kbId, query, topK, sourceVersion);
    }

    /**
     * 混合检索：向量 + 关键词并行，向量分 min-max 归一化后加权融合。
     * 向量腿优先 ES kNN 再退 pgvector，关键词腿优先 ES BM25 再退 SQL 字面子串检索。
     */
    private List<KnowledgeHit> hybridSearch(
            String tenantId, String kbId, String query, int topK,
            double vectorWeight, boolean thresholdEnabled, double threshold, String sourceVersion,
            Supplier<String> queryVector) {
        int pool = Math.max(topK * 2, 10);
        String queryVec = queryVector.get();
        List<KnowledgeHit> vectorItems = new ArrayList<>();
        if (queryVec != null) {
            vectorItems = decodeSearchRows(searchIndexer.searchKnn(tenantId, kbId, queryVec, pool, sourceVersion), kbId, false);
            if (vectorItems.isEmpty()) {
                try {
                    vectorItems = decodeSearchRows(sourceVersion == null
                            ? chunkMapper.vectorSearch(tenantId, kbId, queryVec, pool,
                                    thresholdEnabled ? threshold : null)
                            : chunkMapper.vectorSearchPinned(tenantId, kbId, queryVec, pool,
                                    thresholdEnabled ? threshold : null, sourceVersion), kbId, true);
                } catch (Exception e) {
                    log.warn("混合检索向量腿不可用: {}", e.getMessage(), e);
                }
            }
        }
        List<KnowledgeHit> keywordItems = decodeSearchRows(searchIndexer.search(
                tenantId, kbId, query, pool, sourceVersion), kbId, false);
        if (keywordItems.isEmpty()) {
            keywordItems = keywordSearch(tenantId, kbId, query, pool, sourceVersion);
        }
        List<KnowledgeHit> fused = fuseHybrid(vectorItems, keywordItems, topK, vectorWeight);
        if (thresholdEnabled) {
            fused = fused.stream().filter(item -> scoreOf(item) >= threshold).toList();
        }
        return fused;
    }

    /**
     * 融合向量与关键词结果；共享集合的不同来源版本仍各自保留出处和分数。
     */
    private List<KnowledgeHit> fuseHybrid(List<KnowledgeHit> vectorItems, List<KnowledgeHit> keywordItems,
            int topK, double vectorWeight) {
        double min = vectorItems.stream().mapToDouble(KnowledgeHit::score).min().orElse(0.0);
        double max = vectorItems.stream().mapToDouble(KnowledgeHit::score).max().orElse(1.0);
        double range = max - min;
        Map<HitIdentity, KnowledgeHit> merged = new LinkedHashMap<>();
        for (KnowledgeHit item : vectorItems) {
            double norm = range > 0 ? (item.score() - min) / range : 1.0;
            merged.put(HitIdentity.of(item), item.withScore(norm * vectorWeight));
        }
        for (KnowledgeHit item : keywordItems) {
            HitIdentity identity = HitIdentity.of(item);
            KnowledgeHit existing = merged.get(identity);
            merged.put(identity, existing == null ? item.withScore(1.0 - vectorWeight)
                    : existing.withScore(existing.score() + (1.0 - vectorWeight)));
        }
        return merged.values().stream().sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(topK).map(item -> item.withScore(roundScore(item.score()))).toList();
    }

    private record HitIdentity(String kbId, String documentId, String sourceVersion, String chunkId) {
        private static HitIdentity of(KnowledgeHit hit) {
            return new HitIdentity(hit.kbId(), hit.documentId(), hit.sourceVersion(), hit.chunkId());
        }
    }

    /**
     * 取结果项得分。
     */
    private double scoreOf(KnowledgeHit item) { return item.score(); }

    /**
     * 应用 Rerank 重排（按 relevance_score 降序，可覆盖分数与阈值过滤）。
     */
    private List<KnowledgeHit> applyRerank(List<KnowledgeHit> items, List<RerankProvider.RerankResult> reranked,
            boolean thresholdEnabled, double threshold) {
        List<KnowledgeHit> result = new ArrayList<>();
        for (var rank : reranked) {
            if (rank.index() < 0 || rank.index() >= items.size()
                    || (thresholdEnabled && rank.relevanceScore() < threshold)) continue;
            KnowledgeHit item = items.get(rank.index());
            result.add(item.reranked(roundScore(rank.relevanceScore()), roundScore(item.score())));
        }
        return result;
    }

    /**
     * 解析知识库分段长度（列值 null 时用默认值）。
     */
    private int chunkSizeOf(
            KnowledgeBaseDO base, KnowledgeOperationalSettings.Snapshot settings) {
        return base.getChunkSize() == null
                ? settings.chunkSize() : base.getChunkSize();
    }

    /**
     * 解析知识库分段重叠（列值 null 时用默认值）。
     */
    private int chunkOverlapOf(
            KnowledgeBaseDO base, KnowledgeOperationalSettings.Snapshot settings) {
        return base.getChunkOverlap() == null
                ? settings.chunkOverlap() : base.getChunkOverlap();
    }

    /**
     * 知识库索引方式（high_quality / economical）。
     */
    private String indexingModeOf(
            KnowledgeBaseDO base, KnowledgeOperationalSettings.Snapshot settings) {
        return KnowledgeOperationalSettings.resolveIndexingMode(base.getIndexingMode(),settings);
    }

    /**
     * 知识库是否经济索引（仅关键词，不生成向量）。
     */
    private boolean economical(
            KnowledgeBaseDO base, KnowledgeOperationalSettings.Snapshot settings) {
        return KnowledgeConstants.INDEXING_MODE_ECONOMICAL.equals(indexingModeOf(base, settings));
    }

    /**
     * 检索模式（request 覆盖优先，否则库列值）。
     */
    private String retrievalModeOf(KnowledgeBaseDO base, KnowledgeSearchRequest request,
            KnowledgeOperationalSettings.Snapshot settings) {
        if (request != null && request.retrievalMode() != null && !request.retrievalMode().isBlank()) {
            return request.retrievalMode();
        }
        return base.getRetrievalMode() == null || base.getRetrievalMode().isBlank()
                ? settings.defaultRetrievalMode() : base.getRetrievalMode();
    }

    /**
     * 相关度阈值开关（request 覆盖优先，否则库列值）。
     */
    private boolean scoreThresholdEnabled(KnowledgeBaseDO base, KnowledgeSearchRequest request) {
        if (request != null && request.scoreThresholdEnabled() != null) {
            return request.scoreThresholdEnabled();
        }
        return Boolean.TRUE.equals(base.getScoreThresholdEnabled());
    }

    /**
     * 相关度阈值（request 覆盖优先，否则库列值）。
     */
    private double scoreThresholdOf(KnowledgeBaseDO base, KnowledgeSearchRequest request,
            KnowledgeOperationalSettings.Snapshot settings) {
        if (request != null && request.scoreThreshold() != null) {
            return request.scoreThreshold();
        }
        return base.getScoreThreshold() == null
                ? settings.defaultScoreThreshold() : base.getScoreThreshold();
    }

    /**
     * 混合检索向量权重（request 覆盖优先，否则库列值）。
     */
    private double vectorWeightOf(KnowledgeBaseDO base, KnowledgeSearchRequest request,
            KnowledgeOperationalSettings.Snapshot settings) {
        if (request != null && request.vectorWeight() != null) {
            return clampWeight(request.vectorWeight());
        }
        return clampWeight(base.getVectorWeight() == null
                ? settings.defaultVectorWeight() : base.getVectorWeight());
    }

    /**
     * Rerank 开关（request 覆盖优先，否则库列值）。
     */
    private boolean rerankEnabledOf(KnowledgeBaseDO base, KnowledgeSearchRequest request) {
        if (request != null && request.rerankEnabled() != null) {
            return request.rerankEnabled();
        }
        return Boolean.TRUE.equals(base.getRerankEnabled());
    }

    /**
     * 权重收敛到 [0,1]。
     */
    private double clampWeight(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * 批量生成向量（单次调用，整体超时兜底；单块失败记 null 不阻断）。
     *
     * @param texts 文本列表
     * @return 与输入对齐的向量列表；整体失败返回 null
     */
    private List<String> embedBatch(
            String tenantId, KnowledgeBaseDO base, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        requireFrozenEmbedding(base);
        List<float[]> vectors = embeddingProvider.embedBatch(tenantId,
                base.getEmbeddingModelConfigId().toString(),
                base.getEmbeddingModelRevision(), texts);
        if (vectors == null) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            float[] vector = vectors == null || i >= vectors.size() ? null : vectors.get(i);
            if (vector != null && vector.length != base.getEmbeddingDimensions()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "embedding_dimensions_mismatch");
            }
            result.add(vector == null || vector.length == 0 ? null : toVectorString(vector));
        }
        return result;
    }

    /**
     * 生成单条文本向量。
     *
     * @param text 文本
     * @return 向量字符串；embedding 不可用时返回 null
     */
    private String embed(String tenantId, KnowledgeBaseDO base, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        requireFrozenEmbedding(base);
        float[] vector = embeddingProvider.embed(tenantId,
                base.getEmbeddingModelConfigId().toString(),
                base.getEmbeddingModelRevision(), text);
        if (vector != null && vector.length != base.getEmbeddingDimensions()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "embedding_dimensions_mismatch");
        }
        return vector == null || vector.length == 0 ? null : toVectorString(vector);
    }

    private void requireFrozenEmbedding(KnowledgeBaseDO base) {
        if (base.getEmbeddingModelConfigId() == null
                || base.getEmbeddingModelRevision() == null
                || base.getEmbeddingModelRevision() <= 0
                || base.getEmbeddingDimensions() == null
                || base.getEmbeddingDimensions() <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "knowledge_embedding_model_not_frozen");
        }
    }

    /**
     * 将浮点向量序列化为 PG vector 字符串。
     *
     * @param vector 向量
     * @return "[0.1,0.2,...]" 形式
     */
    private String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * 关键词回退检索：按大小写不敏感的子串匹配。
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @param query    查询文本
     * @param topK     返回条数上限
     * @return 检索结果列表
     */
    private List<KnowledgeHit> keywordSearch(
            String tenantId, String kbId, String query, int topK, String sourceVersion) {
        List<KnowledgeChunkDO> rows = chunkMapper.searchKeyword(tenantId, kbId, query, topK,
                sourceVersion == null ? null : List.of(sourceVersion));
        return rows.stream().map(row -> new KnowledgeHit(kbId, String.valueOf(row.getDocumentId()),
                String.valueOf(row.getId()), row.getSourceVersion(), row.getContent(), 1.0, null)).toList();
    }

    private List<KnowledgeHit> filterBySourceVersion(
            List<KnowledgeHit> items, String sourceVersion) {
        if (sourceVersion == null || sourceVersion.isBlank()) {
            return items;
        }
        return items.stream()
                .filter(item -> sourceVersion.equals(item.sourceVersion()))
                .toList();
    }

    /** 原始 SQL/搜索 JSON 只在边界解码，后续来源过滤、融合与重排共享类型化候选。 */
    private List<KnowledgeHit> decodeSearchRows(List<Map<String, Object>> rows, String kbId, boolean round) {
        return rows.stream().map(row -> new KnowledgeHit(kbId, String.valueOf(row.get("document_id")),
                String.valueOf(row.get("chunk_id")), (String) row.get("source_version"),
                String.valueOf(row.get("content")), round ? roundScore(row.get("score"))
                        : row.get("score") instanceof Number n ? n.doubleValue() : 0.0,
                row.get("vector_score") instanceof Number n ? n.doubleValue() : null)).toList();
    }

    /**
     * 将得分统一为四位小数。
     *
     * @param value 原始得分
     * @return 格式化得分
     */
    private double roundScore(Object value) {
        try {
            return Math.round(Double.parseDouble(String.valueOf(value)) * 10000.0) / 10000.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 组装分段响应项。
     *
     * @param chunk 分段
     * @return 分段响应项
     */
    private ChunkItemResponse chunkItem(KnowledgeChunkDO chunk) {
        return new ChunkItemResponse(chunk.getId(), chunk.getChunkIndex(), chunk.getContent(),
                chunk.getEmbedding() != null, chunk.getSourceVersion(), chunk.getCreatedAt());
    }

    /**
     * 查询知识库是否存在且属于当前租户。
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @return 知识库
     */
    private KnowledgeBaseDO requireWritableBase(String tenantId, Long kbId) {
        KnowledgeBaseDO base = TransactionSynchronizationManager.isActualTransactionActive()
                ? revisionMapper.lockBase(Long.parseLong(tenantId),kbId) : requireBase(tenantId,kbId);
        if(base==null)throw new ApiException(HttpStatus.NOT_FOUND,"知识库不存在");
        if (base.getRebuildJobId() != null) throw conflict("knowledge_rebuild_write_locked");
        return base;
    }

    static void freezeModel(KnowledgeDocumentVersionDO version, KnowledgeBaseDO base) {
        version.setEmbeddingModelConfigId(base.getEmbeddingModelConfigId());
        version.setEmbeddingModelRevision(base.getEmbeddingModelRevision());
        version.setEmbeddingDimensions(base.getEmbeddingDimensions());
    }

    private KnowledgeBaseDO selectedModel(KnowledgeBaseDO base, String tenantId, String kbId,
            String sourceVersion, List<String> manifest) {
        List<String> versions = sourceVersion == null ? manifest : List.of(sourceVersion);
        if (versions == null || versions.isEmpty()) return base;
        List<KnowledgeDocumentVersionDO> selected = documentVersionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentVersionDO>()
                        .eq(KnowledgeDocumentVersionDO::getTenantId, Long.parseLong(tenantId))
                        .eq(KnowledgeDocumentVersionDO::getKbId, Long.parseLong(kbId))
                        .eq(KnowledgeDocumentVersionDO::getStatus, "ready")
                        .in(KnowledgeDocumentVersionDO::getSourceVersion, versions));
        if (selected.size() != versions.size()) throw conflict("knowledge_source_model_missing");
        KnowledgeDocumentVersionDO first = selected.getFirst();
        if (first.getEmbeddingModelConfigId() == null || first.getEmbeddingModelRevision() == null
                || first.getEmbeddingDimensions() == null || selected.stream().anyMatch(v ->
                !Objects.equals(v.getEmbeddingModelConfigId(), first.getEmbeddingModelConfigId())
                || !Objects.equals(v.getEmbeddingModelRevision(), first.getEmbeddingModelRevision())
                || !Objects.equals(v.getEmbeddingDimensions(), first.getEmbeddingDimensions())))
            throw conflict("knowledge_source_model_mixed");
        KnowledgeBaseDO model = new KnowledgeBaseDO();
        model.setEmbeddingModelConfigId(first.getEmbeddingModelConfigId());
        model.setEmbeddingModelRevision(first.getEmbeddingModelRevision());
        model.setEmbeddingDimensions(first.getEmbeddingDimensions());
        return model;
    }

    private KnowledgeBaseDO requireBase(String tenantId, Long kbId) {
        KnowledgeBaseDO base = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getId, kbId)
                        .eq(KnowledgeBaseDO::getTenantId, Long.parseLong(tenantId)));
        if (base == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "知识库不存在");
        }
        return base;
    }

    /**
     * 查询文档是否存在且属于当前租户。
     *
     * @param tenantId 租户 ID
     * @param docId    文档 ID
     * @return 文档
     */
    private KnowledgeDocumentDO requireDocument(String tenantId, Long docId) {
        KnowledgeDocumentDO document = documentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentDO>()
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getTenantId, Long.parseLong(tenantId))
                        .isNull(KnowledgeDocumentDO::getDeletedAt));
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        return document;
    }

    /** 公开语义身份解析，调用方无需跨模块依赖文档 Mapper。 */
    public long documentBaseId(String tenantId, Long docId) {
        return requireDocument(tenantId, docId).getKbId();
    }

    /** 仅解析真实同租户分段所属知识库，不授予该对象的读写权限。 */
    public long chunkBaseId(String tenantId, Long chunkId) {
        KnowledgeChunkDO chunk = chunkMapper.selectOne(new LambdaQueryWrapper<KnowledgeChunkDO>()
                .select(KnowledgeChunkDO::getDocumentId)
                .eq(KnowledgeChunkDO::getId, chunkId)
                .eq(KnowledgeChunkDO::getTenantId, Long.parseLong(tenantId)).last("LIMIT 1"));
        if (chunk == null) throw new ApiException(HttpStatus.NOT_FOUND, "分段不存在");
        return documentBaseId(tenantId, chunk.getDocumentId());
    }

    private String newSourceVersion() {
        return "kbv-" + SnowflakeIds.nextId();
    }

    private KnowledgeDocumentVersionDO requireVersion(
            String tenantId, Long documentId, String sourceVersion) {
        KnowledgeDocumentVersionDO version = requireVersionMetadata(tenantId, documentId, sourceVersion);
        version.setContent(storage.content(version));
        return version;
    }

    /** 分块编辑重组完整新正文，只需要原版本元数据，不预读即将被覆盖的旧正文。 */
    private KnowledgeDocumentVersionDO requireVersionMetadata(
            String tenantId, Long documentId, String sourceVersion) {
        KnowledgeDocumentVersionDO version = documentVersionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentVersionDO>()
                        .eq(KnowledgeDocumentVersionDO::getTenantId, Long.parseLong(tenantId))
                        .eq(KnowledgeDocumentVersionDO::getDocumentId, documentId)
                        .eq(KnowledgeDocumentVersionDO::getSourceVersion, sourceVersion));
        if (version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "文档来源版本不存在");
        }
        return version;
    }

    private void assertExpectedVersion(KnowledgeDocumentDO document, String expectedSourceVersion) {
        if (expectedSourceVersion == null || expectedSourceVersion.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "expected_source_version 不能为空");
        }
        if (!expectedSourceVersion.equals(document.getCurrentSourceVersion())) {
            throw conflict("文档来源版本已变化，请刷新后重试");
        }
    }

    /** 构造统一的并发或生命周期冲突响应。 */
    private static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }

    private void activateVersion(
            KnowledgeDocumentDO document, String expectedSourceVersion, String newSourceVersion) {
        if (documentMapper.compareAndSetCurrentVersion(document.getTenantId(), document.getId(),
                expectedSourceVersion, newSourceVersion) != 1) {
            throw conflict("文档来源版本已被其他请求推进，请刷新后重试");
        }
        document.setCurrentSourceVersion(newSourceVersion);
    }

    private List<KnowledgeChunkDO> chunksOf(
            String tenantId, Long documentId, String sourceVersion) {
        return chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getTenantId, Long.parseLong(tenantId))
                .eq(KnowledgeChunkDO::getDocumentId, documentId)
                .eq(KnowledgeChunkDO::getSourceVersion, sourceVersion)
                .orderByAsc(KnowledgeChunkDO::getChunkIndex));
    }

    private KnowledgeChunkDO copyChunk(
            KnowledgeChunkDO old, String sourceVersion, int index, String embedding) {
        return copyChunk(old, sourceVersion, index, embedding, old.getContent());
    }

    private KnowledgeChunkDO copyChunk(
            KnowledgeChunkDO old, String sourceVersion, int index, String embedding, String content) {
        KnowledgeChunkDO chunk = new KnowledgeChunkDO();
        chunk.setId(SnowflakeIds.nextId());
        chunk.setTenantId(old.getTenantId());
        chunk.setKbId(old.getKbId());
        chunk.setDocumentId(old.getDocumentId());
        chunk.setSourceVersion(sourceVersion);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setEmbedding(embedding);
        if (Objects.equals(content, old.getContent())) chunk.setContentId(old.getContentId());
        if (Objects.equals(content, old.getContent()) && Objects.equals(embedding, old.getEmbedding())) {
            chunk.setEmbeddingId(old.getEmbeddingId());
        }
        chunk.setCreatedAt(Instant.now());
        return chunk;
    }

    private String joinChunkContent(List<KnowledgeChunkDO> chunks) {
        return chunks.stream().map(KnowledgeChunkDO::getContent)
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    /**
     * 查询分段是否存在且属于当前租户。
     *
     * @param tenantId 租户 ID
     * @param chunkId  分段 ID
     * @return 分段
     */
    private KnowledgeChunkDO requireChunk(
            String tenantId, Long chunkId, String expectedSourceVersion) {
        KnowledgeChunkDO chunk = chunkMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeChunkDO>()
                        .eq(KnowledgeChunkDO::getId, chunkId)
                        .eq(KnowledgeChunkDO::getTenantId, Long.parseLong(tenantId))
                        .eq(KnowledgeChunkDO::getSourceVersion, expectedSourceVersion));
        if (chunk == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "分段不存在");
        }
        return chunk;
    }

    /**
     * 将数据库聚合结果映射为知识库计数，空组由调用方补零。
     *
     * @param rows 聚合行
     * @return 知识库 ID → 数量
     */
    private Map<Long, Long> countsByKb(List<KnowledgeBaseCount> rows) {
        Map<Long, Long> result = new HashMap<>();
        for (KnowledgeBaseCount row : rows) {
            result.put(row.kbId(), row.total());
        }
        return result;
    }

    /**
     * 知识库设置更新载体（9 个可空字段，null 表示不修改）。
     */
    public record KnowledgeBaseSettingsUpdate(
            Integer chunkSize,
            Integer chunkOverlap,
            String indexingMode,
            String retrievalMode,
            Integer topK,
            Boolean scoreThresholdEnabled,
            Double scoreThreshold,
            Double vectorWeight,
            Boolean rerankEnabled) {
    }
}
