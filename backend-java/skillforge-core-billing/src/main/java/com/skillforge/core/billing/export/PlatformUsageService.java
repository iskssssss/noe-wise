package com.skillforge.core.billing.export;

import com.skillforge.core.foundation.institution.usage.HeadquartersUsageAccess;
import com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.CryptoService;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.skillforge.core.billing.export.PlatformUsageContracts.*;

/** 平台、机构人员和固定接入分别鉴权，消费同一稳定事件；游标永不扩大当前授权。 */
@Service
public class PlatformUsageService {
    private final PlatformUsageMapper mapper;
    private final PlatformUsagePublisher publisher;
    private final PlatformManagementService management;
    private final org.springframework.beans.factory.ObjectProvider<OpenPlatformAccessService> openPlatform;
    private final FoundationAccess foundation;
    private final CryptoService crypto;
    private final ObjectMapper json;
    private final org.springframework.beans.factory.ObjectProvider<HeadquartersUsageAccess> headquarters;
    /** 所有查询都经正式当前授权入口；cursor 使用现有认证加密，不新建签名密钥配置。 */
    public PlatformUsageService(PlatformUsageMapper mapper,PlatformUsagePublisher publisher,
            PlatformManagementService management,org.springframework.beans.factory.ObjectProvider<OpenPlatformAccessService> openPlatform,
            FoundationAccess foundation,CryptoService crypto,ObjectMapper json,
            org.springframework.beans.factory.ObjectProvider<HeadquartersUsageAccess> headquarters) {
        this.mapper=mapper;this.publisher=publisher;this.management=management;this.openPlatform=openPlatform;
        this.foundation=foundation;this.crypto=crypto;this.json=json;this.headquarters=headquarters;
    }

    /** 平台增量是事件读取动作，不能由汇总或明细权限代替。 */
    @Transactional
    public Page eventsPlatform(PlatformUsageQuery query) { return page(platform("platform.usage.events.read"),checked(query)); }
    /** 平台明细只扫描当前有权目标，不能通过筛选参数探测其他机构。 */
    @Transactional
    public Page detailsPlatform(PlatformUsageQuery query) { return page(platform("platform.usage.details.read"),checked(query)); }
    /** 有效供应商成本汇总，不执行客户钱包扣费。 */
    @Transactional
    public Summary summaryPlatform(PlatformUsageQuery query) { return summary(platform("platform.usage.summary.read"),checked(query)); }
    /** 机构服务身份只消费当前固定 Tenant/integration。 */
    @Transactional
    public Page eventsIntegration(PlatformUsageQuery query) { return page(integration(),checked(query)); }
    /** 同一授权域内的事实明细，保留未知和追加修订。 */
    @Transactional
    public Page detailsIntegration(PlatformUsageQuery query) { return page(integration(),checked(query)); }
    /** 固定接入的有效统计。 */
    @Transactional
    public Summary summaryIntegration(PlatformUsageQuery query) { return summary(integration(),checked(query)); }
    /** 机构人员 JWT 本地域，与机器接入及总部汇总路径分开。 */
    @Transactional
    public Page eventsTenant(PlatformUsageQuery query) { return page(tenant(),checked(query)); }
    /** 机构人员只读可信当前 Tenant，不接受正文或 query 改身份。 */
    @Transactional
    public Page detailsTenant(PlatformUsageQuery query) { return page(tenant(),checked(query)); }
    /** 本机构全部接入及原生来源的有效汇总。 */
    @Transactional
    public Summary summaryTenant(PlatformUsageQuery query) { return summary(tenant(),checked(query)); }

    /** 总部汇总仅使用本次当前汇总动作与明确直属目标集合，不授明细或事件。 */
    @Transactional
    public Summary summaryHeadquarters(PlatformUsageQuery query) { return summary(headquarters(HeadquartersUsageActions.SUMMARY),checked(query)); }
    /** 总部明细需要独立许可；关系或授权修订变化后原cursor明确冲突。 */
    @Transactional
    public Page detailsHeadquarters(PlatformUsageQuery query) { return page(headquarters(HeadquartersUsageActions.DETAILS),checked(query)); }
    /** 同一原子事件供总部读取，身份和成本不因读取视图再生成。 */
    @Transactional
    public Page eventsHeadquarters(PlatformUsageQuery query) { return page(headquarters(HeadquartersUsageActions.EVENTS),checked(query)); }

