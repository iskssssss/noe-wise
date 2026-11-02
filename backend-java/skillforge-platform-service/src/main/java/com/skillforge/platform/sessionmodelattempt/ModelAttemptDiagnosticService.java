package com.skillforge.platform.sessionmodelattempt;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.ObservationReadAudit;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.chat.observation.SessionObservationView.TokenTotals;
import com.skillforge.core.chat.observation.SessionObservationView.UsageAttempt;
import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.trajectory.TrajectoryProjectionService;
import com.skillforge.core.chat.trajectory.TrajectoryQuery;
import com.skillforge.platform.sessionmodelattempt.ModelAttemptDetail.RecordedContent;
import com.skillforge.platform.sessionmodelattempt.ModelAttemptDetail.ReasoningHistoryRetention;
import com.skillforge.platform.sessionmodelattempt.ModelAttemptDetail.CapabilityCall;
import com.skillforge.runtime.session.ExecutionModelAttemptInspector;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 平台私有模型正文读取应用边界；只读取有界、已提交且可精确验证的原事实。 */
@Service
public class ModelAttemptDiagnosticService {
    private static final Logger LOG = LoggerFactory.getLogger(ModelAttemptDiagnosticService.class);
    private final ModelAttemptDiagnosticMapper mapper;
    private final ExecutionModelAttemptInspector inspector;
    private final ObservationReadAudit audit;
    private final PlatformObservationAccess reads;
    private final SessionObservationAuthorizer observationAuthorizer;
    private final TrajectoryProjectionService trajectories;

    /** 安全投影的 JSON 命名独立冻结，不序列化内部 FrozenModelRequest 或目标配置。 */
    public ModelAttemptDiagnosticService(ModelAttemptDiagnosticMapper mapper, ExecutionModelAttemptInspector inspector,
            ObservationReadAudit audit, SessionObservationAuthorizer observationAuthorizer,
            TrajectoryProjectionService trajectories, PlatformObservationAccess reads) {
        this.mapper = mapper;
        this.inspector = inspector;
        this.audit = audit;
        this.observationAuthorizer = observationAuthorizer;
        this.trajectories = trajectories;
        this.reads = reads;
    }

