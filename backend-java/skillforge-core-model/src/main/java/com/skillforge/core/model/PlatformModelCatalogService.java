package com.skillforge.core.model;

import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.Selection;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/** 平台模型版本的唯一写入能力；机构与后台任务只通过模型引用解析当前可用配置。 */
@Transactional
public class PlatformModelCatalogService {
    private final PlatformModelMapper mapper;
    private final CryptoService crypto;
    private final ObjectMapper json;
    private final ModelProviderCatalog providers;
    private final TimePricingRules timeRules;

    public PlatformModelCatalogService(PlatformModelMapper mapper, CryptoService crypto, ObjectMapper json,
                                       ModelProviderCatalog providers, TimePricingRules timeRules) {
        this.mapper = mapper; this.crypto = crypto;
        // 草稿 envelope 经 JSON tree 读取，必须在首次解析时保留小数，不能先转 double 再恢复。
        this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.providers = Objects.requireNonNull(providers);
        this.timeRules = Objects.requireNonNull(timeRules);
    }

    public List<PlatformModelDirectorySummary> summaries() { return mapper.listSummaries(); }

    public List<ModelView> list() {
        var references = mapper.draftRuleRefs();
        var policies = timeRules.readAll(references.stream().map(PlatformModelMapper.ModelRuleRef::ruleId).toList());
        var byModel = references.stream().collect(Collectors.toMap(PlatformModelMapper.ModelRuleRef::modelId, r -> policies.get(r.ruleId())));
        return mapper.listDrafts().stream().map(stored -> draftView(stored, byModel)).toList();
    }

    /** 非媒体计价只读取本次精确匹配的草稿，保留所有匹配配置参与价格唯一性判断。 */
    public List<ModelView> pricingModels(String provider, String model) {
        var rows = mapper.pricingDrafts(provider, model);
        if (rows.isEmpty()) return List.of();
        var ruleIds = rows.stream().map(PlatformModelMapper.PricingDraft::ruleId).filter(Objects::nonNull).distinct().toList();
        Map<Long, TimePricingPolicy> policies = ruleIds.isEmpty() ? Map.of() : timeRules.readAll(ruleIds);
        Map<String, TimePricingPolicy> byModel = new java.util.HashMap<>();
        for (var row : rows) if (row.ruleId() != null) byModel.put(row.modelId(), policies.get(row.ruleId()));
        return rows.stream().map(row -> draftView(row.storedDraft(), byModel)).toList();
    }

    public ModelView detail(String id) { requireId(id); return draftView(requireDraft(id)); }

    /** 平台管理员显式连接探测使用工作草稿，不改变线上发布指针。 */
    public PlatformModelConfig draftForProbe(String id) { return draftConfig(requireDraft(id)); }

    /** 校验未保存输入，仅在连接范围不变时复用已有凭据；不写入草稿或发布版本。 */
    public PlatformModelConfig previewForProbe(String id, ModelWrite request) {
        PlatformModelConfig previous = id == null ? null : draftForProbe(id);
        if (previous != null) {
            if (!Objects.equals(request.expectedRevision(), previous.revision())) throw conflict();
            if (!Objects.equals(request.kind(), previous.kind())) throw invalid("model_kind_immutable");
        }
        return validate(id == null ? "0" : id, 0, request, previous);
    }

    /** 测试结果只附到同一草稿修订，不增加发布版本或覆盖并发编辑。 */
    public void saveCompatibilityReport(String id, long expectedRevision, ModelCompatibilityReport report) {
        requireId(id);
        if (expectedRevision < 1 || report.draftRevision() != expectedRevision) throw invalid("model_report_revision_invalid");
        try {
            if (mapper.saveCompatibilityReport(id, expectedRevision, json.writeValueAsString(report)) != 1) throw conflict();
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("model_report_encoding_failed", failure);
        }
    }