    /** 管理服务同时裁定人员/机器当前动作和目标范围，空集合表示无目标。 */
    private Scope platform(String action) {
        var current=management.authorizedTenantScope(action);
        return new Scope(current.actorRef(),String.valueOf(current.authorizationVersion()),current.tenantIds(),null,null);
    }
    /** SP 的身份只从已认证/current 校验结果取得。 */
    private Scope integration() {
        var principal=openPlatform.getObject().require("usage.read").principal();
        return new Scope("service_principal:"+principal.principalId(),String.valueOf(principal.credentialVersion()),
                Set.of(Long.parseLong(principal.tenantId())),principal.integrationId(),null);
    }
    /** 每次读取当前 Foundation 建制，未过期 JWT 不保留旧角色权限。 */
    private Scope tenant() {
        var subject=foundation.currentSubject();
        if(subject.platform())throw forbidden("平台身份不能进入机构用量域");
        var current=foundation.snapshot(subject);
        if(!current.permissions().contains("foundation.usage.read"))throw forbidden("没有当前机构用量读取动作");
        return new Scope("tenant_member:"+subject.tenantId()+":"+subject.memberId(),
                String.valueOf(current.authorizationVersion()),Set.of(Long.parseLong(subject.tenantId())),null,null);
    }
    private Scope headquarters(String action) {
        var current=headquarters.getObject().authorize(action);
        return new Scope(current.actorRef(),current.authorizationVersion(),current.tenantIds(),null,current);
    }
    /** 发布在只读窗口之外，窗口仅允许所属授权中的固定SQL读取。 */
    private <T>T read(Scope scope,java.util.function.Supplier<T> work) {
        return scope.headquarters()==null?work.get():headquarters.getObject().read(scope.headquarters(),work);
    }
    /** 校验筛选形状；实际范围在查询前用当前 Scope 判定。 */
    private PlatformUsageQuery checked(PlatformUsageQuery query) {
        var q=query==null?PlatformUsageQuery.empty():query;
        if(q.from()!=null&&q.to()!=null&&!q.from().isBefore(q.to()))throw bad("from 必须早于 to");
        for(Long id:new Long[]{q.tenantId(),q.agentId(),q.endUserId(),q.deploymentId(),q.groupBindingId()})
            if(id!=null&&id<=0)throw bad("身份过滤必须为正数");
        if(q.limit()!=null&&(q.limit()<1||q.limit()>500))throw bad("limit 必须在 1 到 500 之间");
        return q;
    }
    /** 明确越权目标在查询前拒绝，不靠空结果掩盖授权错误。 */
    private void requireFilters(Scope scope,PlatformUsageQuery q) {
        if(q.tenantId()!=null&&!scope.tenants().contains(q.tenantId()))throw forbidden("无权读取目标机构用量");
        if(scope.integrationId()!=null&&q.integrationId()!=null&&!scope.integrationId().equals(q.integrationId()))
            throw forbidden("无权读取其他 integration 用量");
    }
    /** 授权/筛选和 stream 世代先验游标；分页只扫描有界全局窗口，空匹配仍推进。 */
    private Page page(Scope scope,PlatformUsageQuery q) {
        String revision=mapper.streamRevision();
        String binding=binding(scope,q);
        long cursor=decode(q.cursor(),binding,revision);
        requireFilters(scope,q);
        publisher.publishCommitted();
        long tail=mapper.streamTail();
        if(cursor>tail)throw conflict("用量流位置已改变，请从历史起点重建");
        int limit=q.limit()==null?100:q.limit();
        long through=Math.min(tail,cursor>Long.MAX_VALUE-1_000?Long.MAX_VALUE:cursor+1_000);
        return read(scope,()->{
            var rows=mapper.list(scope.tenants(),scope.integrationId(),q,cursor,through,limit+1);
            boolean overflow=rows.size()>limit;
            var visible=rows.subList(0,Math.min(limit,rows.size()));
            if(!visible.isEmpty()) {
                var hops=mapper.grantHops(scope.tenants(),visible.stream().map(PlatformUsageRow::getUsageId).toList())
                        .stream().collect(java.util.stream.Collectors.groupingBy(PlatformUsageGrantHopRow::getUsageId));
                for(var row:visible)row.setGrantChain(hops.getOrDefault(row.getUsageId(),List.of()).stream().map(PlatformUsageGrantHopRow::toHop).toList());
            }
            long scanned=overflow?visible.getLast().getPublicationCursor():through;
            String next=encode(binding,revision,scanned);
            return new Page(visible.stream().map(row->view(row,encode(binding,revision,row.getPublicationCursor()))).toList(),
                    next,overflow||scanned<tail||mapper.hasPending(scope.tenants(),scope.integrationId(),q));
        });
    }
    /** 发布提交后读取同一事实，累计报告按实际尝试归并、确认替换原成本、调整只加一次。 */
    private Summary summary(Scope scope,PlatformUsageQuery q) {
        requireFilters(scope,q);publisher.publishCommitted();
        var rows=read(scope,()->mapper.summarize(scope.tenants(),scope.integrationId(),q));
        if(rows.isEmpty())return new Summary(0,0,null,null,0,0,List.of());
        var totals=rows.getFirst();
        var costs=rows.stream().filter(r->r.getCurrency()!=null&&r.getAmountCents()!=null)
                .map(r->new Cost(r.getCurrency(),r.getAmountCents().toPlainString())).toList();
        return new Summary(totals.getRequestCount(),totals.getInvocationCount(),totals.getPromptTokens(),
                totals.getCompletionTokens(),totals.getUnknownUsageCount(),totals.getUnresolvedCostCount(),costs);
    }
    /** 稳定业务身份和精确金额公开为字符串，累计报告不是新的消费次数。 */
    private Event view(PlatformUsageRow row,String cursor) {
        boolean tokenMetered=Set.of("llm.chat","llm.embedding","media.image","media.video","media.transcription","media.speech").contains(row.getEventType());
        boolean measurementKnown=tokenMetered?"PROVIDER_REPORTED".equals(row.getUsageSource())
                || "not_applicable".equals(row.getAmountStatus()) && Integer.valueOf(0).equals(row.getQuantity()):row.getQuantity()!=null;
        String kind=switch(row.getUsageEventKind()) {
            case "reconciliation"->"confirmation";
            case "adjustment"->"adjustment";
            case "usage_report"->measurementKnown?"usage_report":"unknown";
            default->measurementKnown?"measured":"unknown";
        };
        Long tokens=row.getTokens();
        if(tokens==null&&row.getPromptTokens()!=null&&row.getCompletionTokens()!=null) {
            try { tokens=Math.addExact(row.getPromptTokens(),row.getCompletionTokens()); }
            catch(ArithmeticException overflow) { tokens=null; }
        }
        return new Event(cursor,row.getEventId(),row.getSourceEventId(),row.getOriginKind(),row.getActorRef(),
                row.getResponsibilityRef(),text(row.getDeploymentId()),text(row.getTenantId()),row.getIntegrationId(),kind,
                row.getReportRevision()!=null?row.getReportRevision():row.getUsageSnapshotRevision()!=null?row.getUsageSnapshotRevision():1,
                row.getAdjustmentOfEventId(),row.getEventType(),row.getQuantity(),
                tokens,row.getPromptTokens(),row.getCompletionTokens(),row.getUsageSource(),row.getAmountStatus(),
                row.getAmountCents()==null?null:row.getAmountCents().toPlainString(),row.getCurrency(),row.getInvocationId(),
                row.getAttemptId(),row.getRequestId(),row.getRootInputId(),text(row.getAgentId()),row.getAgentDefinitionVersion(),
                row.getSessionId(),row.getRunId(),text(row.getEndUserId()),row.getChannelId(),row.getBusinessScopeId(),
                row.getProvider(),row.getModel(),row.getOccurredAt(),row.getPublishedAt(),
                row.getSourceOwnerKind(),text(row.getSourceOwnerTenantId()),text(row.getAgentDefinitionId()),
                text(row.getAgentRevisionId()),row.getAgentRevision(),row.getDeploymentRevision(),
                text(row.getGrantId()),row.getGrantRevision(),row.getGrantChain().stream().map(this::hop).toList(),row.getMeasurementUnit(),text(row.getGroupBindingId()),row.getRequesterKind(),row.getRequesterRef(),
                row.getOriginSessionId(),row.getOriginEventId(),text(row.getResponsibilityAgentId()),text(row.getSystemAgentRunId()),
                row.getSystemAgentConfigurationRef(),row.getSystemAgentInvocationRef(),
                text(row.getImageCount()),text(row.getVideoCount()),text(row.getCharacters()),
                row.getDurationSeconds()==null?null:row.getDurationSeconds().toPlainString(),
                text(row.getPricingModelId()),row.getPricingModelRevision());
    }
    private GrantHop hop(PlatformUsageGrantHop value) {
        return new GrantHop(value.ordinal(),text(value.grantId()),value.revision(),value.grantorKind(),
                text(value.grantorTenantId()),text(value.targetTenantId()),text(value.parentGrantId()),
                value.parentGrantRevision(),text(value.relationRef()),value.relationRevision());
    }
    /** cursor/limit不改变过滤语义，授权集合有序摘要防止同游标跨范围使用。 */
    private String binding(Scope scope,PlatformUsageQuery q) {
        var filter=new PlatformUsageQuery(q.integrationId(),q.deploymentId(),q.tenantId(),q.agentId(),q.agentDefinitionVersion(),
                q.endUserId(),q.sessionId(),q.runId(),q.channelId(),q.businessScopeId(),q.from(),q.to(),null,null,q.groupBindingId());
        try {
            return TokenHash.sha256Hex(scope.actor()+"\n"+scope.authorizationVersion()+"\n"+
                    scope.tenants().stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))+"\n"+
                    String.valueOf(scope.integrationId())+"\n"+json.writeValueAsString(filter));
        } catch(Exception invalid) { throw new IllegalStateException("usage_cursor_binding_failed",invalid); }
    }
    /** 使用现有 AES-GCM 认证信封，游标既不可篡改也不泄露全局流尾。 */
    private String encode(String binding,String revision,long position) {
        try { return crypto.encrypt(json.writeValueAsString(new Cursor("platform-usage-v1",binding,revision,position))); }
        catch(Exception invalid) { throw new IllegalStateException("usage_cursor_encode_failed",invalid); }
    }
    /** 签名/格式失败400，合法但旧授权/过滤/世代冲突409；不会默默重置到起点。 */
    private long decode(String value,String binding,String revision) {
        if(value==null||value.isBlank())return 0;
        Cursor cursor;
        try { cursor=json.readValue(crypto.decrypt(value),Cursor.class); }
        catch(Exception invalid) { throw bad("cursor 无效"); }
        if(!"platform-usage-v1".equals(cursor.schema())||cursor.position()<0)throw bad("cursor 无效");
        if(!binding.equals(cursor.binding())||!revision.equals(cursor.revision()))throw conflict("用量授权、过滤或流版本已变化，请显式从历史起点重建");
        return cursor.position();
    }
    /** 数字身份不得通过浮点转换。 */
    private static String text(Long value) { return value==null?null:value.toString(); }
    /** 参数错误不携带跨范围事实。 */
    private static ApiException bad(String value) { return new ApiException(HttpStatus.BAD_REQUEST,value); }
    /** 当前范围拒绝。 */
    private static ApiException forbidden(String value) { return new ApiException(HttpStatus.FORBIDDEN,value); }
    /** 旧游标必须显式重建。 */
    private static ApiException conflict(String value) { return new ApiException(HttpStatus.CONFLICT,value); }
    /** 当前授权闭包，仅在一次读取内复用。 */
    private record Scope(String actor,String authorizationVersion,Set<Long> tenants,String integrationId,
            HeadquartersUsageAccess.Authorization headquarters) { }
    /** 认证加密的内部游标；position是扫描位置而非源事件id。 */
    private record Cursor(String schema,String binding,String revision,long position) { }
}
