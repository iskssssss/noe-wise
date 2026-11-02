package com.skillforge.core.knowledge.integration.runtime.knowledge;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.KnowledgePortal.*;
import com.skillforge.runtime.codec.spi.KnowledgeDocumentCodec;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 只验证direct计算边界与失败分类；真实RLS关键词SQL由数据库/HTTP组合用例覆盖。 */
class JdbcKnowledgeDirectRetrievalTest {
    private static final Instant NOW=Instant.parse("2026-10-22T00:00:00Z");
    private static final TenantId TENANT=new TenantId("1");
    private static final KnowledgeDocumentCodec DOCUMENTS=CoreRuntime.knowledgeDocuments(new CodecLimits(1_048_576,10_000,16));
    private final JdbcKnowledgeBindings bindings=mock(JdbcKnowledgeBindings.class);
    private final DataSource source=mock(DataSource.class);
    private final FrozenKnowledgeTarget target=new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:mount"),
            new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE,"20","30"),"10",7,
            Set.of(new KnowledgeSourceVersion("10","v1"),new KnowledgeSourceVersion("10","v2")),
            Set.of(KnowledgeOperatorType.KEYWORD,KnowledgeOperatorType.LINK),Duration.ofSeconds(5));
    private final JdbcKnowledgeBindings.Binding frozen=new JdbcKnowledgeBindings.Binding(target,"{}",NOW.minusSeconds(100),"frozen-binding");

    /** 不读取旧policy/results；保留两份来源和版本，不从无主体/时间依据的同键异值断定冲突。 */
    @Test void retrieveUsesGivenBindingAndPreservesVersionTimeScopeWithoutLegacyReads() {
        var request=request(Set.of(KnowledgeOperatorType.KEYWORD));
        when(bindings.keyword(eq(TENANT),eq(target),eq(request.allowedSourceVersions()),eq("alpha"),eq(5),eq(request.asOf()),eq(request.freshnessAfter()),any()))
                .thenReturn(List.of(hit("v1","chunk-a","alpha\npolicy: enabled"),hit("v2","chunk-b","alpha\npolicy: disabled")));
        var result=portal(bundle->true).retrieve(request,target,frozen,"actual-request-fingerprint",control(),()->true);
        assertEquals(2,result.items().size());assertEquals(EvidenceSufficiency.PARTIAL,result.sufficiency());
        assertTrue(result.conflictItemIds().isEmpty());
        assertTrue(result.items().getFirst().content().canonicalJson().contains("policy: enabled"));
        assertTrue(result.items().getLast().content().canonicalJson().contains("policy: disabled"));
        assertEquals(Set.of("v1","v2"),result.items().stream().map(EvidenceItem::sourceVersion).collect(java.util.stream.Collectors.toSet()));
        assertEquals(result.items().stream().map(EvidenceItem::itemId).toList(),result.trace().getFirst().selectedItemIds());
        assertEquals(JdbcKnowledgePortal.CONTENT_SCHEMA,result.items().getFirst().content().schemaId());
        verify(bindings).keyword(eq(TENANT),eq(target),eq(request.allowedSourceVersions()),eq("alpha"),eq(5),eq(request.asOf()),eq(request.freshnessAfter()),any());
        verifyNoMoreInteractions(bindings);verifyNoInteractions(source);
    }

    /** 无I/O前的明确拒绝可确定失败；原SQL技术异常必须保留原对象，不能包装成已知拒绝。 */
    @Test void onlyPreflightDenialIsTypedAndTechnicalFailureRemainsUnresolved() {
        var portal=portal(bundle->true);
        var denied=assertThrows(JdbcKnowledgePortal.RetrievalRejected.class,
                ()->portal.retrieve(request(Set.of(KnowledgeOperatorType.KEYWORD)),target,frozen,"request",control(),()->false));
        assertEquals("knowledge_control_inactive",denied.reasonCode());verifyNoInteractions(bindings,source);
        var failure=new IllegalStateException("original_sql_failure");
        when(bindings.keyword(any(),any(),anySet(),anyString(),anyInt(),any(),any(),any())).thenThrow(failure);
        assertSame(failure,assertThrows(IllegalStateException.class,
                ()->portal.retrieve(request(Set.of(KnowledgeOperatorType.KEYWORD)),target,frozen,"request",control(),()->true)));
        verifyNoInteractions(source);
    }

    /** 首次query已发生后失租不再归为预检拒绝，也不返回可注入Bundle。 */
    @Test void ownershipLostAfterQueryCannotBecomeFailedOrSuccess() {
        var allowed=new AtomicBoolean(true);
        when(bindings.keyword(any(),any(),anySet(),anyString(),anyInt(),any(),any(),any())).thenAnswer(invocation->{
            allowed.set(false);return List.of(hit("v1","chunk","alpha"));
        });
        var failure=assertThrows(IllegalStateException.class,
                ()->portal(bundle->true).retrieve(request(Set.of(KnowledgeOperatorType.KEYWORD)),target,frozen,"request",control(),allowed::get));
        assertEquals("knowledge_retrieval_control_changed",failure.getMessage());
        verify(bindings).keyword(any(),any(),anySet(),anyString(),anyInt(),any(),any(),any());verifyNoInteractions(source);
    }

    /** 合法链接仍经过原域名、预算、trace与父证据关系；未授权域名不进入LinkReader。 */
    @Test void controlledLinkComputationKeepsActualReaderAndParentProvenance() {
        when(bindings.keyword(any(),any(),anySet(),anyString(),anyInt(),any(),any(),any())).thenReturn(
                List.of(hit("v1","chunk","alpha https://docs.example.com/policy https://foreign.invalid/private")));
        var requested=new ArrayList<String>();
        var portal=JdbcKnowledgePortal.registered(source,bindings,bundle->true,(url,max,timeout)->{
            requested.add(url);return new JdbcKnowledgePortal.LinkResponse(url,200,"text/plain","actual linked policy",false,false);
        },Clock.fixed(NOW,ZoneOffset.UTC),DOCUMENTS);
        var descriptor=new JdbcKnowledgeBindings.Binding(target,"{\"link_follow_up\":{\"enabled\":true,\"robots_allowed\":true,\"max_depth\":1,\"max_characters\":1024,\"allowed_domains\":[\"docs.example.com\"]}}",NOW.minusSeconds(100),"binding");
        var result=portal.retrieve(request(Set.of(KnowledgeOperatorType.KEYWORD,KnowledgeOperatorType.LINK)),target,descriptor,"request",control(),()->true);
        assertEquals(List.of("https://docs.example.com/policy"),requested);
        var link=result.items().stream().filter(value->value.operator()==KnowledgeOperatorType.LINK).findFirst().orElseThrow();
        assertEquals(JdbcKnowledgePortal.LINK_CONTENT_SCHEMA,link.content().schemaId());
        assertTrue(link.content().canonicalJson().contains("actual linked policy"));
        assertEquals(List.of(result.items().getFirst().itemId()),link.graphPath());
        assertEquals(EvidenceSufficiency.PARTIAL,result.sufficiency());verifyNoInteractions(source);
    }

    /** 生产组件保持不变，只有外部依赖的调用边界在本单测中受控。 */
    private JdbcKnowledgePortal portal(java.util.function.Predicate<EvidenceBundle> output) {
        return JdbcKnowledgePortal.registered(source,bindings,output,(url,max,timeout)->{throw new IllegalStateException("unexpected_link_reader");},Clock.fixed(NOW,ZoneOffset.UTC),DOCUMENTS);
    }
    /** 显式收窄到原冻结来源和版本，时间字段必须原样传入真实关键词边界。 */
    private KnowledgeRequest request(Set<KnowledgeOperatorType> operators) {
        return new KnowledgeRequest("request", TENANT, new com.skillforge.runtime.api.RunRequester.EndUser(new EndUserId(1)), new AgentId("agent"), new SessionRef(TENANT,"session"), new TraceEnvelope("trace","span",new InputId("input"),Optional.empty()), KnowledgeMode.RESEARCH, "answer", new com.skillforge.runtime.api.KnowledgeOperation.Search("alpha"), Set.of("10"), target.sourceVersions(), operators, new KnowledgeBudget(2,4,1000,4096,4000), NOW.minusSeconds(10), Optional.of(NOW.minusSeconds(50)), true);
    }
    /** 已由真实SQL返回的typed行；不在本测试里宣称这些受控行是数据库验收。 */
    private JdbcKnowledgeBindings.ChunkHit hit(String version,String id,String content) {
        return new JdbcKnowledgeBindings.ChunkHit(new KnowledgeSourceVersion("10",version),"document",id,0,content,NOW.minusSeconds(20));
    }
    /** 控制使用固定时钟，避免sleep制造取消时序。 */
    private InvocationControl control(){return new InvocationControl(NOW.plusSeconds(5),()->false);}
}