    public List<ModelSummary> available() {
        return mapper.listCurrent().stream().map(this::decode).filter(PlatformModelConfig::enabled)
                .map(c -> new ModelSummary(c.id(), c.name(), c.kind(), c.modelId(), c.maxOutputTokens(),
                        c.reasoning(), c.dimensions(), c.revision())).toList();
    }

    public List<ModelView> history(String id) {
        requireId(id); requireDraft(id);
        var references = mapper.revisionRuleRefs(id);
        var policies = timeRules.readAll(references.stream().map(PlatformModelMapper.ModelRuleRef::ruleId).toList());
        var byRevision = references.stream().collect(Collectors.toMap(PlatformModelMapper.ModelRuleRef::revision, r -> policies.get(r.ruleId())));
        return mapper.history(id).stream().map(this::decode).map(c -> withTimePricing(c, byRevision.get(c.revision()))).map(ModelView::from).toList();
    }

    public ModelView create(ModelWrite request, String actor) {
        requireActor(actor);
        String id = Long.toString(SnowflakeIds.nextId());
        PlatformModelConfig value = validate(id, 1, request, null);
        mapper.createDraft(id, encode(value), timeRules.save(value.timePricing()));
        return ModelView.from(value, null, true);
    }

    public ModelView update(String id, ModelWrite request, String actor) {
        requireActor(actor);
        PlatformModelConfig previous = draftConfig(requireDraft(id));
        if (request.expectedRevision() == null || request.expectedRevision() != previous.revision()) throw conflict();
        if (!Objects.equals(request.kind(), previous.kind())) throw invalid("model_kind_immutable");
        PlatformModelConfig value = validate(id, Math.addExact(previous.revision(), 1), request, previous);
        if (mapper.saveDraft(id, previous.revision(), encode(value), sameTimeRule(id, previous.timePricing(), value.timePricing())) != 1) throw conflict();
        return detail(id);
    }

    /** 发布冻结工作草稿，重复保存只更新草稿 CAS，不增加发布历史。 */
    public ModelView publish(String id, long expectedRevision, String actor) {
        requireActor(actor);
        ModelView draft = detail(id);
        if (draft.revision() != expectedRevision) throw conflict();
        PlatformModelConfig source = draftConfig(requireDraft(id));
        validateReasoning(source);
        if (!draft.hasUnpublishedChanges()) return draft;
        long publishedRevision = draft.publishedRevision() == null ? 1 : Math.addExact(draft.publishedRevision(), 1);
        PlatformModelConfig published = withRevision(source, publishedRevision);
        if (mapper.publishDraft(id, expectedRevision, publishedRevision) != 1) throw conflict();
        mapper.append(id, publishedRevision, encode(published), actor, mapper.draftRuleId(id));
        return detail(id);
    }

    public PlatformModelConfig current(String id) {
        requireId(id);
        String stored = mapper.current(id);
        if (stored == null) throw new ApiException(HttpStatus.NOT_FOUND, "platform_model_not_found");
        return decodePublished(stored);
    }

    /** 将历史版本恢复到工作草稿，显式发布后才对新执行生效。 */
    public ModelView rollback(String id, long revision, long expectedRevision, String actor) {
        requireActor(actor);
        PlatformModelConfig current = draftConfig(requireDraft(id));
        if (current.revision() != expectedRevision) throw conflict();
        PlatformModelConfig source = readRevision(id, revision);
        validateReasoning(source);
        PlatformModelConfig restored = withRevision(source, Math.addExact(current.revision(), 1));
        if (mapper.saveDraft(id, expectedRevision, encode(restored), mapper.revisionRuleId(id, revision)) != 1) throw conflict();
        return detail(id);
    }

    public PlatformModelConfig readRevision(String id, long revision) {
        requireId(id);
        String stored = mapper.revision(id, revision);
        if (stored == null) throw new ApiException(HttpStatus.NOT_FOUND, "platform_model_revision_not_found");
        return decodePublished(stored);
    }