    /**
     * 同一快照校验真实Session/typed Run/Action/Attempt，再读取该Attempt精确实际请求与结果。
     * 审计提交成功后才向管理员返回正文；异常、日志和审计均不携带私有正文。
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ModelAttemptDetail detail(ModelAttemptDiagnosticScope scope, long tenant, String session,
            String root, String attempt, String parentSession, String parentRoot) {
        LOG.debug("管理员模型调用详情读取进入");
        if (scope == null) throw new ApiException(HttpStatus.FORBIDDEN, "缺少平台私有诊断授权");
        if (tenant <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "tenant_id 必须是正整数");
        requireText(session, "session_id");
        requireText(attempt, "attempt_id");
        if (root != null) requireText(root, "root_input_id");
        if ((parentSession == null) != (parentRoot == null))
            throw new ApiException(HttpStatus.BAD_REQUEST, "parent_session_id 和 parent_root_input_id 必须同时提供");
        if (parentSession != null) {
            requireText(parentSession, "parent_session_id");
            requireText(parentRoot, "parent_root_input_id");
            requireText(root, "root_input_id");
        }
        try {
            List<Map<String, Object>> found;
            String verifiedRun = null;
            if (parentSession == null) {
                found = reads.read(tenant, () -> mapper.attempt(tenant, session, attempt, root));
            } else {
                var parentScope = observationAuthorizer.platformAdmin();
                if (!scope.actorId().equals(parentScope.actorId()))
                    throw new ApiException(HttpStatus.FORBIDDEN, "模型诊断主体与父轨迹观测主体不一致");
                var parent = new TrajectoryQuery.Resource(tenant, parentSession, parentRoot);
                verifiedRun = trajectories.requireDescendantAttempt(parentScope, parent, session, root, attempt);
                String sourceRun = verifiedRun;
                found = reads.read(tenant, () -> mapper.descendantAttempt(tenant, session, root, attempt, sourceRun));
            }
            if (found.isEmpty()) throw missing();
            if (found.size() != 1) throw unreadable(null);
            var row = found.getFirst();
            if (!attempt.equals(text(row, "attempt_id"))
                    || root != null && !root.equals(text(row, "root_input_id"))
                    || verifiedRun != null && !verifiedRun.equals(text(row, "run_id"))) throw missing();
            var runtime = inspectRuntime(tenant, session, row);
            var usage = usage(row);
            var result = new ModelAttemptDetail(Long.toString(tenant), session, text(row, "root_input_id"),
                    attempt, text(row, "invocation_id"), text(row, "run_id"), text(row, "action_id"),
                    runtime.loopId(), runtime.iteration(), text(row, "provider"), text(row,"model")==null?runtime.modelId():text(row,"model"),
                    text(row, "call_point"), text(row, "status"), runtime.finishReason(), instant(row, "started_at"), instant(row, "ended_at"),
                    Instant.now(), usage, runtime.input(), runtime.reasoning(), runtime.reasoningHistoryRetention(), runtime.output(), runtime.decision(),
                    runtime.capabilityAvailability(), runtime.capabilities());
            var auditFields = new java.util.LinkedHashMap<String, Object>(Map.of(
                            "session_id", session, "root_input_id", result.rootInputId() == null ? "" : result.rootInputId(),
                            "result", "SUCCESS",
                            "input_availability", result.input().availability(),
                            "reasoning_availability", result.reasoning().availability(),
                            "output_availability", result.output().availability(),
                            "decision_availability", result.decision().availability(),
                            "capability_calls_availability", result.capabilityCallsAvailability()));
            if (parentSession != null) {
                auditFields.put("parent_session_id", parentSession);
                auditFields.put("parent_root_input_id", parentRoot);
            }
            audit.record(Long.toString(tenant), scope.actorId(), "platform.session.model-attempt.detail.read",
                    "model_attempt", attempt, "平台管理员读取模型调用诊断正文", auditFields);
            LOG.debug("管理员模型调用详情读取完成 inputAvailability={} outputAvailability={}",
                    result.input().availability(), result.output().availability());
            return result;
        } finally {
            LOG.debug("管理员模型调用详情读取退出");
        }
    }

    /** 原typed Attempt只引用其真实请求和结果；已选定且已采纳的模型才拥有后续能力。 */
    private RuntimeContent inspectRuntime(long tenant,String session,Map<String,Object> row) {
        try {
            if(!Long.toString(tenant).equals(text(row,"tenant_id"))||!session.equals(text(row,"session_id"))||!"MODEL".equals(text(row,"kind")))throw unreadable(null);
            var request=payload(row,"request");var result=optionalPayload(row,"result");
            var inspected=inspector.inspect(request,result, references -> {
                var actuals = reads.read(tenant, () -> mapper.materials(Long.toString(tenant), references.stream().map(ResultRef::value).toList()));
                var indexed = new java.util.HashMap<ResultRef, ExecutionStore.Material>();
                for (var actual : actuals) {
                    var ref = new ResultRef(text(actual, "reference"));
                    var material = new ExecutionStore.Material(ref, new TypedPayload(text(actual, "schema_id"), text(actual, "canonical_json")));
                    if (indexed.put(ref, material) != null) throw unreadable(null);
                }
                return references.stream().map(ref -> Optional.ofNullable(indexed.get(ref))).toList();
            });
            var input=new RecordedContent("FULL","ACTUAL_MODEL_REQUEST","skillforge.platform.model-attempt.input.v1",inspected.input());
            var reasoning=RecordedContent.notRecorded("PROVIDER_REASONING");var output=RecordedContent.notRecorded("ACTUAL_MODEL_OUTCOME");
            var decision=RecordedContent.notRecorded("ACTUAL_MODEL_OUTCOME");String finish=null;
            if(inspected.outcome().orElse(null) instanceof ModelResult.Completed completed) {
                finish=completed.finishReasonCode();decision=new RecordedContent("FULL","ACTUAL_MODEL_OUTCOME",completed.output().schemaId(),completed.output().canonicalJson());
                reasoning=completed.reasoningContent().map(value->new RecordedContent("FULL","PROVIDER_REASONING",null,value)).orElseGet(()->new RecordedContent("NOT_RETURNED","PROVIDER_REASONING",null,null));
                output=completed.assistantTurn().map(turn->new RecordedContent("FULL","PROVIDER_ASSISTANT_TURN",null,turn.content())).orElse(decision);
            }
            var calls=new ArrayList<CapabilityCall>();String availability="NOT_RECORDED";
            if(Boolean.TRUE.equals(row.get("adopted"))&&Objects.equals(text(row,"selected_attempt"),text(row,"attempt_id"))) {
                var actuals=reads.read(tenant, () -> mapper.capabilityFacts(Long.toString(tenant),text(row,"run_id"),text(row,"attempt_id"),number(row,"ordinal")));
                availability=actuals.size()>64?"PARTIAL":"FULL";
                for(var actual:actuals.stream().limit(64).toList()) {
                    if(!text(row,"tenant_id").equals(text(actual,"tenant_id"))||!text(row,"run_id").equals(text(actual,"run_id")))throw unreadable(null);
                    var kind=ExecutionStore.Kind.valueOf(text(actual,"kind"));var capability=inspector.inspectCapability(kind,payload(actual,"request"),optionalPayload(actual,"result"));
                    calls.add(new CapabilityCall(capability.callId(),text(actual,"action_id"),text(actual,"attempt_id"),kind.name(),capability.title(),text(actual,"status"),
                            new RecordedContent(capability.redactedFields().isEmpty()?"FULL":"PARTIAL","ACTUAL_CAPABILITY_REQUEST",capability.input().schemaId(),capability.input().canonicalJson()),
                            capability.output().map(value->new RecordedContent("FULL","ACTUAL_CAPABILITY_OUTCOME",value.schemaId(),value.canonicalJson())).orElseGet(()->RecordedContent.notRecorded("ACTUAL_CAPABILITY_OUTCOME"))));
                }
            }
            // 新Runtime用真实MODEL Action身份及有序计数分组，不重建旧Preparation loop。
            return new RuntimeContent(text(row,"action_id"),Math.toIntExact(number(row,"model_ordinal")),input,reasoning,null,output,decision,finish,availability,calls,inspected.modelId());
        }catch(ApiException failure){throw failure;}catch(RuntimeException failure){throw unreadable(failure);}
    }
    private static TypedPayload payload(Map<String,Object> row,String prefix) {
        if(text(row,prefix+"_ref")==null||text(row,prefix+"_schema")==null||text(row,prefix+"_json")==null)throw unreadable(null);
        return new TypedPayload(text(row,prefix+"_schema"),text(row,prefix+"_json"));
    }
    private static Optional<TypedPayload> optionalPayload(Map<String,Object> row,String prefix) {
        return text(row,prefix+"_ref")==null?Optional.empty():Optional.of(payload(row,prefix));
    }

