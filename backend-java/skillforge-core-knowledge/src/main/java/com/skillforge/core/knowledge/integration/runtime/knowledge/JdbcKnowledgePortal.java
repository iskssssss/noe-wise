package com.skillforge.core.knowledge.integration.runtime.knowledge;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.KnowledgePortal.*;
import com.skillforge.runtime.codec.spi.KnowledgeDocumentCodec;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import javax.sql.DataSource;

/** 对冻结知识副本执行多源计划、受控链接跟进、证据融合与充分性评估。 */
public final class JdbcKnowledgePortal {
    public static final String CONTENT_SCHEMA = "skillforge.runtime.knowledge.chunk.v1";
    public static final String LINK_CONTENT_SCHEMA = "skillforge.runtime.knowledge.link.v1";
    public static final String DOCUMENT_CONTENT_SCHEMA = KnowledgeDocumentCodec.TEXT_SCHEMA;
    private static final int CURSOR_VERSION = 1;
    private static final int CURSOR_MAX_BYTES = 3_072;
    private static final Pattern URL = Pattern.compile("https?://[^\\s\\\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOMAIN_ARRAY = Pattern.compile("\\\"allowed_domains\\\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private final JdbcKnowledgeBindings bindings;
    private final Predicate<EvidenceBundle> outputValidator;
    private final LinkReader links;
    private final Clock clock;
    private final KnowledgeDocumentCodec documents;

    /** 已登记派发的检索适配器；结果与使用量只由 ExecutionStore 的提交保存。 */
    public static JdbcKnowledgePortal registered(DataSource source, JdbcKnowledgeBindings bindings,
            Predicate<EvidenceBundle> outputValidator, LinkReader links, Clock clock, KnowledgeDocumentCodec documents) {
        Objects.requireNonNull(source);
        return new JdbcKnowledgePortal(bindings, outputValidator, links, clock, documents);
    }

    /** 不建立旧结果账本或第二套调用授权，实际调用权来自本次 Store 派发。 */
    private JdbcKnowledgePortal(JdbcKnowledgeBindings bindings, Predicate<EvidenceBundle> outputValidator,
            LinkReader links, Clock clock, KnowledgeDocumentCodec documents) {
        this.bindings = Objects.requireNonNull(bindings);
        this.outputValidator = Objects.requireNonNull(outputValidator);
        this.links = Objects.requireNonNull(links);
        this.clock = Objects.requireNonNull(clock);
        this.documents = Objects.requireNonNull(documents);
    }

    /**
     * 对已由Store登记并授权的冻结请求执行真实检索计算；本方法不读取旧permit/source或保存结果。
     * currentAuthority必须是当前执行的无I/O取消/失租信号，不能用缓存权限替代派发事务中的当前IAM。
     * 检索预检及文档读取的确定业务拒绝抛RetrievalRejected；SQL技术异常或控制丢失继续未决。
     */
    public EvidenceBundle retrieve(KnowledgeRequest request, FrozenKnowledgeTarget target,
            JdbcKnowledgeBindings.Binding frozen, String requestFingerprint, InvocationControl control,
            BooleanSupplier currentAuthority) {
        Objects.requireNonNull(request); Objects.requireNonNull(target); Objects.requireNonNull(frozen);
        Objects.requireNonNull(control); Objects.requireNonNull(currentAuthority);
        if (!target.equals(frozen.target()) || requestFingerprint == null || requestFingerprint.isBlank())
            throw rejected("knowledge_request_binding_mismatch");
        if (request.mode() == KnowledgeMode.NONE || !target.sourceVersions().containsAll(request.allowedSourceVersions())
                || !target.sourceVersions().stream().map(KnowledgeSourceVersion::sourceId).collect(java.util.stream.Collectors.toSet()).containsAll(request.allowedSourceIds())
                || !target.allowedOperators().containsAll(request.allowedOperators())
                || Duration.ofMillis(request.budget().timeoutMs()).compareTo(target.timeout()) > 0)
            throw rejected("knowledge_request_scope_rejected");
        long timeout = Math.min(request.budget().timeoutMs(), target.timeout().toMillis());
        Instant deadline = clock.instant().plusMillis(timeout);
        if (control.deadlineAt().isBefore(deadline)) deadline = control.deadlineAt();
        control = new InvocationControl(deadline, control.cancellationSignal());
        if (!live(control) || !currentAuthority.getAsBoolean()) throw rejected("knowledge_control_inactive");
        if(request.operation() instanceof KnowledgeOperation.ListDocuments operation)
            return listDocuments(request,target,frozen,operation,requestFingerprint,control,currentAuthority);
        if(request.operation() instanceof KnowledgeOperation.ReadDocument operation)
            return readDocument(request,target,operation,requestFingerprint,control,currentAuthority);
        var search=(KnowledgeOperation.Search)request.operation();
        if (!request.allowedOperators().contains(KnowledgeOperatorType.KEYWORD)
                || !target.allowedOperators().contains(KnowledgeOperatorType.KEYWORD))
            throw rejected("knowledge_operator_unsupported");
        LinkPolicy linkPolicy = linkPolicy(frozen.descriptorJson());
        var budget = request.budget();
        var versions = request.allowedSourceVersions().stream()
                .sorted(Comparator.comparing(KnowledgeSourceVersion::sourceId)
                        .thenComparing(KnowledgeSourceVersion::sourceVersion)).toList();
        if (versions.isEmpty()) throw rejected("knowledge_source_missing");
        if (budget.maxItems() > 10_000 || budget.maxQueries() > 10_000
                || budget.maxCharacters() > 1_048_576) throw rejected("knowledge_budget_capacity_exceeded");
        // 单轮按知识来源计查询；同一知识库的全部请求授权文档版本属于一次 SQL 查询。
        var sourceIds = versions.stream().map(KnowledgeSourceVersion::sourceId).distinct().toList();
        int keywordCount = Math.min(sourceIds.size(), request.mode() == KnowledgeMode.GROUNDING
                ? 1 : budget.maxQueries());
        var steps = new ArrayList<KnowledgeStep>();
        for (int i = 0; i < keywordCount; i++) {
            steps.add(new KnowledgeStep("keyword-" + (i + 1), search.query(),
                    Set.of(sourceIds.get(i)), KnowledgeOperatorType.KEYWORD, List.of(),
                    new KnowledgeBudget(1, budget.maxItems(), budget.maxTokens(),
                            budget.maxCharacters(), timeout)));
        }
        boolean linkEnabled = request.allowedOperators().contains(KnowledgeOperatorType.LINK)
                && target.allowedOperators().contains(KnowledgeOperatorType.LINK)
                && linkPolicy.enabled() && budget.maxQueries() > keywordCount;
        if (linkEnabled) steps.add(new KnowledgeStep("link-1", search.query(),
                request.allowedSourceIds(), KnowledgeOperatorType.LINK,
                steps.stream().map(KnowledgeStep::stepId).toList(),
                new KnowledgeBudget(budget.maxQueries() - keywordCount, budget.maxItems(),
                        budget.maxTokens(), budget.maxCharacters(), timeout)));
        var plan = new KnowledgePlan("knowledge-plan:" + request.knowledgeRequestId(),
                request.knowledgeRequestId(), steps, "sufficient_or_budget_exhausted",
                "source-manifest-link-v2");
        var items = new ArrayList<EvidenceItem>();
        var trace = new ArrayList<KnowledgeTraceStep>();
        int characters = 0; long started = System.nanoTime();
        for (int i = 0; i < keywordCount; i++) {
            requireCurrent(control, currentAuthority);
            var step = steps.get(i); int remaining = budget.maxItems() - items.size();
            if (remaining <= 0 || characters >= budget.maxCharacters()) {
                trace.add(new KnowledgeTraceStep(step.stepId(), "budget_exhausted",
                        List.of(), List.of(), 0, 0)); continue;
            }
            long stepStart = System.nanoTime();
            var sourceVersions = versions.stream().filter(version -> step.sourceIds().contains(version.sourceId()))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            var rows = bindings.keyword(request.tenantId(), target, sourceVersions,
                    search.query(), remaining + 1, request.asOf(), request.freshnessAfter(), control);
            var selected = new ArrayList<String>(); boolean truncated = false;
            for (var row : rows) {
                if (items.size() >= budget.maxItems()) { truncated = true; break; }
                String content = row.content();
                int codePoints = content.codePointCount(0, content.length());
                if (codePoints > KnowledgeCapacity.MAX_SEGMENT_CHARACTERS) {
                    content = content.substring(0, content.offsetByCodePoints(0, KnowledgeCapacity.MAX_SEGMENT_CHARACTERS));
                    truncated = true;
                }
                String encoded = "{\"text\":" + KnowledgeJson.quote(content) + "}";
                int length = encoded.codePointCount(0, encoded.length());
                if (length > budget.maxCharacters() - characters) { truncated = true; continue; }
                String id = "knowledge-item:" + hash(KnowledgeJson.encode(List.of(
                        row.sourceVersion().sourceId(), row.sourceVersion().sourceVersion(), row.chunkId())));
                items.add(new EvidenceItem(id, row.sourceVersion().sourceId(),
                        row.sourceVersion().sourceVersion(), KnowledgeOperatorType.KEYWORD,
                        new TypedPayload(CONTENT_SCHEMA, encoded),
                        List.of("knowledge-document:" + row.documentId(),
                                "knowledge-chunk:" + row.chunkId()), List.of(), 1.0,
                        "published-source"));
                selected.add(id); characters += length;
            }
            trace.add(new KnowledgeTraceStep(step.stepId(), truncated
                    ? "keyword_budget_clipped" : "keyword", selected, selected, 0,
                    elapsed(stepStart)));
            if (elapsed(started) > timeout) throw new IllegalStateException("knowledge_retrieval_deadline");
            requireCurrent(control, currentAuthority);
        }
        if (linkEnabled) {
            var followed = followLinks(steps.getLast(), items, budget, characters,
                    linkPolicy, control, currentAuthority);
            items.addAll(followed.items()); characters += followed.characters();
            trace.add(followed.trace());
        }
        var sufficiency = sufficiency(items);
        // 取回不同版本的字面值不证明语义矛盾；主体、适用条件与时间须由后续解释明确核验。
        var bundle = new EvidenceBundle("knowledge-bundle:" + hash(requestFingerprint),
                request.knowledgeRequestId(), plan, items, List.of(), sufficiency, trace, 1,
                clock.instant(),Optional.empty());
        if (!outputValidator.test(bundle)) throw new IllegalStateException("knowledge_result_schema_rejected");
        requireCurrent(control, currentAuthority);
        return bundle;
    }

    /** 目录按原清单与标题筛选稳定续页，元数据不进入EvidenceItem，也不产生可引用正文。 */
    private EvidenceBundle listDocuments(KnowledgeRequest request,FrozenKnowledgeTarget target,JdbcKnowledgeBindings.Binding frozen,
            KnowledgeOperation.ListDocuments operation,String fingerprint,InvocationControl control,BooleanSupplier authority) {
        documentOperator(request,target,KnowledgeOperatorType.DOCUMENT_LIST);
        String scope=cursorScope(request,frozen,operation.titleFilter());
        var after=operation.cursor().map(value->decodeCursor(value,scope));
        if(after.isPresent()&&(!after.orElseThrow().documentId().matches("[1-9][0-9]*")
                ||!request.allowedSourceVersions().contains(new KnowledgeSourceVersion(after.orElseThrow().sourceId(),after.orElseThrow().sourceVersion()))))
            throw rejected("knowledge_document_cursor_rejected");
        long started=System.nanoTime();
        var rows=documentAccess(()->bindings.listDocuments(request.tenantId(),target,request.allowedSourceVersions(),operation.titleFilter(),
                after,operation.pageSize()+1,request.asOf(),request.freshnessAfter(),control),control,authority);
        requireCurrent(control,authority);
        var selected=new ArrayList<KnowledgeDocumentResult.Document>();
        int count=Math.min(Math.min(operation.pageSize(),request.budget().maxItems()),rows.size());
        for(int i=0;i<count;i++)selected.add(metadata(rows.get(i)));
        while(true) {
            Optional<String> next=selected.size()<rows.size()&&!selected.isEmpty()
                    ?Optional.of(encodeCursor(scope,rows.get(selected.size()-1).position())):Optional.empty();
            var result=new KnowledgeDocumentResult.CatalogPage(selected,next);
            if(metadataCharacters(result)<=request.budget().maxCharacters())
                return documentBundle(request,fingerprint,KnowledgeOperatorType.DOCUMENT_LIST,result,List.of(),started,control,authority);
            if(selected.isEmpty()||selected.size()==1)throw rejected("knowledge_document_budget_exceeded");
            selected.removeLast();
        }
    }

    /** 只取精确版本原文；按实际JSON正文和元数据共享字符预算，码点范围不会被UTF-16拆开。 */
    private EvidenceBundle readDocument(KnowledgeRequest request,FrozenKnowledgeTarget target,KnowledgeOperation.ReadDocument operation,
            String fingerprint,InvocationControl control,BooleanSupplier authority) {
        documentOperator(request,target,KnowledgeOperatorType.DOCUMENT_READ);
        if(!request.allowedSourceIds().contains(operation.sourceId())
                ||!request.allowedSourceVersions().contains(new KnowledgeSourceVersion(operation.sourceId(),operation.sourceVersion())))
            throw rejected("knowledge_document_unavailable");
        long started=System.nanoTime();
        var read=documentAccess(()->bindings.readDocument(request.tenantId(),target,request.allowedSourceVersions(),operation,
                request.asOf(),request.freshnessAfter(),control),control,authority);
        requireCurrent(control,authority);
        var row=read.orElseThrow(()->rejected("knowledge_document_unavailable"));
        var document=metadata(row.document());String full=row.text();int maximum=full.codePointCount(0,full.length());
        if(maximum==0) {
            var result=slice(document,operation.offset(),operation.offset());
            if(metadataCharacters(result)>request.budget().maxCharacters())throw rejected("knowledge_document_budget_exceeded");
            return documentBundle(request,fingerprint,KnowledgeOperatorType.DOCUMENT_READ,result,List.of(),started,control,authority);
        }
        int low=0,high=maximum,accepted=-1;
        while(low<=high) {
            int length=low+(high-low)/2;int end=Math.addExact(operation.offset(),length);
            if(length==0&&end<document.totalCharacters()){low=1;continue;}
            var candidate=slice(document,operation.offset(),end);
            String encoded=documentText(prefix(full,length));
            if(metadataCharacters(candidate)+encoded.codePointCount(0,encoded.length())<=request.budget().maxCharacters()) {
                accepted=length;low=length+1;
            } else high=length-1;
        }
        if(accepted<0)throw rejected("knowledge_document_budget_exceeded");
        int end=Math.addExact(operation.offset(),accepted);var result=slice(document,operation.offset(),end);
        String itemId="knowledge-document-item:"+hash(KnowledgeJson.encode(List.of(operation.sourceId(),operation.documentId(),
                operation.sourceVersion(),Integer.toString(operation.offset()),Integer.toString(end))));
        var item=new EvidenceItem(itemId,operation.sourceId(),operation.sourceVersion(),KnowledgeOperatorType.DOCUMENT_READ,
                new TypedPayload(DOCUMENT_CONTENT_SCHEMA,documentText(prefix(full,accepted))),
                List.of("knowledge-document:"+operation.documentId()),List.of(),1.0,"published-source");
        return documentBundle(request,fingerprint,KnowledgeOperatorType.DOCUMENT_READ,result,List.of(item),started,control,authority);
    }
    private EvidenceBundle documentBundle(KnowledgeRequest request,String fingerprint,KnowledgeOperatorType operator,
            KnowledgeDocumentResult result,List<EvidenceItem> items,long started,InvocationControl control,BooleanSupplier authority) {
        String step=operator==KnowledgeOperatorType.DOCUMENT_LIST?"document-list":"document-read";
        var plan=new KnowledgePlan("knowledge-plan:"+hash(fingerprint),request.knowledgeRequestId(),
                List.of(new KnowledgeStep(step,step,request.allowedSourceIds(),operator,List.of(),request.budget())),"document_access_complete","frozen-document-v1");
        var ids=items.stream().map(EvidenceItem::itemId).toList();
        var bundle=new EvidenceBundle("knowledge-bundle:"+hash(fingerprint),request.knowledgeRequestId(),plan,items,List.of(),
                items.isEmpty()?EvidenceSufficiency.INSUFFICIENT:EvidenceSufficiency.PARTIAL,
                List.of(new KnowledgeTraceStep(step,step,ids,ids,0,elapsed(started))),1,clock.instant(),Optional.of(result));
        if(!outputValidator.test(bundle))throw new IllegalStateException("knowledge_result_schema_rejected");
        requireCurrent(control,authority);return bundle;
    }
    private static void documentOperator(KnowledgeRequest request,FrozenKnowledgeTarget target,KnowledgeOperatorType operator) {
        if(!request.allowedOperators().equals(Set.of(operator))||!target.allowedOperators().contains(operator))
            throw rejected("knowledge_operator_unsupported");
        if(request.allowedSourceVersions().isEmpty())throw rejected("knowledge_source_missing");
        if(!request.allowedSourceIds().equals(Set.of(target.descriptorId()))
                ||target.sourceVersions().stream().anyMatch(value->!value.sourceId().equals(target.descriptorId())))
            throw rejected("knowledge_request_scope_rejected");
        if(request.budget().maxCharacters()>1_048_576||request.budget().maxQueries()>10_000||request.budget().maxItems()>10_000)
            throw rejected("knowledge_budget_capacity_exceeded");
    }
    /** 只有明确只读业务错误可确定失败，取消与技术失败保持原异常与未知边界。 */
    private <T>T documentAccess(Supplier<T> query,InvocationControl control,BooleanSupplier authority) {
        try{return query.get();}
        catch(IllegalArgumentException failure) {
            requireCurrent(control,authority);
            if("knowledge_binding_missing".equals(failure.getMessage()))throw rejected("knowledge_document_unavailable");
            if(Set.of("knowledge_document_unavailable","knowledge_document_range_rejected").contains(failure.getMessage()))
                throw rejected(failure.getMessage());
            throw failure;
        }
    }
    private static KnowledgeDocumentResult.Document metadata(JdbcKnowledgeBindings.DocumentHit row) {
        return new KnowledgeDocumentResult.Document(row.sourceVersion().sourceId(),row.documentId(),row.sourceVersion().sourceVersion(),row.title(),row.totalCharacters());
    }
    private static KnowledgeDocumentResult.DocumentSlice slice(KnowledgeDocumentResult.Document document,int start,int end) {
        return new KnowledgeDocumentResult.DocumentSlice(document,start,end,end==document.totalCharacters()?OptionalInt.empty():OptionalInt.of(end));
    }
    private int metadataCharacters(KnowledgeDocumentResult result) {
        String encoded=documents.encode(result).canonicalJson();return encoded.codePointCount(0,encoded.length());
    }
    private static String prefix(String text,int length){return text.substring(0,text.offsetByCodePoints(0,length));}
    private String documentText(String value){return documents.encodeText(value).canonicalJson();}
    /** 游标绑定原目录、收窄manifest、标题与时态条件；位置不是授权票据。 */
    private static String cursorScope(KnowledgeRequest request,JdbcKnowledgeBindings.Binding frozen,String filter) {
        var fields=new ArrayList<>(List.of(frozen.snapshotId(),filter,request.asOf().toString(),request.freshnessAfter().map(Instant::toString).orElse("")));
        request.allowedSourceVersions().stream().sorted(Comparator.comparing(KnowledgeSourceVersion::sourceId).thenComparing(KnowledgeSourceVersion::sourceVersion))
                .forEach(value->{fields.add(value.sourceId());fields.add(value.sourceVersion());});
        return hash(KnowledgeJson.encode(fields));
    }
    private static String encodeCursor(String scope,JdbcKnowledgeBindings.DocumentPosition position) {
        try {
            var bytes=new ByteArrayOutputStream();var output=new DataOutputStream(bytes);output.writeInt(CURSOR_VERSION);
            for(String value:List.of(scope,position.sourceId(),position.documentId(),position.sourceVersion()))output.writeUTF(value);
            output.flush();if(bytes.size()>CURSOR_MAX_BYTES)throw rejected("knowledge_document_cursor_rejected");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        }catch(IOException invalid){throw rejected("knowledge_document_cursor_rejected");}
    }
    private static JdbcKnowledgeBindings.DocumentPosition decodeCursor(String cursor,String scope) {
        try {
            var bytes=Base64.getUrlDecoder().decode(cursor);if(bytes.length>CURSOR_MAX_BYTES)throw rejected("knowledge_document_cursor_rejected");
            var input=new DataInputStream(new ByteArrayInputStream(bytes));if(input.readInt()!=CURSOR_VERSION||!scope.equals(input.readUTF()))throw rejected("knowledge_document_cursor_rejected");
            var position=new JdbcKnowledgeBindings.DocumentPosition(input.readUTF(),input.readUTF(),input.readUTF());
            if(input.read()!=-1||!encodeCursor(scope,position).equals(cursor)||position.sourceId().isBlank()||position.documentId().isBlank()||position.sourceVersion().isBlank())throw rejected("knowledge_document_cursor_rejected");
            return position;
        }catch(IOException|IllegalArgumentException invalid){throw rejected("knowledge_document_cursor_rejected");}
    }

    /** 真实query/link之后失去控制只能保留未决，不能编码为尚未执行的确定拒绝。 */
    private void requireCurrent(InvocationControl control, BooleanSupplier currentAuthority) {
        if (!live(control) || !currentAuthority.getAsBoolean())
            throw new IllegalStateException("knowledge_retrieval_control_changed");
    }

    /** 确定输入拒绝或只读文档业务拒绝，reasonCode闭集不能携带查询、SQL或外部异常正文。 */
    private static RetrievalRejected rejected(String reason) { return new RetrievalRejected(reason); }

    /** 确定拒绝可编码FAILED；文档不存在、越界或预算不足可在只读SQL后确认，不代表SQL尚未执行。 */
    public static final class RetrievalRejected extends IllegalArgumentException {
        private final String reasonCode;
        private RetrievalRejected(String reasonCode) {
            super(reasonCode);
            if (!Set.of("knowledge_request_binding_mismatch", "knowledge_request_scope_rejected", "knowledge_operator_unsupported",
                    "knowledge_source_missing", "knowledge_budget_capacity_exceeded", "knowledge_control_inactive",
                    "knowledge_document_unavailable","knowledge_document_range_rejected","knowledge_document_cursor_rejected","knowledge_document_budget_exceeded").contains(reasonCode))
                throw new IllegalArgumentException("knowledge_retrieval_reason_invalid");
            this.reasonCode = reasonCode;
        }
        /** 固定本地拒绝码，不代表Provider或数据库技术失败已确定。 */
        public String reasonCode() { return reasonCode; }
    }





    static String diagnosticReason(Throwable failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLTimeoutException) return "sql_timeout";
            if (cause instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if ("57014".equals(state)) return "sql_statement_cancelled";
                if ("40P01".equals(state)) return "sql_deadlock";
                if ("55P03".equals(state)) return "sql_lock_unavailable";
                if (state != null && state.startsWith("08")) return "sql_connection_failure";
                return "sql_failure";
            }
            if (cause instanceof java.util.concurrent.TimeoutException) return "operation_timeout";
            if (cause instanceof InterruptedException) return "operation_interrupted";
        }
        return "operation_failure";
    }

    private LinkResult followLinks(KnowledgeStep step, List<EvidenceItem> parents,
            KnowledgeBudget budget, int used, LinkPolicy policy, InvocationControl control,
            BooleanSupplier authorized) {
        var discovered = new LinkedHashMap<String, EvidenceItem>();
        for (var parent : parents) {
            var matcher = URL.matcher(parent.content().canonicalJson());
            while (matcher.find()) normalize(matcher.group()).filter(policy::allows)
                    .ifPresent(value -> discovered.putIfAbsent(value, parent));
        }
        var followed = new ArrayList<EvidenceItem>(); var candidates = new ArrayList<String>();
        var selected = new ArrayList<String>(); int characters = 0; int queries = 0;
        long started = System.nanoTime(); String phase = "link";
        for (var entry : discovered.entrySet()) {
            if (++queries > step.budget().maxQueries() || parents.size() + followed.size() >= budget.maxItems()
                    || used + characters >= budget.maxCharacters()) { phase = "link_budget_exhausted"; break; }
            if (!live(control) || !authorized.getAsBoolean()) throw new IllegalStateException();
            String id = "knowledge-link:" + hash(entry.getKey());
            try {
                var response = links.fetch(entry.getKey(), Math.min(KnowledgeCapacity.MAX_SEGMENT_CHARACTERS, Math.min(policy.maxCharacters(),
                        budget.maxCharacters() - used - characters)),
                        Duration.between(clock.instant(), control.deadlineAt()));
                if (!response.successful()) {
                    phase = response.redirected() ? "link_redirect_rejected" : "link_http_rejected";
                    continue;
                }
                if (response.truncated()) phase = "link_budget_clipped";
                String encoded = "{\"url\":" + KnowledgeJson.quote(response.url())
                        + ",\"contentType\":" + KnowledgeJson.quote(response.contentType())
                        + ",\"text\":" + KnowledgeJson.quote(response.body()) + "}";
                int length = encoded.codePointCount(0, encoded.length());
                if (length > budget.maxCharacters() - used - characters) {
                    phase = "link_budget_clipped"; continue;
                }
                var parent = entry.getValue();
                followed.add(new EvidenceItem(id, parent.sourceId(), parent.sourceVersion(),
                        KnowledgeOperatorType.LINK, new TypedPayload(LINK_CONTENT_SCHEMA, encoded),
                        List.of(parent.itemId(), "link:" + response.url()), List.of(parent.itemId()),
                        parent.relevance(), parent.trustLevel()));
                candidates.add(id); selected.add(id); characters += length;
            } catch (RuntimeException failure) { phase = "link_fetch_failed"; }
        }
        return new LinkResult(List.copyOf(followed), characters,
                new KnowledgeTraceStep(step.stepId(), phase, candidates, selected, 0, elapsed(started)));
    }

    /** 字面命中及链接正文只证明已取证，不能据此判定已充分回答研究目标。 */
    private static EvidenceSufficiency sufficiency(List<EvidenceItem> items) {
        return items.isEmpty() ? EvidenceSufficiency.INSUFFICIENT : EvidenceSufficiency.PARTIAL;
    }

    private static String text(EvidenceItem item) {
        return jsonStringField(item.content().canonicalJson(), "text")
                .or(() -> jsonStringField(item.content().canonicalJson(), "content"))
                .orElse("");
    }

    private static LinkPolicy linkPolicy(String descriptor) {
        if (descriptor == null || !booleanField(descriptor, "enabled")
                || !booleanField(descriptor, "robots_allowed")
                || integerField(descriptor, "max_depth", 0) != 1) return LinkPolicy.disabled();
        Set<String> domains = new LinkedHashSet<>();
        Matcher array = DOMAIN_ARRAY.matcher(descriptor);
        if (array.find()) {
            Matcher values = QUOTED.matcher(array.group(1));
            while (values.find()) {
                String domain = unescape(values.group(1)).strip().toLowerCase(Locale.ROOT);
                if (!domain.isEmpty() && domain.matches("[a-z0-9.-]+")) domains.add(domain);
            }
        }
        int chars = Math.min(64 * 1024, Math.max(1,
                integerField(descriptor, "max_characters", 16 * 1024)));
        return domains.isEmpty() ? LinkPolicy.disabled()
                : new LinkPolicy(true, Set.copyOf(domains), chars);
    }

    private static Optional<String> jsonStringField(String json, String field) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
        Matcher matcher = pattern.matcher(json == null ? "" : json);
        return matcher.find() ? Optional.of(unescape(matcher.group(1))) : Optional.empty();
    }

    private static boolean booleanField(String json, String field) {
        return Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*true")
                .matcher(json).find();
    }

    private static int integerField(String json, String field, int fallback) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*([0-9]+)").matcher(json);
        if (!matcher.find()) return fallback;
        try { return Integer.parseInt(matcher.group(1)); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) { result.append(current); continue; }
            char next = value.charAt(++index);
            switch (next) {
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case '\\', '"', '/' -> result.append(next);
                default -> result.append(next);
            }
        }
        return result.toString();
    }

    private static Optional<String> normalize(String raw) {
        try {
            URI value = URI.create(raw);
            if (!("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()))
                    || value.getHost() == null || value.getUserInfo() != null) return Optional.empty();
            return Optional.of(new URI(value.getScheme().toLowerCase(Locale.ROOT), null,
                    value.getHost().toLowerCase(Locale.ROOT), value.getPort(), value.getPath(),
                    value.getQuery(), null).toASCIIString());
        } catch (Exception invalid) { return Optional.empty(); }
    }

    private boolean live(InvocationControl control) {
        return !control.cancellationSignal().isCancellationRequested()
                && clock.instant().isBefore(control.deadlineAt());
    }
    private static long elapsed(long start) { return Math.max(0, (System.nanoTime() - start) / 1_000_000); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }



    @FunctionalInterface public interface LinkReader {
        LinkResponse fetch(String url, int maxCharacters, Duration timeout);
        static LinkReader unavailable() { return (url, maxCharacters, timeout) -> {
            throw new IllegalStateException("knowledge_link_reader_unavailable"); }; }
    }
    public record LinkResponse(String url, int statusCode, String contentType,
            String body, boolean redirected, boolean truncated) {
        public LinkResponse {
            Objects.requireNonNull(url); contentType = contentType == null ? "" : contentType;
            body = body == null ? "" : body;
            if (statusCode < 100 || statusCode > 599) throw new IllegalArgumentException("statusCode");
        }
        public boolean successful() { return statusCode >= 200 && statusCode < 300 && !redirected; }
    }
    private record LinkPolicy(boolean enabled, Set<String> domains, int maxCharacters) {
        static LinkPolicy disabled() { return new LinkPolicy(false, Set.of(), 1); }
        boolean allows(String url) {
            String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
            return domains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        }
    }
    private record LinkResult(List<EvidenceItem> items, int characters, KnowledgeTraceStep trace) { }
}