    /** 查询校验失败可由上层转换为字段问题，不应将只读外层事务标记为回滚。 */
    @Transactional(readOnly = true, noRollbackFor = {ApiException.class, IllegalArgumentException.class})
    public PlatformModelConfig requireEnabled(String id, String kind) {
        PlatformModelConfig c = current(id);
        if (!c.enabled()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "platform_model_disabled");
        if (!c.kind().equals(kind)) throw invalid("platform_model_kind_mismatch");
        validateReasoning(c);
        return c;
    }

    public ModelSelections selections(String tenantId) {
        bindTenant(tenantId);
        ModelSelections value = mapper.selections(tenantId);
        return value == null ? new ModelSelections(null, null, null) : value;
    }

    public ModelSelections select(String tenantId, ModelSelections value) {
        bindTenant(tenantId);
        for (String kind : List.of("LLM", "EMBEDDING", "RERANK")) {
            String id = value.forKind(kind);
            if (id != null) requireEnabled(id, kind);
        }
        mapper.saveSelections(tenantId, value);
        return value;
    }

    public PlatformModelConfig resolveDefault(String tenantId, String kind) {
        String id = selections(tenantId).forKind(kind);
        if (id == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSTITUTION_MODEL_SELECTION_REQUIRED",
                "请先在机构设置中配置所需的默认模型", false);
        return requireEnabled(id, kind);
    }

    public PlatformModelConfig resolveLlm(String tenantId, String agentId) {
        bindTenant(tenantId);
        if (agentId != null) {
            requireId(agentId);
            if (!mapper.agentExists(tenantId, agentId)) throw new ApiException(HttpStatus.NOT_FOUND, "agent_not_found");
            String selected = mapper.agentSelection(tenantId, agentId);
            if (selected != null) return requireEnabled(selected, "LLM");
        }
        return resolveDefault(tenantId, "LLM");
    }

    /** 保存之外，发布与运行冻结也使用相同的 SDK 精确策略校验。 */
    public void validateReasoning(PlatformModelConfig config) {
        validateTimePricing(config.kind(), config.providerId(), config.serviceId(), config.profileVersion(),
                config.modelId(), config.cachePricing(), config.timePricing());
        validatePricing(config.kind(), config.inputPricePerMillionCents(), config.outputPricePerMillionCents(),
                config.pricePerQuantityCents(), config.mediaPricing(), config.cachePricing());
        try {
            var resolved = ModelServiceAdmission.resolve(providers, config);
            if (!"LLM".equals(config.kind())) return;
            providers.validateReasoning(resolved,
                    com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy.valueOf(config.reasoning()));
            providers.validatePrefixCapabilities(resolved, config.prefixCapabilities());
        } catch (IllegalArgumentException rejected) {
            throw invalid("model_provider_profile_invalid");
        }
    }

