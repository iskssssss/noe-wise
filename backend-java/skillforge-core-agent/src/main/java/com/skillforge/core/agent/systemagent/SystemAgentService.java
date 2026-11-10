package com.skillforge.core.agent.systemagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台系统智能体发布与受理边界；发布指针、修订号及审计在同一事务内提交。 */
@Service
public class SystemAgentService {
    public static final String DEBUG_TENANT = "900000000000000001";
    public static final String CONTEXT_SUMMARY_PURPOSE = "context-summary";
    private final SystemAgentMapper mapper;
    private final ObjectMapper json;
    private final PlatformModelCatalogService models;
    private final PlatformAgentResources resources;

    public SystemAgentService(SystemAgentMapper mapper, ObjectMapper json,
                              PlatformModelCatalogService models, PlatformAgentResources resources) {
        this.mapper = mapper; this.json = json; this.models = models; this.resources = resources;
    }

    public List<View> list() { return mapper.list().stream().map(s -> read(s, View.class)).toList(); }
    public View detail(String id) { requireId(id); return readRequired(mapper.find(id), View.class); }
    public List<Version> versions(String id) {
        detail(id); return mapper.versions(id).stream().map(s -> read(s, Version.class)).toList();
    }

    @Transactional
    public View create(String key, String name, String description, String mode, String actor) {
        return create(key, name, description, mode, null, null, actor);
    }

    /** 初始提示词与模型在创建草稿时保存；发布仍执行完整校验。 */
    @Transactional
    public View create(String key, String name, String description, String mode,
            String systemPrompt, String modelConfigId, String actor) {
        actor(actor);
        if (key == null || !key.matches("[a-z][a-z0-9-]{2,99}")
                || Set.of("memory-extraction", "task-context", CONTEXT_SUMMARY_PURPOSE).contains(key)
                || mode == null || !Set.of("MODEL_TASK","AGENT_RUN").contains(mode)) {
            throw invalid("system_agent_identity_invalid");
        }
        Config config = new Config(name, description, systemPrompt == null ? "" : systemPrompt,
                modelConfigId, 30, "MODEL_TASK".equals(mode) ? 1 : 10, List.of());
        validate(config, mode, false, false);
        String id = Long.toString(SnowflakeIds.nextId());
        mapper.create(id,key,null,mode,write(config));
        audit(id,1,"create",actor);
        return detail(id);
    }

    @Transactional
    public View update(String id, long expected, Config config, String actor) {
        View current = expected(id,expected); actor(actor);
        if ("archived".equals(current.status())) throw invalid("system_agent_archived");
        validate(config,current.executionMode(),false,false);
        changed(mapper.save(id,expected,write(config)));
        audit(id,expected+1,"update",actor);
        return detail(id);
    }

    @Transactional
    public View transition(String id, long expected, String operation, String actor) {
        View current = expected(id,expected); actor(actor);
        String status; Long version = current.publishedVersion();
        if ("archived".equals(current.status())) throw invalid("system_agent_archived");
        switch(operation) {
            case "publish" -> {
                validate(current.draft(),current.executionMode(),true,false);
                version = version == null ? 1L : version+1;
                status = "published";
                // 先抢占修订号，再插入版本；延迟外键保证提交时引用完整。
                changed(mapper.state(id,expected,status,version));
                mapper.append(id,version,write(current.draft()),actor);
            }
            case "disable" -> {
                if (!"published".equals(current.status())) throw invalid("system_agent_not_published");
                status="disabled"; changed(mapper.state(id,expected,status,version));
            }
            case "enable" -> {
                if (!"disabled".equals(current.status()) || current.published()==null) throw invalid("system_agent_not_disabled");
                validate(current.published(),current.executionMode(),true,false);
                status="published"; changed(mapper.state(id,expected,status,version));
            }
            case "archive" -> {
                if ("published".equals(current.status())) throw invalid("system_agent_disable_before_archive");
                if (current.purpose()!=null) throw invalid("system_agent_purpose_bound");
                status="archived"; changed(mapper.state(id,expected,status,version));
            }
            default -> throw invalid("system_agent_operation_invalid");
        }
        audit(id,expected+1,operation,actor);
        return detail(id);
    }

    @Transactional
    public void delete(String id,long expected,String actor) {
        expected(id,expected); actor(actor);
        if (mapper.delete(id,expected)!=1) throw invalid("system_agent_not_deletable");
        audit(id,expected+1,"delete",actor);
    }

    /** 后台用途只解析平台绑定，不能由机构覆盖 Prompt、模型或挂载。 */
    @Transactional
    public FrozenRun acceptPurpose(String purpose,String tenant,String key,String input) {
        View agent = readRequired(mapper.findPurpose(purpose),View.class);
        return accept(agent,tenant,key,input,false,false,0);
    }

