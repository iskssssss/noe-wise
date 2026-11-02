package example.session;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.core.AgentExecutionLoop;
import com.skillforge.runtime.core.BudgetGuard;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.api.KnowledgeDocumentResult;
import com.skillforge.runtime.codec.spi.KnowledgeDocumentCodec;
import com.skillforge.runtime.core.LoopBudget;
import com.skillforge.runtime.core.LoopUsage;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** 独立JAR消费者只执行纯解析和预算算术，不装配Provider、数据库或旧效果循环。 */
public final class CoreConsumer {
    private CoreConsumer() { }
    /** 当前协议与错schema负控必须在仅Core/API/Codec SPI的classloader中同时成立。 */
    public static void main(String[] args) {
        var loop = new AgentExecutionLoop(new CodecLimits(4096,4096,32));
        var decision = loop.decodeModel(new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA, "{\"type\":\"respond\",\"output\":\"完成\"}"));
        if (!(decision instanceof AgentDecisionDecoder.Decoded value)
                || !(value.action() instanceof AgentAction.Respond result) || !result.output().equals("完成"))
            throw new AssertionError("native_model_decision_invalid");
        if (!(loop.decodeModel(new TypedPayload("application/json", "{}")) instanceof AgentDecisionDecoder.Failure))
            throw new AssertionError("wrong_schema_accepted");
        var budget = new LoopBudget.Enforced(2,0,0,100,100,200,Duration.ofSeconds(5));
        if (BudgetGuard.evaluate(budget,LoopUsage.empty(),BudgetGuard.Operation.TOOL,Duration.ZERO).isEmpty())
            throw new AssertionError("zero_tool_capacity_accepted");
        KnowledgeDocumentCodec documents = CoreRuntime.knowledgeDocuments(new CodecLimits(4096,4096,32));
        var page = new KnowledgeDocumentResult.CatalogPage(List.of(), Optional.empty());
        if (!page.equals(documents.decode(documents.encode(page)))
                || !new TypedPayload(KnowledgeDocumentCodec.TEXT_SCHEMA, "{\"text\":\"原文\\n😀\"}").equals(documents.encodeText("原文\n😀")))
            throw new AssertionError("knowledge_document_codec_invalid");
        try {
            documents.decode(new TypedPayload("application/json", "{}"));
            throw new AssertionError("knowledge_document_schema_accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Verified pure Core consumer from exact dependency JARs");
    }
}