    private PlatformModelConfig validate(String id, long revision, ModelWrite r, PlatformModelConfig old) {
        String name = text(r.name(), "model_name", 200);
        try { ModelProviderCatalog.ModelKind.valueOf(Objects.toString(r.kind(), "")); }
        catch (IllegalArgumentException rejected) { throw invalid("model_kind_invalid"); }
        validateTimePricing(r.kind(), r.providerId(), r.serviceId(), r.profileVersion(), r.modelId(), r.cachePricing(), r.timePricing());
        validateServiceScope(r.providerId(), r.serviceId(), r.kind());
        String model = text(r.modelId(), "model_id", 256);
        String endpoint = text(r.endpoint(), "model_endpoint", 2048);
        validateEndpoint(endpoint);
        int defaultTimeout = switch (r.kind()) {
            case "LLM" -> 600;
            case "EMBEDDING" -> 120;
            case "RERANK" -> 60;
            case "IMAGE_GENERATION", "VIDEO_GENERATION", "SPEECH_RECOGNITION", "SPEECH_SYNTHESIS" -> 600;
            default -> throw invalid("model_kind_invalid");
        };
        Integer requestedTimeout = r.timeoutSeconds();
        if (requestedTimeout == null) requestedTimeout = old == null ? defaultTimeout : old.timeoutSeconds();
        int timeout = positive(requestedTimeout, "model_timeout", 600);
        String provider = r.providerId(), service = r.serviceId(), profile = r.profileVersion();
        String protocol = r.protocol(), authentication = r.authenticationScheme(), dialect = null;
        String reasoning = null;
        if (r.kind().equals("LLM")) {
            reasoning = r.reasoning();
            com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy policy;
            try { policy = com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy.valueOf(Objects.toString(reasoning, "")); }
            catch (IllegalArgumentException rejected) { throw invalid("model_reasoning_invalid"); }
            ModelProviderCatalog.ResolvedProfile resolved;
            try {
                resolved = providers.resolve(new Selection(provider, service, profile, model,
                        ModelProtocol.fromId(protocol), AuthenticationScheme.fromId(authentication), endpoint));
                providers.validateCapabilities(resolved,
                        Set.of(com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind.TEXT), false, false,
                        false);
                providers.validateReasoning(resolved, policy);
                providers.validatePrefixCapabilities(resolved, r.prefixCapabilities());
            } catch (IllegalArgumentException rejected) {
                throw invalid("model_provider_profile_invalid");
            }
            endpoint = resolved.endpoint(); dialect = resolved.dialectVersion();
            int output = positive(r.maxOutputTokens(), "max_output_tokens", Integer.MAX_VALUE);
            int supported = positive(r.supportedMaxOutputTokens(), "supported_max_output_tokens", Integer.MAX_VALUE);
            int window = positive(r.contextWindowTokens(), "context_window_tokens", Integer.MAX_VALUE);
            if (output > supported || output >= window) throw invalid("model_output_exceeds_capacity");
            if (r.temperature() != null && (!Double.isFinite(r.temperature()) || r.temperature() < 0 || r.temperature() > 2)) throw invalid("model_temperature_invalid");
        } else {
            provider = text(provider, "model_provider", 64);
            try {
                var resolved = providers.resolve(new Selection(provider, service, profile, model,
                        ModelProtocol.fromId(protocol), AuthenticationScheme.fromId(authentication), endpoint));
                endpoint = resolved.endpoint(); dialect = resolved.dialectVersion();
            } catch (IllegalArgumentException rejected) { throw invalid("model_provider_profile_invalid"); }
            if (r.kind().equals("EMBEDDING")) positive(r.dimensions(), "embedding_dimensions", 65536);
            if (r.kind().equals("RERANK") && (r.path() == null || !r.path().startsWith("/") || r.path().startsWith("//")
                    || r.path().contains("?") || r.path().contains("#") || r.path().contains(".."))) throw invalid("rerank_path_invalid");
        }
        boolean supplied = r.apiKey() != null && !r.apiKey().isBlank();
        for (var price : new java.math.BigDecimal[]{r.inputPricePerMillionCents(), r.outputPricePerMillionCents(), r.pricePerQuantityCents()}) {
            if (!ModelPrices.valid(price)) throw invalid("model_price_invalid");
        }
        if (r.kind().equals("RERANK") && r.inputPricePerMillionCents() != null
                && r.pricePerQuantityCents() != null) throw invalid("rerank_price_unit_ambiguous");
        validatePricing(r.kind(), r.inputPricePerMillionCents(), r.outputPricePerMillionCents(), r.pricePerQuantityCents(), r.mediaPricing(), r.cachePricing());
        String currency = r.currency() == null ? "CNY" : r.currency();
        try { java.util.Currency.getInstance(currency); }
        catch (IllegalArgumentException invalid) { throw invalid("model_currency_invalid"); }
        boolean scopeMatches = old != null && Objects.equals(old.endpoint(), endpoint)
                && Objects.equals(old.providerId(), provider) && Objects.equals(old.serviceId(), service)
                && Objects.equals(old.authenticationScheme(), authentication);
        if (!supplied && !scopeMatches) throw invalid("model_credential_required_for_service_change");
        String credential = supplied ? crypto.encrypt(text(r.apiKey(), "model_credential", 16384)) : old.apiKeyEncrypted();
        return new PlatformModelConfig(id, revision, name, r.kind(), r.enabled(), provider, service, profile,
                protocol, authentication, dialect, endpoint, model, credential,
                r.kind().equals("LLM") ? r.supportedMaxOutputTokens() : null,
                r.kind().equals("LLM") ? r.contextWindowTokens() : null,
                r.kind().equals("LLM") ? r.maxOutputTokens() : null,
                r.kind().equals("LLM") ? r.temperature() : null, reasoning, timeout,
                r.kind().equals("EMBEDDING") ? r.dimensions() : null,
                r.kind().equals("RERANK") ? r.path() : null,
                r.inputPricePerMillionCents(), r.outputPricePerMillionCents(), r.pricePerQuantityCents(), currency, imagePolicy(r), null, r.prefixCapabilities(), r.mediaPricing(), r.cachePricing(), r.timePricing());
    }