    /** 草稿调试必须指定刚保存的修订号，禁止静默测试其他配置。 */
    @Transactional
    public FrozenRun acceptDebug(String id,String source,long revision,String key,String input,String actor) {
        actor(actor);
        if (!Set.of("DRAFT","PUBLISHED").contains(source)) throw invalid("system_agent_debug_source_invalid");
        View agent = expected(id,revision);
        FrozenRun accepted = accept(agent,DEBUG_TENANT,key,input,true,"DRAFT".equals(source),revision);
        audit(id,revision,"debug",actor);
        return accepted;
    }

    private FrozenRun accept(View agent,String tenant,String key,String input,boolean debug,boolean draft,long revision) {
        requireId(tenant);
        if (key==null || key.isBlank() || key.length()>256 || input==null || input.isBlank() || input.length()>128000) {
            throw invalid("system_agent_input_invalid");
        }
        mapper.bindTenant(tenant);
        String fingerprint = hash(input+"\n"+debug+"\n"+draft+(debug?"\n"+revision:""));
        String existing = mapper.existingRun(tenant,agent.id(),key);
        if (existing!=null) {
            if (CONTEXT_SUMMARY_PURPOSE.equals(agent.purpose())) {
                verifyContextSummaryReplay(existing, input);
                return frozen(existing, agent, tree(existing).path("input_fingerprint").asText());
            }
            return frozen(existing,agent,fingerprint);
        }
        if ("archived".equals(agent.status()) || (!draft && !"published".equals(agent.status()))) {
            throw invalid("system_agent_unavailable");
        }
        Config config = draft ? agent.draft() : agent.published();
        validateExecution(config,agent.executionMode());
        var mounts=resources.resolveForExecution(config.mounts(),debug);
        long modelRevision = models.requireEnabled(config.modelConfigId(),"LLM").revision();
        String id = Long.toString(SnowflakeIds.nextId());
        mapper.insertRun(id,tenant,agent.id(),key,fingerprint,draft?null:agent.publishedVersion(),debug,
                write(config),modelRevision,input,write(mounts));
        return frozen(mapper.existingRun(tenant,agent.id(),key),agent,fingerprint);
    }

    @Transactional
    public List<RunView> runs(String id) {
        detail(id);
        // 平台历史只返回调试记录；机构生产输入与输出不通过平台维护页泄露。
        mapper.bindTenant(DEBUG_TENANT);
        return mapper.runs(DEBUG_TENANT,id).stream().map(this::runView).toList();
    }

    /** 仅查询原调试账本；未找到不证明原请求未受理，也不重新派发。 */
    @Transactional
    public RunView debugRun(String id,String key) {
        detail(id);
        if (key==null || key.isBlank() || key.length()>256) throw invalid("system_agent_input_invalid");
        mapper.bindTenant(DEBUG_TENANT);
        return runView(required(mapper.debugRun(DEBUG_TENANT,id,key)));
    }

    @Transactional
    public RunView run(String id,String tenant) {
        requireId(id); requireId(tenant); mapper.bindTenant(tenant);
        mapper.expireModelRun(id,tenant);
        return runView(required(mapper.run(id,tenant)));
    }

    /** 平台恢复只读取内部调试空间，不扫描机构运行数据。 */
    @Transactional
    public List<FrozenRun> pendingDebugRuns() {
        mapper.bindTenant(DEBUG_TENANT);
        return mapper.pendingDebug(DEBUG_TENANT).stream().map(value -> {
            var row=tree(value);
            return frozen(value,detail(row.path("agent_id").asText()),row.path("input_fingerprint").asText());
        }).toList();
    }

    @Transactional
    public boolean claim(FrozenRun run) {
        mapper.bindTenant(run.tenantId()); return mapper.claim(run.id(),run.tenantId())==1;
    }

    /** 新派发资格与 RUNNING 提交共用短事务，暂停先落库则领取回滚；外调不持有群控制锁。 */
    @Transactional
    public boolean claimGuarded(FrozenRun run, Runnable beforeDispatch) {
        mapper.bindTenant(run.tenantId());
        if (mapper.claim(run.id(),run.tenantId()) != 1) return false;
        beforeDispatch.run();
        return true;
    }

    @Transactional
    public RunView finish(FrozenRun run,String output,String error,Long inputTokens,Long outputTokens,long duration) {
        mapper.bindTenant(run.tenantId());
        // 并发恢复和原请求可能同时完成；终态 CAS 的胜者为唯一权威结果。
        mapper.finish(run.id(),run.tenantId(),error==null?"COMPLETED":"FAILED",output,error,
                inputTokens,outputTokens,duration);
        return runView(required(mapper.run(run.id(),run.tenantId())));
    }

