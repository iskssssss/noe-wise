package com.skillforge.core.chat.profile;
import com.skillforge.core.shared.config.SnowflakeIds;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.model.invocation.LlmService;
import com.skillforge.core.chat.persistence.UserProfileMapper;
import com.skillforge.core.chat.persistence.UserProfileDO;
import com.skillforge.core.chat.persistence.ProfileSummary;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/**
 * 用户中期画像服务。
 *
 * <p>封装 {@code user_profiles} 的读取与 upsert，供会话上下文注入
 * 和异步画像摘要任务使用。画像为双字段：
 * {@code summary} 结构化画像（每轮注入）+ {@code summaries} 原始总结累积
 * （近期窗口，超阈值由 LLM 分层合并进 summary，成本恒定）。
 */
@Service
public class UserProfileService {

    /** 用户画像表映射器。 */
    private final UserProfileMapper userProfileMapper;

    /** LLM 服务（画像合并）。 */
    private final LlmService llmService;

    /** Jackson 对象映射器。 */
    private final ObjectMapper objectMapper;
    private final ObjectReader profileReader;
    private record StructuredProfile(@JsonProperty("用户目标") String target,
            @JsonProperty("关键偏好") Map<String, Object> preferences,
            @JsonProperty("历史结论") List<String> conclusions) { }

    /** 版本化运营配置。 */
    private final OperationalSettingsService settings;

    /**
     * 构造用户画像服务。
     *
     * @param userProfileMapper      用户画像表映射器
     * @param llmService             LLM 服务
     * @param objectMapper           Jackson 对象映射器
     * @param settings               版本化运营配置
     */
    public UserProfileService(
            UserProfileMapper userProfileMapper,
            LlmService llmService,
            ObjectMapper objectMapper,
            OperationalSettingsService settings) {
        this.userProfileMapper = userProfileMapper;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        var strictProfiles = objectMapper.copy();
        strictProfiles.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        this.profileReader = strictProfiles.readerFor(StructuredProfile.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.settings = settings;
    }

    /**
     * 查询用户画像。
     *
     * @param tenantId   租户 ID
     * @param endUserId  C 端用户 ID
     * @return 画像实体；不存在返回 null
     */
    public UserProfileDO get(String tenantId, Long endUserId) {
        return userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfileDO>()
                        .eq(UserProfileDO::getTenantId, Long.parseLong(tenantId))
                        .eq(UserProfileDO::getEndUserId, endUserId));
    }

    /**
     * upsert 用户画像摘要。
     *
     * @param tenantId         租户 ID
     * @param endUserId        C 端用户 ID
     * @param summary          跨会话摘要文本
     * @param structuredFields 结构化画像字段
     * @param preferences      偏好
     * @param interactionDelta 本次累加的交互次数
     * @param lastSessionAt    最近会话时间
     */
    public void upsertSummary(
            String tenantId,
            Long endUserId,
            String summary,
            Map<String, Object> structuredFields,
            Map<String, Object> preferences,
            int interactionDelta,
            Instant lastSessionAt) {
        userProfileMapper.upsert(
                SnowflakeIds.nextId(),
                Long.parseLong(tenantId),
                endUserId,
                summary,
                structuredFields == null ? Map.of() : structuredFields,
                preferences == null ? Map.of() : preferences,
                interactionDelta,
                lastSessionAt);
    }

    /**
     * 追加一条原始总结（工作流完成总结 / 闲聊总结的输出）。
     *
     * <p>追加到 {@code summaries} 窗口并裁剪；窗口达上限且开启增量合并时，
     * 用旧 {@code summary} + 新窗口明细 LLM 合并覆盖 {@code summary}（合并成本恒定）。
     *
     * @param tenantId   租户 ID
     * @param endUserId  C 端用户 ID
     * @param summaryText 本次总结文本
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void appendSummary(String tenantId, Long endUserId, String summaryText) {
        var memory = settings.read("memory");
        int max = memory.integer("max_raw_summaries");
        if (max <= 0) throw new IllegalArgumentException("profile_window_size_invalid");
        Instant now = Instant.now();
        var entry = new ProfileSummary(summaryText, com.skillforge.common.time.BusinessTime.format(now));
        // 自动提交的单语句返回精确写入快照；外部模型调用期间不持有数据库事务。
        UserProfileDO snapshot = userProfileMapper.appendRawSummary(SnowflakeIds.nextId(),
                Long.parseLong(tenantId), endUserId, initialProfile(summaryText), List.of(entry), max, now);
        if (snapshot == null) throw new IllegalStateException("profile_append_snapshot_missing");
        if (memory.bool("incremental_merge") && snapshot.getSummaries().size() >= max) {
            String merged = mergeProfile(tenantId, endUserId, requireStructuredProfile(snapshot.getSummary()),
                    List.copyOf(snapshot.getSummaries()), settings.read("prompts").text("profile_merge_prompt"));
            // 有新追加或其他画像写入时 CAS 返回 0；保留新窗口，不重新发起付费模型调用。
            userProfileMapper.mergeSummaryIfRevision(Long.parseLong(tenantId), endUserId,
                    snapshot.getRevision(), merged);
        }
    }

    /**
     * LLM 分层合并：结构化 {@code summary} + 近期窗口明细 → 结构化压缩画像。
     *
     * <p>合并输出结构化 JSON {@code {"用户目标":..., "关键偏好":{...}, "历史结论":[...]}}，
     * 结构化去重（如「喜欢咖啡」「偏好燕麦奶」「要热的」→「偏好热燕麦奶咖啡」）。
     *
     * @param tenantId  租户 ID
     * @param endUserId C 端用户 ID
     * @param oldSummary 当前结构化画像
     * @param summaries 近期原始总结窗口
     * @return 合并后的结构化画像 JSON
     */
    private String mergeProfile(
            String tenantId, Long endUserId, String oldSummary,
            List<ProfileSummary> summaries, String prompt) {
        var input = objectMapper.createObjectNode().put("old_profile", oldSummary);
        input.set("summaries", objectMapper.valueToTree(summaries));
        // 幂等键：{tenantId}:profile:merge:{雪花}（调用前生成，防重试双计）
        String mergeKey = String.valueOf(SnowflakeIds.nextId());
        LlmResult result;
        // 计量作用域：Provider 内自动计量 llm.chat（异步线程自行 open，字段取方法参数）
        try (MeterScope scope = MeterScope.open(CostContext.builder()
                .eventId(mergeKey)
                .endUserId(endUserId)
                .callPoint("profile.merge")
                .build())) {
            result = llmService.generate(prompt, input.toString());
        }
        String raw = result.content();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("profile_merge_invalid_json");
        }
        return requireStructuredProfile(raw.substring(start, end + 1));
    }

    private String initialProfile(String summaryText) {
        try {
            return objectMapper.writeValueAsString(new StructuredProfile("", Map.of(), List.of(summaryText)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("profile_json_encoding_failed", exception);
        }
    }

    private String requireStructuredProfile(String value) {
        StructuredProfile profile;
        try {
            profile = profileReader.readValue(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("profile_schema_invalid", exception);
        }
        if (profile == null || profile.target() == null || profile.preferences() == null
                || profile.conclusions() == null || profile.conclusions().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException("profile_schema_invalid");
        }
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("profile_json_encoding_failed", exception);
        }
    }
}
