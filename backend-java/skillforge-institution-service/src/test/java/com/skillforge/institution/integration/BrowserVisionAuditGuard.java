package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.institution.media.SessionMediaService;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit.Provider;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.media.*;
import com.skillforge.runtime.api.port.MediaPort;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 本批真实视觉验收的发送前上限与安全元数据；生产audit、预算、媒体校验仍由正式组件执行。 */
final class BrowserVisionAuditGuard {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String,Attempt> attempts=new LinkedHashMap<>();
    private final Set<String> runs=new LinkedHashSet<>();
    private final List<ImagePreparation> preparations=new ArrayList<>();
    private final List<ImageRead> reads=new ArrayList<>();
    private final Map<String,ToolDecision> toolDecisions=new LinkedHashMap<>();
    private final Map<String,ToolSchema> toolSchemas=new LinkedHashMap<>();
    private String tenant;
    private String agent;
    private Path directory;
    private String stopReason;
    private Instant fixtureDeadline;

    /** 委托实际宿主审计，测试门禁不伪造准入或用量。 */
    BrowserVisionAuditGuard() { }

    /** 每次真实Registered调用独立捕获生产DirectAudit；共享观测状态不复用其他调用的审计权限。 */
    ModelInvocationAudit wrap(ModelInvocationAudit delegate) {
        Objects.requireNonNull(delegate);
        return new ModelInvocationAudit() {
            public boolean start(ModelCall call, Provider provider) { return BrowserVisionAuditGuard.this.start(call, provider, delegate); }
            public void usageReported(ModelCall call, Provider provider, long revision, ModelUsage usage) { BrowserVisionAuditGuard.this.usageReported(call, provider, revision, usage, delegate); }
            public void succeeded(ModelCall call, Provider provider, ModelUsage usage) { BrowserVisionAuditGuard.this.succeeded(call, provider, usage, delegate); }
            public void failed(ModelCall call, Provider provider, String reason) { BrowserVisionAuditGuard.this.failed(call, provider, reason, delegate); }
            public void unresolved(ModelCall call, Provider provider, String reason) { BrowserVisionAuditGuard.this.unresolved(call, provider, reason, delegate); }
        };
    }

    /** 仅在新隔离机构资源就绪后开放发送，最多20分钟总夹具时间。 */
    synchronized void configure(String tenant,String agent,Path directory,Instant deadline){
        if(this.tenant!=null)throw new IllegalStateException("vision_harness_already_configured");
        this.tenant=tenant;this.agent=agent;this.directory=directory;this.fixtureDeadline=deadline;persist();
    }