    private void validate(Config c,String mode,boolean executable,boolean debug) {
        if (c==null || c.name()==null || c.name().isBlank() || c.name().codePointCount(0,c.name().length())>128
                || (c.description()!=null && c.description().length()>2000)
                || c.systemPrompt()==null || c.systemPrompt().length()>64000
                || c.timeoutSeconds()<1 || c.timeoutSeconds()>3600 || c.maxBusinessModelCalls()<1 || c.maxBusinessModelCalls()>100) {
            throw invalid("system_agent_config_invalid");
        }
        if ("MODEL_TASK".equals(mode) && (!c.mounts().isEmpty() || c.maxBusinessModelCalls()!=1)) throw invalid("model_task_capabilities_forbidden");
        if (executable && (c.systemPrompt().isBlank() || c.modelConfigId()==null)) throw invalid("system_agent_config_incomplete");
        if (c.modelConfigId()!=null) models.requireEnabled(c.modelConfigId(),"LLM");
        if(!c.mounts().isEmpty()) {
            if(executable) resources.validate(c.mounts(),debug);
            else resources.validateDraft(c.mounts());
        }
    }

    private void validateExecution(Config c,String mode) {
        if(c==null || c.systemPrompt()==null || c.systemPrompt().isBlank() || c.modelConfigId()==null)
            throw invalid("system_agent_config_incomplete");
        if("MODEL_TASK".equals(mode) && (!c.mounts().isEmpty() || c.maxBusinessModelCalls()!=1))
            throw invalid("model_task_capabilities_forbidden");
    }

    private FrozenRun frozen(String value,View agent,String fingerprint) {
        JsonNode n=tree(required(value));
        if (!Objects.equals(n.path("input_fingerprint").asText(),fingerprint)) throw conflict();
        return new FrozenRun(n.path("id").asText(),agent.id(),n.path("tenant_id").asText(),agent.purpose(),
                agent.executionMode(),nullableLong(n,"version"),n.path("debug").asBoolean(),
                read(n.path("config").toString(),Config.class),n.path("model_revision").asLong(),n.path("input").asText(),
                java.util.Arrays.asList(read(n.path("mount_snapshot").toString(),PlatformAgentResources.PublishedMount[].class)),runView(value));
    }

    private void verifyContextSummaryReplay(String existing, String requestedInput) {
        JsonNode previous = tree(tree(existing).path("input").asText());
        JsonNode requested = tree(requestedInput);
        if (!sameContextSummarySource(previous, requested)) throw conflict();
    }

    private static boolean sameContextSummarySource(JsonNode previous, JsonNode requested) {
        JsonNode left = previous.deepCopy();
        JsonNode right = requested.deepCopy();
        if (!left.isObject() || !right.isObject()) return false;
        ((com.fasterxml.jackson.databind.node.ObjectNode) left).remove(List.of(SystemAgentExecution.MAX_OUTPUT_TOKENS_FIELD, SystemAgentExecution.REMAINING_MILLIS_FIELD));
        ((com.fasterxml.jackson.databind.node.ObjectNode) right).remove(List.of(SystemAgentExecution.MAX_OUTPUT_TOKENS_FIELD, SystemAgentExecution.REMAINING_MILLIS_FIELD));
        return left.equals(right);
    }
    private RunView runView(String value) {
        JsonNode n=tree(value);
        return new RunView(n.path("id").asText(),n.path("agent_id").asText(),nullableLong(n,"version"),
                n.path("debug").asBoolean(),n.path("status").asText(),nullableText(n,"output"),nullableText(n,"error_code"),
                n.path("input_tokens").isNumber()?n.path("input_tokens").longValue():null,
                n.path("output_tokens").isNumber()?n.path("output_tokens").longValue():null,
                n.path("duration_ms").asLong(),Instant.parse(n.path("created_at").asText()));
    }
    private View expected(String id,long revision) {
        View view=detail(id); if(revision<1 || view.revision()!=revision) throw conflict(); return view;
    }
    private void audit(String id,long revision,String operation,String actor) {
        if(mapper.audit(id,revision,operation,actor)!=1) throw new IllegalStateException("system_agent_audit_failed");
    }
    private static void actor(String actor) { if(actor==null || actor.isBlank()) throw invalid("system_agent_actor_missing"); }
    private static void changed(int changed) { if(changed!=1) throw conflict(); }
    private static String required(String value) { if(value==null) throw new ApiException(HttpStatus.NOT_FOUND,"system_agent_not_found"); return value; }
    private <T>T readRequired(String value,Class<T> type) { return read(required(value),type); }
    private <T>T read(String value,Class<T> type) { try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("system_agent_record_invalid",e);} }
    private JsonNode tree(String value) { return read(value,JsonNode.class); }
    private String write(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("system_agent_config_invalid",e);} }
    private static Long nullableLong(JsonNode n,String key) {return n.path(key).isNumber()?n.path(key).asLong():null;}
    private static String nullableText(JsonNode n,String key) {return n.path(key).isNull()?null:n.path(key).asText(null);}
    private static String hash(String text) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void requireId(String id) {try {if(id==null || !id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id)<=0) throw new NumberFormatException();}catch(NumberFormatException e){throw invalid("system_agent_id_invalid");}}
    private static ApiException invalid(String message) {return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,message);}
    private static ApiException conflict() {return new ApiException(HttpStatus.CONFLICT,"system_agent_revision_conflict");}
}
