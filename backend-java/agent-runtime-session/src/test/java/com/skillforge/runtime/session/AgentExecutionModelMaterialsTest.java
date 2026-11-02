package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.core.FrozenModelRequest;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 实际MODEL请求分片只改变持久表达；恢复、Provider与诊断仍读同一完整冻结值。 */
class AgentExecutionModelMaterialsTest {
    private static final CodecLimits LIMITS=new CodecLimits(2_000_000,200_000,128);
    private final AgentExecutionCodec codec=new AgentExecutionCodec(LIMITS);

    @Test void inFlightAndOneBatchRecoveryProduceTheExactFrozenRequest() {
        var original=request(List.of(new ModelMessage("user",ModelRole.USER,"large input")),"large tool");
        var bundle=codec.encodeModelRequestMaterials(original);
        var prepared=prepared(bundle.root(),bundle.parts());
        assertEquals(original,codec.decodeModelRequest(prepared));
        var count=new AtomicInteger(); var parts=new HashMap<ResultRef,ExecutionStore.Material>();
        bundle.parts().forEach(part->parts.put(part.reference(),part));
        var actual=codec.decodeModelRequest(prepared(bundle.root(),List.of()),refs->{
            count.incrementAndGet(); return refs.stream().map(ref->Optional.ofNullable(parts.get(ref))).toList();
        });
        assertEquals(original,actual); assertEquals(original.request().encode(),actual.request().encode());
        assertEquals(1,count.get());
        assertEquals(original.request().tools().getFirst(),codec.requireProgramTool(bundle.root().content(),"tool",refs->
                refs.stream().map(ref->Optional.ofNullable(parts.get(ref))).toList()));
    }

    @Test void rejectsMissingAlteredAndExtraLeafOrWrongRootReference() {
        var bundle=codec.encodeModelRequestMaterials(request(List.of(new ModelMessage("user",ModelRole.USER,"body")),"tool"));
        assertThrows(IllegalArgumentException.class,()->codec.decodeModelRequest(bundle.root().content(),refs->refs.stream().map(ref->Optional.<ExecutionStore.Material>empty()).toList()));
        var changed=new ArrayList<>(bundle.parts()); var first=changed.getFirst();
        changed.set(0,new ExecutionStore.Material(first.reference(),new TypedPayload(first.content().schemaId(),"{}")));
        assertThrows(IllegalArgumentException.class,()->codec.decodeModelRequest(prepared(bundle.root(),changed)));
        var extra=new ArrayList<>(bundle.parts()); var body=new TypedPayload(AgentExecutionCodec.MODEL_MESSAGE_SCHEMA,"{\"message\":{}}");
        extra.add(new ExecutionStore.Material(AgentExecutionCodec.reference(body),body));
        assertThrows(IllegalArgumentException.class,()->codec.decodeModelRequest(prepared(bundle.root(),extra)));
        assertThrows(IllegalArgumentException.class,()->codec.decodeModelRequest(prepared(new ExecutionStore.Material(new ResultRef("wrong-root"),bundle.root().content()),bundle.parts())));
    }

    @Test void reconstructedRequestCannotBypassTheOriginalTotalLimit() {
        var bundle=codec.encodeModelRequestMaterials(request(List.of(new ModelMessage("first",ModelRole.USER,"x".repeat(8192)),
                new ModelMessage("second",ModelRole.USER,"y".repeat(8192))),"tool"));
        var limited=new AgentExecutionCodec(new CodecLimits(12_000,200_000,128));
        assertTrue(bundle.parts().stream().allMatch(part->part.content().canonicalJson().length()<12_000));
        assertTrue(bundle.root().content().canonicalJson().length()<12_000);
        assertThrows(IllegalArgumentException.class,()->limited.decodeModelRequest(prepared(bundle.root(),bundle.parts())));
    }

    @Test void tenAndThirtyRoundsCountAllManifestsAndOnlyDistinctBodies() {
        for (int rounds:List.of(10,30)) {
            var messages=new ArrayList<ModelMessage>(); var leaves=new LinkedHashMap<ResultRef,ExecutionStore.Material>();
            long inline=0,manifests=0;
            for (int round=0;round<rounds;round++) {
                messages.add(new ModelMessage("message-"+round,ModelRole.USER,"x".repeat(8192)+round));
                var original=request(messages,"tool-description-"+"t".repeat(16_384));
                var bundle=codec.encodeModelRequestMaterials(original);
                inline+=bytes(codec.encodeModelRequest(original)); manifests+=bytes(bundle.root().content());
                bundle.parts().forEach(part->leaves.putIfAbsent(part.reference(),part));
                assertEquals(original,codec.decodeModelRequest(prepared(bundle.root(),bundle.parts())));
            }
            long distinct=leaves.values().stream().mapToLong(part->bytes(part.content())).sum();
            assertEquals(rounds+1,leaves.size()); assertTrue(manifests+distinct<inline);
            System.out.printf("MODEL_MATERIAL_BYTES rounds=%d inline=%d manifests=%d uniqueLeaves=%d total=%d leaves=%d%n",
                    rounds,inline,manifests,distinct,manifests+distinct,leaves.size());
        }
    }

    private static long bytes(TypedPayload payload) { return payload.canonicalJson().getBytes(StandardCharsets.UTF_8).length; }
    private static ExecutionStore.Prepared prepared(ExecutionStore.Material root,List<ExecutionStore.Material> parts) {
        return new ExecutionStore.Prepared(new ActionRef(new TenantId("1"),"action"),1,ExecutionStore.Kind.MODEL,"model",new ResultRef("capability"),root,"fingerprint",parts);
    }
    private static AgentExecutionCodec.ModelCallRequest request(List<ModelMessage> messages,String description) {
        var target=new FrozenModelTarget(new ModelBindingRef(new TenantId("1"),"model",1),"model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT),true,true,new ResultRef("capability")),Duration.ofSeconds(30),new ResultRef("resolution"));
        var tool=new ModelTool("tool","tool:clock:input",description,new TypedPayload("json-schema","{}"),true,ModelToolKind.TOOL_CALL,false);
        var request=new FrozenModelRequest(new RequestedModelStrategy("strategy","1"),target,messages,List.of(tool),ToolChoice.AUTO,
                new ResponseContract(new ResponseFormat.NativeConversation(),EnforcementLevel.STRICT,"supported"),
                new GenerationOptions(OptionalInt.empty(),OptionalDouble.empty()),ReasoningPolicy.DEFAULT);
        return new AgentExecutionCodec.ModelCallRequest(request,List.of(),0,Optional.empty());
    }
}