    /** 稳定attempt去重、两Run和每Run12次上限在返回网络发送许可前生效。 */
    private synchronized boolean start(ModelCall call,Provider provider,ModelInvocationAudit delegate){
        var identity=call.context().callContext().runIdentity();
        String attempt=call.context().dispatch().attemptId();String run=identity.runRef().runId();
        if(attempts.containsKey(attempt))return false;
        if(stopReason!=null||tenant==null||!tenant.equals(identity.runRef().tenantId().value())||!agent.equals(identity.agentId().value())
                ||!Instant.now().isBefore(fixtureDeadline))throw new IllegalStateException("vision_harness_send_closed");
        if(!runs.contains(run)&&runs.size()>=2){stop("UI_SAMPLE_LIMIT");throw new IllegalStateException("vision_harness_sample_limit");}
        if(attempts.values().stream().filter(value->run.equals(value.runId())).count()>=12){stop("RUN_MODEL_SEND_LIMIT");throw new IllegalStateException("vision_harness_model_send_limit");}
        for(var tool:call.tools()) {
            try {
                byte[] canonical=tool.inputSchema().canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical));
                int choices=json.readTree(canonical).path("properties").path("command").path("oneOf").size();
                toolSchemas.putIfAbsent(tool.wireName()+":"+digest,new ToolSchema(tool.wireName(),digest,tool.strict(),choices));
            }catch(java.io.IOException|java.security.NoSuchAlgorithmException invalid){throw new IllegalStateException("vision_harness_tool_schema_invalid",invalid);}
        }
        // 历史只抽取身份和参数形状；操作名限正式契约，不保存地址、脚本或模型正文。
        for(var message:call.messages())for(var tool:message.toolCalls()) {
            try {
                var args=json.readTree(tool.arguments().canonicalJson());var fields=new ArrayList<String>();args.fieldNames().forEachRemaining(fields::add);
                var browser=args.path("browser");String selector=browser.isTextual()?browser.asText():null;
                if(selector!=null&&!selector.matches("[A-Za-z0-9_.:-]{1,128}"))selector="[non-identifier]";
                toolDecisions.putIfAbsent(run+":"+tool.callId(),new ToolDecision(run,tool.callId(),tool.wireName(),List.copyOf(fields),selector,commandShape(args)));
            }catch(java.io.IOException invalid){throw new IllegalStateException("vision_harness_tool_arguments_invalid",invalid);}
        }
        var images=new ArrayList<ImageInput>();
        for(var message:call.messages())for(var part:message.contents())if(part instanceof ModelContent.Image image)
            images.add(new ImageInput(message.role().name(),image.media(),image.detail().name()));
        var record=new Attempt(attempt,run,identity.owner().sessionRef().sessionId(),provider.providerId(),provider.serviceId(),provider.modelId(),
                Instant.now(),call.context().callContext().control().deadlineAt(),"RESERVED",List.copyOf(images),null,null);
        attempts.put(attempt,record);runs.add(run);persist();
        try{
            boolean granted=delegate.start(call,provider);
            replace(attempt,granted?"SEND_AUTHORIZED":"NOT_SENT",null,null);return granted;
        }catch(RuntimeException failure){stopReason="AUDIT_START_FAILED";replace(attempt,"NOT_SENT",null,"audit_start_failed");throw failure;}
    }

    /** 真实报告水位不补号，缺失token保持null。 */
    private synchronized void usageReported(ModelCall call,Provider provider,long revision,ModelUsage usage,ModelInvocationAudit delegate){
        try{delegate.usageReported(call,provider,revision,usage);replace(id(call),null,new Usage(revision,value(usage.inputTokens()),value(usage.outputTokens()),value(usage.cachedInputTokens()),value(usage.cacheWriteInputTokens()),value(usage.reasoningOutputTokens())),null);}
        catch(RuntimeException failure){stop("USAGE_RECORDING_FAILED");throw failure;}
    }
    /** 只有正式Provider成功后记录成功；保留缺失用量的未知值。 */
    private synchronized void succeeded(ModelCall call,Provider provider,ModelUsage usage,ModelInvocationAudit delegate){
        try{delegate.succeeded(call,provider,usage);var previous=attempts.get(id(call));long revision=previous!=null&&previous.usage()!=null?previous.usage().revision():0;
            replace(id(call),"SUCCEEDED",new Usage(revision,value(usage.inputTokens()),value(usage.outputTokens()),value(usage.cachedInputTokens()),value(usage.cacheWriteInputTokens()),value(usage.reasoningOutputTokens())),null);}
        catch(RuntimeException failure){stopReason="USAGE_RECORDING_FAILED";replace(id(call),"UNKNOWN",null,"usage_recording_failed");throw failure;}
    }
    /** 确定失败停止后继新发送，原生产失败事实保持不变。 */
    private synchronized void failed(ModelCall call,Provider provider,String reason,ModelInvocationAudit delegate){
        stopReason="PROVIDER_FAILED";try{delegate.failed(call,provider,reason);}finally{replace(id(call),"FAILED",null,reason);}
    }
    /** UNKNOWN立即全局闭锁，禁止第二样本或下一模型请求冲淡原未知。 */
    private synchronized void unresolved(ModelCall call,Provider provider,String reason,ModelInvocationAudit delegate){
        stopReason="PROVIDER_UNKNOWN";try{delegate.unresolved(call,provider,reason);}finally{replace(id(call),"UNKNOWN",null,reason);}
    }

    /** 媒体实际准备和编码读取完全委托正式服务，仅保存受管引用与实际字节长度。 */
    MediaPort media(SessionMediaService original){return new MediaPort(){
        @Override public MediaRef prepare(SessionRef owner,MediaRef ref,ModelImagePolicy policy){
            var prepared=original.prepare(owner,ref,policy);synchronized(BrowserVisionAuditGuard.this){preparations.add(new ImagePreparation(Instant.now(),ref,prepared,policy.version()));persist();}return prepared;
        }
        @Override public byte[] read(SessionRef owner,MediaRef ref){
            byte[] bytes=original.read(owner,ref);synchronized(BrowserVisionAuditGuard.this){reads.add(new ImageRead(Instant.now(),ref,bytes.length));persist();}return bytes;
        }
    };}

    /** 安全快照供观察线程与最终验收使用，实际内容从不进入此结构。 */
    synchronized Snapshot snapshot(){return new Snapshot(stopReason,fixtureDeadline,List.copyOf(attempts.values()),List.copyOf(preparations),List.copyOf(reads),List.copyOf(toolDecisions.values()),List.copyOf(toolSchemas.values()));}
    /** UI结束、失败或总夹具到期时禁止后继新发送，不重新签发历史attempt。 */
    synchronized void stop(String reason){if(stopReason==null)stopReason=reason;persist();}
    /** 替换单attempt元数据，使用null表示沿用原值。 */
    private void replace(String id,String state,Usage usage,String reason){
        var old=attempts.get(id);if(old!=null)attempts.put(id,new Attempt(old.attemptId(),old.runId(),old.sessionId(),old.providerId(),old.serviceId(),old.modelId(),old.authorizedAt(),old.callDeadlineAt(),state==null?old.state():state,old.images(),usage==null?old.usage():usage,reason==null?old.reason():reason));persist();
    }
    /** 每次发送许可返回前落安全账本，文件失败即闭锁后继。 */
    private void persist(){
        if(directory==null)return;
        try{Files.createDirectories(directory);var target=directory.resolve("model-audit.json");var temporary=directory.resolve("model-audit.json.tmp");
            Files.writeString(temporary,json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot()));Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(Exception failure){stopReason="AUDIT_JOURNAL_FAILED";throw new IllegalStateException("vision_harness_audit_journal_failed",failure);}
    }
    /** 原Runtime attempt标识，不从文本或模型参数构造。 */
    private static String id(ModelCall call){return call.context().dispatch().attemptId();}
    /** 缺报保持未知，不将空Optional填零。 */
    private static Long value(OptionalLong value){return value.isPresent()?value.getAsLong():null;}

    /** 仅记录足以区分字符串、缺字段、层级错误的安全结构，不复制业务参数。 */
    private static CommandShape commandShape(com.fasterxml.jackson.databind.JsonNode args){
        if(!args.has("command"))return null;
        var command=args.path("command");var operation=command.path("operation");
        String name=operation.isTextual()&&com.skillforge.execution.browser.BrowserCommandContract.operations().contains(operation.asText())
                ?operation.asText():null;
        var known=List.of("operation","parameters").stream().filter(command::has).toList();
        boolean canonicalTask=false;
        try{String task=args.path("task").asText();canonicalTask=UUID.fromString(task).toString().equals(task);}
        catch(IllegalArgumentException ignored){/* 只保存是否合法，不保存模型构造的身份。 */}
        return new CommandShape(command.getNodeType().name(),known,command.isObject()?command.size()-known.size():0,
                name,command.path("parameters").getNodeType().name(),canonicalTask);
    }

    /** @param role 图片所在模型角色 @param media 冻结受管图片元数据 @param detail 显式图片细节级别 */
    record ImageInput(String role,MediaRef media,String detail){}
    /** @param revision Provider报告水位 @param inputTokens 实报输入或未知 @param outputTokens 实报输出或未知 @param cachedInputTokens 实报缓存命中或未知 @param cacheWriteInputTokens 实报缓存写入或未知 @param reasoningOutputTokens 实报推理输出或未知 */
    record Usage(long revision,Long inputTokens,Long outputTokens,Long cachedInputTokens,Long cacheWriteInputTokens,Long reasoningOutputTokens){}
    /** @param attemptId 原尝试 @param runId 原Run @param sessionId 原Session @param providerId 当前Provider @param serviceId 当前服务 @param modelId 冻结模型 @param authorizedAt 授权观察时间 @param callDeadlineAt 生产调用截止 @param state 发送许可或真实结果 @param images 本次规范模型图片输入 @param usage 真实用量快照 @param reason 受控失败码 */
    record Attempt(String attemptId,String runId,String sessionId,String providerId,String serviceId,String modelId,Instant authorizedAt,Instant callDeadlineAt,String state,List<ImageInput> images,Usage usage,String reason){}
    /** @param at 准备时间 @param source 原受管图片 @param variant 生产prepare返回的实际变体 @param policyVersion 冻结策略版本 */
    record ImagePreparation(Instant at,MediaRef source,MediaRef variant,String policyVersion){}
    /** @param at 实际读取时间 @param media 原受管图片身份 @param actualBytes 实际返回字节数，不保存二进制 */
    record ImageRead(Instant at,MediaRef media,long actualBytes){}
    /** @param stopReason 闭锁原因，null表示仍开放 @param fixtureDeadline 总夹具截止 @param attempts 原调用安全账本 @param preparations 原图与变体准备 @param reads 实际受管字节读取元数据 */
    record Snapshot(String stopReason,Instant fixtureDeadline,List<Attempt> attempts,List<ImagePreparation> preparations,List<ImageRead> reads,List<ToolDecision> toolDecisions,List<ToolSchema> toolSchemas){}
    record ToolSchema(String wireName,String schemaSha256,boolean strict,int commandChoiceCount){}
    record CommandShape(String kind,List<String> knownFields,int unexpectedFieldCount,String operation,String parametersKind,boolean canonicalTask){}
    record ToolDecision(String runId,String callId,String wireName,List<String> argumentFields,String browserSelector,CommandShape command){}
}