    /** 适用范围属于规则数据；不从服务商、型号或缓存模式推断价格。 */
    private static void validateTimePricing(String kind, String provider, String service, String profile,
            String model, ModelCachePricing cache, TimePricingPolicy policy) {
        if (policy == null) return;
        if (!"LLM".equals(kind) || !policy.appliesTo(provider, service, profile, model))
            throw invalid("model_time_pricing_scope_invalid");
    }

    private static void validatePricing(String kind, java.math.BigDecimal input, java.math.BigDecimal output,
            java.math.BigDecimal quantity, MediaCostPricing media, ModelCachePricing cache) {
        if (cache != null && !"LLM".equals(kind)) throw invalid("model_cache_pricing_kind_invalid");
        boolean mediaKind = Set.of("IMAGE_GENERATION", "VIDEO_GENERATION", "SPEECH_RECOGNITION", "SPEECH_SYNTHESIS").contains(kind);
        if (mediaKind && (input != null || output != null || quantity != null)) throw invalid("model_media_generic_price_ambiguous");
        if (!mediaKind && media != null) throw invalid("model_media_pricing_kind_invalid");
        if (media != null) {
            try { media.requireKind(kind); }
            catch (IllegalArgumentException rejected) { throw invalid("model_media_pricing_kind_invalid"); }
        }
    }

    /** 视觉能力只来自管理员明确发布的配置，模型名称不参与能力猜测。 */
    private ModelImagePolicy imagePolicy(ModelWrite request) {
        var policy = request.imagePolicy() == null
                ? ModelImagePolicy.textOnly() : request.imagePolicy();
        if (policy.enabled() && !"LLM".equals(request.kind())) throw invalid("model_image_kind_invalid");
        if (policy.enabled()) {
            var support = providers.imageSupport(request.providerId(), request.serviceId(), ModelProtocol.fromId(request.protocol()), request.modelId());
            if (support.userImages() == ModelProviderCatalog.SupportLevel.UNSUPPORTED
                    || policy.toolImages() && support.toolImages() == ModelProviderCatalog.SupportLevel.UNSUPPORTED
                    || policy.baseTokens() == 0 && policy.tileTokens() == 0) throw invalid("model_image_policy_invalid");
        }
        return policy;
    }

    private void validateServiceScope(String providerId, String serviceId, String kind) {
        try {
            var service = providers.serviceProfile(providerId, serviceId);
            if (service.region() == ModelProviderCatalog.ServiceRegion.UNVERIFIED
                    || !service.modelKinds().contains(ModelProviderCatalog.ModelKind.valueOf(kind)))
                throw invalid("model_domestic_service_required");
        } catch (IllegalArgumentException rejected) { throw invalid("model_provider_profile_invalid"); }
    }

    private void bindTenant(String tenant) {
        requireId(tenant);
        String current = TenantContext.getTenantId();
        if (current != null && !current.equals(tenant)) throw new ApiException(HttpStatus.FORBIDDEN, "tenant_scope_mismatch");
        mapper.bindTenant(tenant);
    }

    private PlatformModelConfig decode(String value) {
        try { return json.readValue(value, PlatformModelConfig.class); }
        catch (Exception failure) { throw new IllegalStateException("platform_model_snapshot_invalid"); }
    }

