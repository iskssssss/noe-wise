package com.skillforge.core.chat.profile;

import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.session.SessionView;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.model.invocation.LlmService;
import com.skillforge.core.chat.persistence.ChatMessageMapper;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.application.TenantDirectoryService;
import com.skillforge.core.chat.persistence.ChatMessageDO;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import jakarta.annotation.PreDestroy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 用户画像异步摘要任务。
 *
 * <p>双触发：① 定时扫描已空闲且未生成摘要的会话（兜底）；② 同会话
 * 非工作流消息累计达阈值（默认 30 条）立即触发。输入排除工作流执行消息
 * （metadata.execution_id 非空的），经空转防护后 LLM 总结，画像追加到
 * {@code user_profiles.summaries}（分层/增量合并）。Runtime Memory 事实只允许
 * 由 Agent Runtime 正式链路写入。
 * 计数器 Key 带 TTL（默认 7 天）防残留计数误触发。
 */
@Component
public class UserProfileSummarizer {
    private static final int SUMMARY_BATCH_SIZE = 20;

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(UserProfileSummarizer.class);

    /** 闲聊消息计数器 Redis 键前缀（对齐 memoryKey 命名空间）。 */
    private static final String NON_WORKFLOW_COUNT_PREFIX = ":session:";
    private static final String NON_WORKFLOW_COUNT_SUFFIX = ":non_workflow_count";
    /** 递增与首次 TTL 同一原子动作；归还配额复用相同脚本。 */
    private static final DefaultRedisScript<Long> ADD_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCRBY', KEYS[1], ARGV[1])
            if redis.call('PTTL', KEYS[1]) < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return count
            """, Long.class);
    /** 多实例只在仍有完整额度时预占，避免重复扣减到负值。 */
    private static final DefaultRedisScript<Long> CLAIM_QUOTA = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local quota = tonumber(ARGV[1])
            if current < quota then return 0 end
            redis.call('INCRBY', KEYS[1], -quota)
            return 1
            """, Long.class);

    /** 每实例独立的有界异步池；模型调用不得回落到请求线程执行。 */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            runnable -> {
                Thread thread = new Thread(runnable, "casual-summarize");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Object lifecycleLock = new Object();
    private final Set<SummaryKey> pending = new HashSet<>();
    private volatile boolean closed;
    private record SummaryKey(String tenantId, String sessionId) { }
    /** 仅由定时扫描推进的实例内位置；空转不冒充摘要成功，不保存正文或模型结果。 */
    private record IdleCursor(Instant updatedAt,String sessionId) { }
    private final Map<Long,IdleCursor> idleCursors=new HashMap<>();
    private final class SummaryTask implements Runnable {
        private final SummaryKey key;
        private final String counterKey;
        private final int threshold;
        private final long ttlMillis;
        private SummaryTask(SummaryKey key, String counterKey, int threshold, long ttlMillis) {
            this.key = key; this.counterKey = counterKey; this.threshold = threshold; this.ttlMillis = ttlMillis;
        }
        @Override public void run() {
            try { summarizeCasual(key.tenantId(), key.sessionId()); }
            finally { synchronized (lifecycleLock) { pending.remove(key); } }
        }
        private boolean restoreTrigger() {
            try {
                Long restored = redisTemplate.execute(ADD_WITH_TTL, List.of(counterKey),
                        String.valueOf(threshold), String.valueOf(ttlMillis));
                if (restored == null) throw new IllegalStateException("profile_summary_quota_restore_missing");
                return true;
            } catch (RuntimeException exception) {
                log.warn("画像摘要触发配额归还失败 diagnostic={}",
                        com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(exception));
                return false;
            } finally {
                synchronized (lifecycleLock) { pending.remove(key); }
            }
        }
    }
    private boolean cancelQueued() {
        boolean restored = true;
        for (Runnable cancelled : executor.shutdownNow()) {
            if (cancelled instanceof SummaryTask task && !task.restoreTrigger()) restored = false;
        }
        return restored;
    }

    /** 停机拒绝新触发，先等待已接收任务；超时中断并在未静止时明确失败。 */
    @PreDestroy
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            executor.shutdown();
        }
        boolean restoreFailed = false;
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                restoreFailed = !cancelQueued();
                boolean stopped = executor.awaitTermination(1, TimeUnit.SECONDS);
                if (restoreFailed) {
                    var failure = new IllegalStateException("profile_summary_quota_restore_failed");
                    if (!stopped) failure.addSuppressed(new IllegalStateException("profile_summary_shutdown_incomplete"));
                    throw failure;
                }
                if (!stopped) {
                    throw new IllegalStateException("profile_summary_shutdown_incomplete");
                }
            }
        } catch (InterruptedException exception) {
            try { restoreFailed |= !cancelQueued(); }
            finally { Thread.currentThread().interrupt(); }
            var failure = new IllegalStateException("profile_summary_shutdown_interrupted", exception);
            if (restoreFailed) failure.addSuppressed(new IllegalStateException("profile_summary_quota_restore_failed"));
            throw failure;
        }
    }

    /** 租户身份目录。 */
    private final TenantDirectoryService tenantDirectory;

    /** 会话表映射器。 */
    private final SessionMapper sessionMapper;

    /** 会话消息表映射器。 */
    private final ChatMessageMapper chatMessageMapper;

    /** 用户画像服务。 */
    private final UserProfileService userProfileService;

    /** 会话服务。 */
    private final SessionService sessionService;

    /** LLM 服务。 */
    private final LlmService llmService;

    /** Jackson 对象映射器。 */
    private final ObjectMapper objectMapper;

    /** 版本化运营配置。 */
    private final OperationalSettingsService settings;

    /** Redis 操作模板（闲聊计数器）。 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 构造画像摘要任务。
     *
     * @param tenantDirectory       租户身份目录
     * @param sessionMapper         会话表映射器
     * @param chatMessageMapper     会话消息表映射器
     * @param userProfileService    用户画像服务
     * @param sessionService        会话服务
     * @param llmService            LLM 服务
     * @param objectMapper          Jackson 对象映射器
     * @param settings              版本化运营配置
     * @param redisTemplate         Redis 操作模板
     */
    public UserProfileSummarizer(
            TenantDirectoryService tenantDirectory,
            SessionMapper sessionMapper,
            ChatMessageMapper chatMessageMapper,
            UserProfileService userProfileService,
            SessionService sessionService,
            LlmService llmService,
            ObjectMapper objectMapper,
            OperationalSettingsService settings,
            StringRedisTemplate redisTemplate) {
        this.tenantDirectory = tenantDirectory;
        this.sessionMapper = sessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.userProfileService = userProfileService;
        this.sessionService = sessionService;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 定时扫描空闲会话并生成画像摘要（双触发之一：空闲兜底）。
     */
    @Scheduled(cron = "${skillforge.memory-profile-summary-cron:0 */10 * * * *}", zone = "Asia/Shanghai")
    public void summarizeIdleSessions() {
        if (closed) return;
        try {
            Snapshot memory = settings.read("memory");
            Snapshot prompts = settings.read("prompts");
            List<String> tenants = tenantDirectory.tenantIds();
            for (String tenant : tenants) {
                TenantContext.setTenantId(tenant);
                try {
                    summarizeTenant(Long.valueOf(tenant), memory, prompts);
                } finally {
                    TenantContext.clear();
                }
            }
        } catch (Exception e) {
            log.error("用户画像摘要任务失败 diagnostic={}", com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(e));
        }
    }

    /**
     * 汇总单个租户的空闲会话。
     *
     * @param tenantId 租户 ID
     */
    private void summarizeTenant(Long tenantId, Snapshot memory, Snapshot prompts) {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(
                memory.integer("profile_summary_idle_minutes")));
        var cursor=idleCursors.get(tenantId);
        List<SessionDO> sessions=idleSessions(tenantId,cutoff,cursor);
        if(sessions.isEmpty()&&cursor!=null) {
            idleCursors.remove(tenantId);
            sessions=idleSessions(tenantId,cutoff,null);
        }
        if (sessions.isEmpty()) {
            return;
        }
        var last=sessions.getLast();
        idleCursors.put(tenantId,new IdleCursor(last.getUpdatedAt(),last.getSessionId()));
        List<String> sessionIds = sessions.stream().map(SessionDO::getSessionId).toList();
        List<ChatMessageDO> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getTenantId, tenantId)
                        .in(ChatMessageDO::getSessionId, sessionIds)
                        .orderByAsc(ChatMessageDO::getCreatedAt));
        Map<String, List<ChatMessageDO>> bySession = messages.stream()
                .collect(Collectors.groupingBy(ChatMessageDO::getSessionId));
        for (SessionDO session : sessions) {
            if (closed) return;
            List<ChatMessageDO> rows = bySession.getOrDefault(session.getSessionId(), List.of());
            summarizeSession(tenantId.toString(), session.getSessionId(), session.getEndUserId(), rows,
                    prompts.text("chat_summary_prompt"));
        }
        log.info("用户画像摘要完成: tenant={} sessions={}", tenantId, sessions.size());
    }

    /** 同一稳定键集用于顺序读取和一次尾部回绕；LIMIT约束候选量，不追填有效摘要数。 */
    private List<SessionDO> idleSessions(Long tenantId,Instant cutoff,IdleCursor cursor) {
        var query=new LambdaQueryWrapper<SessionDO>().eq(SessionDO::getTenantId,tenantId)
                .isNull(SessionDO::getSummaryText).lt(SessionDO::getUpdatedAt,cutoff);
        if(cursor!=null)query.and(after->after.gt(SessionDO::getUpdatedAt,cursor.updatedAt())
                .or(tie->tie.eq(SessionDO::getUpdatedAt,cursor.updatedAt()).gt(SessionDO::getSessionId,cursor.sessionId())));
        return sessionMapper.selectList(query.orderByAsc(SessionDO::getUpdatedAt,SessionDO::getSessionId)
                .last("LIMIT "+SUMMARY_BATCH_SIZE));
    }

    /**
     * 非工作流消息累计达阈值时触发闲聊总结（双触发之二：条数阈值）。
     *
     * <p>每轮用户消息后由对话链路调用；计数器 Key
     * {@code {tenant}:session:{session}:non_workflow_count} 带 TTL（默认 7 天），
     * 防长期不活跃会话重连后残留计数误触发；原子预占阈值配额后异步总结，并发新消息保留计数。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     */
    public void submitCasualSummary(String tenantId, String sessionId) {
        if (closed) return;
        try {
            Snapshot memory = settings.read("memory");
            String key = tenantId + NON_WORKFLOW_COUNT_PREFIX + sessionId + NON_WORKFLOW_COUNT_SUFFIX;
            int threshold = memory.integer("casual_threshold");
            long ttlMillis = Duration.ofDays(memory.integer("casual_counter_ttl_days")).toMillis();
            if (threshold <= 0 || ttlMillis <= 0) throw new IllegalStateException("profile_summary_counter_config_invalid");
            Long count = redisTemplate.execute(ADD_WITH_TTL, List.of(key), "1", String.valueOf(ttlMillis));
            if (count == null || count < threshold) return;
            SummaryKey summaryKey = new SummaryKey(tenantId, sessionId);
            synchronized (lifecycleLock) {
                if (closed || pending.contains(summaryKey)) return;
                pending.add(summaryKey);
                var task = new SummaryTask(summaryKey, key, threshold, ttlMillis);
                boolean claimed = false;
                try {
                    Long acquired = redisTemplate.execute(CLAIM_QUOTA, List.of(key), String.valueOf(threshold));
                    if (acquired == null) throw new IllegalStateException("profile_summary_quota_claim_missing");
                    if (acquired != 1) { pending.remove(summaryKey); return; }
                    claimed = true;
                    executor.execute(task);
                } catch (RejectedExecutionException exception) {
                    pending.remove(summaryKey);
                    boolean restored = !claimed || task.restoreTrigger();
                    log.warn("画像摘要队列已满 reason=profile_summary_queue_full quotaRestored={}", restored);
                } catch (RuntimeException exception) {
                    pending.remove(summaryKey);
                    if (claimed) task.restoreTrigger();
                    throw exception;
                }
            }
        } catch (Exception e) {
            log.warn("闲聊计数器写入失败 diagnostic={}", com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(e));
        }
    }

    /**
     * 异步闲聊总结：拉取会话消息 → 排除工作流执行消息 → 空转防护 → LLM 总结。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     */
    private void summarizeCasual(String tenantId, String sessionId) {
        TenantContext.setTenantId(tenantId);
        try {
            Snapshot prompts = settings.read("prompts");
            // 租户与会话来自已持久化的总结任务，后台执行不伪装成请求成员。
            SessionView session = sessionService.requireForSystem(tenantId, sessionId);
            List<ChatMessageDO> messages = chatMessageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessageDO>()
                            .eq(ChatMessageDO::getTenantId, Long.parseLong(tenantId))
                            .eq(ChatMessageDO::getSessionId, sessionId)
                            .orderByAsc(ChatMessageDO::getCreatedAt));
            summarizeSession(tenantId, session.getSessionId(), session.getEndUserId(), messages,
                    prompts.text("chat_summary_prompt"));
        } catch (Exception e) {
            log.error("闲聊总结失败 diagnostic={}", com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(e));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 单会话总结主流程（空闲兜底与阈值触发共用）。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话标识
     * @param endUserId 会话终端用户
     * @param messages  会话消息（原始）
     */
    private void summarizeSession(
            String tenantId, String sessionId, Long endUserId, List<ChatMessageDO> messages, String prompt) {
        // 排除工作流执行消息（metadata.execution_id 非空）：执行中不直接统计
        List<ChatMessageDO> casual = messages.stream()
                .filter(message -> message.getMetadata() == null
                        || message.getMetadata().get("execution_id") == null)
                .toList();
        if (MemorySummarizeGuard.shouldSkip(casual)) {
            return;
        }
        String summaryText = summarize(
                tenantId, sessionId, endUserId, casual, prompt);
        if (summaryText == null || summaryText.isBlank()) {
            return;
        }
        userProfileService.appendSummary(tenantId, endUserId, summaryText);
        sessionService.updateSummary(tenantId, sessionId, summaryText);
        log.debug("闲聊总结完成 tenant={}", tenantId);
    }

    /**
     * 调用 LLM 生成画像摘要。
     *
     * @param tenantId  租户 ID
     * @param sessionId 会话 ID
     * @param endUserId C 端用户 ID
     * @param messages  有效消息（已过滤工作流执行消息）
     * @return 画像总结文本；空转跳过或失败返回 null
     */
    private String summarize(
            String tenantId, String sessionId, Long endUserId,
            List<ChatMessageDO> messages, String prompt) {
        var conversation = objectMapper.createArrayNode();
        for (ChatMessageDO message : messages) {
            conversation.addObject().put("role", message.getRole()).put("content", message.getContent());
        }
        String input = objectMapper.createObjectNode().set("messages", conversation).toString();
        // 幂等键：{tenantId}:profile:{sessionId}:{日期}，同日重跑去重
        String profileKey = tenantId + ":profile:" + sessionId + ":"
                + java.time.LocalDate.now(com.skillforge.common.time.BusinessTime.ZONE);
        LlmResult result;
        // 计量作用域：异步线程内自行 open（ThreadLocal 不跨线程传递），上下文取方法参数；
        // Provider 内自动计量 llm.chat；幂等键 {tenantId}:profile:{sessionId}:{日期} 同日去重
        try (MeterScope scope = MeterScope.open(CostContext.builder()
                .eventId(profileKey)
                .sessionId(sessionId)
                .endUserId(endUserId)
                .callPoint("profile.summarize")
                .build())) {
            try {
                result = llmService.generate(prompt, input, List.of());
            } catch (Exception e) {
                log.warn("闲聊总结 LLM 调用失败 diagnostic={}", com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(e));
                return null;
            }
        }
        return parseProfile(result.content());
    }

    /** 仅消费完整 JSON 对象中的字符串画像；契约违规不落库，诊断不记录模型原文。 */
    private String parseProfile(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("画像摘要输出已拒绝 reason=empty_output");
            return null;
        }
        try {
            var parsed = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            if (!parsed.isObject() || !parsed.path("profile").isTextual()) {
                log.warn("画像摘要输出已拒绝 reason=profile_contract_invalid");
                return null;
            }
            return parsed.path("profile").textValue().strip();
        } catch (Exception invalid) {
            log.warn("画像摘要输出已拒绝 reason=invalid_json");
            return null;
        }
    }
}