    /** Token 延续真实 provider report 口径；未知字段显式 null，不依据调用状态补零。 */
    private static UsageAttempt usage(Map<String, Object> row) {
        boolean reported = "PROVIDER_REPORTED".equals(text(row, "usage_source"));
        String prompt = reported ? decimal(row, "prompt_tokens") : null;
        String completion = reported ? decimal(row, "completion_tokens") : null;
        String status = !reported ? "UNKNOWN" : prompt == null || completion == null ? "PARTIAL"
                : "0".equals(prompt) && "0".equals(completion) ? "ZERO" : "REPORTED";
        return new UsageAttempt(text(row, "attempt_id"), text(row, "invocation_id"), text(row, "trace_node_id"),
                text(row, "run_id"), text(row, "provider"), text(row, "model"), text(row, "call_point"), status,
                text(row, "usage_source"), new TokenTotals(prompt, completion,
                        reported ? decimal(row, "cached_input_tokens") : null,
                        reported ? decimal(row, "cache_write_input_tokens") : null,
                        reported ? decimal(row, "reasoning_output_tokens") : null), instant(row, "ingested_at"), true,
                text(row,"session_id"),text(row,"root_input_id"));
    }

    /** 参数有界且不含控制字符，避免扩大查询及让目标标识污染审计。 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl))
            throw new ApiException(HttpStatus.BAD_REQUEST, name + " 必须是有界非空标识");
    }

    /** 仅转换 Mapper 的明确列，不解析任何 Runtime 私有字段。 */
    private static String text(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : row.get(key).toString();
    }

    /** 保留数据库整数的十进制精度。 */
    private static String decimal(Map<String, Object> row, String key) { return text(row, key); }

    /** 明确整数列无损恢复，不把缺失来源数字默认为零。 */
    private static long number(Map<String, Object> row, String key) {
        if (!(row.get(key) instanceof Number value)) throw unreadable(null);
        return value.longValue();
    }

    /** PostgreSQL 时间保持 Instant；不使用读时刻填补原边界缺失。 */
    private static Instant instant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (value instanceof Instant time) return time;
        if (value instanceof java.sql.Timestamp time) return time.toInstant();
        if (value instanceof java.time.OffsetDateTime time) return time.toInstant();
        throw unreadable(null);
    }

    /** 查询不存在和错归属返回相同错误，避免泄露其他会话的 attempt 存在性。 */
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "模型调用不存在或不属于指定会话范围"); }

    /**
     * 保留内部原因链但错误协议不包含冻结数据或 Provider 正文。
     * 不把 Throwable 交给日志框架：异常 message、toString 和 suppressed 都可能包含原始正文。
     */
    private static ApiException unreadable(Throwable cause) {
        var failure = new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "模型调用诊断证据不可读取", cause);
        LOG.error("管理员模型调用诊断读取失败 exceptionLocations={}", exceptionLocations(cause == null ? failure : cause));
        return failure;
    }

    /** 仅记录有界的异常类型和编译栈位置，禁止读取异常 message 或序列化原证据。 */
    private static List<String> exceptionLocations(Throwable failure) {
        var locations = new ArrayList<String>();
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (int depth = 0; failure != null && depth < 4 && seen.add(failure); depth++, failure = failure.getCause()) {
            locations.add(failure.getClass().getName());
            var frames = failure.getStackTrace();
            for (int index = 0; index < Math.min(frames.length, 12); index++) {
                var frame = frames[index];
                locations.add(frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber());
            }
        }
        return List.copyOf(locations);
    }

    /** 不向日志暴露内容的内部已验证投影。 */
    private record RuntimeContent(String loopId, Integer iteration, RecordedContent input,
            RecordedContent reasoning, ReasoningHistoryRetention reasoningHistoryRetention, RecordedContent output, RecordedContent decision, String finishReason,
            String capabilityAvailability, List<CapabilityCall> capabilities,String modelId) {
        @Override public String toString() { return "RuntimeContent[REDACTED]"; }
    }

}