    private String requireDraft(String id) {
        requireId(id);
        String stored = mapper.draft(id);
        if (stored == null) throw new ApiException(HttpStatus.NOT_FOUND, "platform_model_not_found");
        return stored;
    }

    private PlatformModelConfig draftConfig(String stored) {
        try {
            var config = decode(json.readTree(stored).get("config").toString());
            return withTimePricing(config, timeRules.read(mapper.draftRuleId(config.id())));
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("platform_model_draft_invalid", failure); }
    }

    private ModelView draftView(String stored) { return draftView(stored, null); }

    private ModelView draftView(String stored, Map<String, TimePricingPolicy> policies) {
        try {
            var value = json.readTree(stored);
            var revision = value.get("published_revision");
            var config = decode(value.get("config").toString());
            config = withTimePricing(config, policies == null ? timeRules.read(mapper.draftRuleId(config.id())) : policies.get(config.id()));
            return ModelView.from(config,
                    revision == null || revision.isNull() ? null : revision.longValue(),
                    value.path("has_unpublished_changes").asBoolean());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("platform_model_draft_invalid", failure); }
    }

    private PlatformModelConfig withRevision(PlatformModelConfig source, long revision) {
        var snapshot = json.valueToTree(source);
        ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot).put("revision", revision);
        return decode(snapshot.toString());
    }

    private String encode(PlatformModelConfig value) {
        try {
            var stored = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(value);
            stored.remove("time_pricing");
            return json.writeValueAsString(stored);
        }
        catch (Exception failure) { throw new IllegalStateException("platform_model_snapshot_invalid"); }
    }

    private PlatformModelConfig decodePublished(String stored) {
        var config = decode(stored);
        return withTimePricing(config, timeRules.read(mapper.revisionRuleId(config.id(), config.revision())));
    }

    private Long sameTimeRule(String id, TimePricingPolicy previous, TimePricingPolicy next) {
        return Objects.equals(previous, next) ? mapper.draftRuleId(id) : timeRules.save(next);
    }

    private static PlatformModelConfig withTimePricing(PlatformModelConfig c, TimePricingPolicy policy) {
        return new PlatformModelConfig(c.id(), c.revision(), c.name(), c.kind(), c.enabled(),
                c.providerId(), c.serviceId(), c.profileVersion(), c.protocol(), c.authenticationScheme(),
                c.dialectVersion(), c.endpoint(), c.modelId(), c.apiKeyEncrypted(), c.supportedMaxOutputTokens(),
                c.contextWindowTokens(), c.maxOutputTokens(), c.temperature(), c.reasoning(), c.timeoutSeconds(),
                c.dimensions(), c.path(), c.inputPricePerMillionCents(), c.outputPricePerMillionCents(),
                c.pricePerQuantityCents(), c.currency(), c.imagePolicy(), c.compatibilityReport(),
                c.prefixCapabilities(), c.mediaPricing(), c.cachePricing(), policy);
    }

    private static int positive(Integer value, String field, int max) {
        if (value == null || value <= 0 || value > max) throw invalid(field + "_invalid");
        return value;
    }
    private static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw invalid(field + "_invalid");
        return value.trim();
    }
    private static void requireId(String value) {
        try { if (value == null || Long.parseLong(value) <= 0) throw invalid("model_reference_invalid"); }
        catch (NumberFormatException failure) { throw invalid("model_reference_invalid"); }
    }
    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "platform_actor_required");
    }
    private static void validateEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) throw invalid("model_endpoint_invalid");
        } catch (IllegalArgumentException invalid) { throw invalid("model_endpoint_invalid"); }
    }
    private static ApiException invalid(String reason) { return new ApiException(HttpStatus.BAD_REQUEST, reason); }
    private static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "model_revision_conflict"); }

    public record ModelSummary(String id, String name, String kind, String modelId,
            Integer maxOutputTokens, String reasoning, Integer dimensions, long revision) { }
}
