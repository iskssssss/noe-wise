package example.host;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.List;

/** 最终Session/SPI/Core/API JAR的独立消费者，只消费新协调器与原始输入协议。 */
public final class SessionConsumer {
    private SessionConsumer() { }
    public static void main(String[] args) throws java.io.IOException {
        // 情境信息来自冻结输入和真实观察，不再发布未被运行时装配的提示词资源。
        for(String filename:List.of("recent-conversation.md","history-state-guidance.md","current-task-boundary.md",
                "truncated-output.md","workflow-completed.md","workflow-resumed.md")) {
            require(AgentExecutionProgram.class.getResource("/com/skillforge/runtime/session/prompts/"+filename)==null,
                    "unused packaged prompt: "+filename);
        }
        var codec=new AgentExecutionCodec(new CodecLimits(100000,10000,64));
        var input=new TypedPayload("input.v1","{\"text\":\"实际用户输入\"}");
        var messages=List.of(new ModelMessage("input",ModelRole.USER,"实际用户输入"));
        var wire=codec.encodeInput(messages,input);
        require(codec.decodeInput(wire).equals(messages),"input messages changed");
        require(codec.frozenInput(wire).equals(input),"frozen input changed");
        require(codec.encodeInput(codec.decodeInput(wire),codec.frozenInput(wire)).equals(wire),"canonical input changed");
        require(ExecutionCoordinator.Capabilities.class.isInterface(),"missing single capability boundary");
        require(ExecutionStore.Phase.valueOf("UNCERTAIN")==ExecutionStore.Phase.UNCERTAIN,"missing honest uncertain state");
        System.out.println("Session/SPI/Core/API JAR consumer: typed execution input passed");
    }
    private static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
}
