package example.host;

import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryRetrievalDiagnostics;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.MemoryTaskConstraint;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.runtime.memory.MemorySourcePolicy;
import com.skillforge.runtime.memory.MemoryTypeRegistry;
import java.lang.reflect.Modifier;
import java.util.List;

/** 只用两个发布 JAR 验证 Memory 的受支持入口可加载且公开。 */
public final class MemoryConsumer {
    private static final List<Class<?>> ENTRY_POINTS = List.of(
            MemoryRuntime.class,
            MemoryQuery.class,
            MemoryRetrievalPolicy.class,
            MemoryRetrievalDiagnostics.class,
            MemoryTaskConstraint.class,
            MemoryAccessPolicy.class,
            MemoryTypeRegistry.class,
            MemorySourcePolicy.class);

    private MemoryConsumer() { }

    public static void main(String[] args) throws ReflectiveOperationException {
        for (Class<?> type : ENTRY_POINTS) {
            require(Modifier.isPublic(type.getModifiers()), type.getName() + " must be public");
        }
        MemoryQuery.class.getMethod("taskConstraint");
        MemoryQuery.class.getMethod("retrievalPolicy");
        MemoryRetrievalPolicy.class.getMethod("lexicalV1");
        MemoryRuntime.class.getMethod("retrieve", MemoryRuntime.RetrievalCall.class);
        MemoryRuntime.class.getMethod("mutate", MemoryRuntime.MutationCall.class);
        MemoryRuntime.class.getMethod("replayRetrieval",
                com.skillforge.runtime.api.port.PortCallContext.class,
                com.skillforge.runtime.api.port.CommittedSessionEvent.class);
        System.out.println("Memory/API JAR consumer: public entry points loaded");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new AssertionError(reason);
        }
    }
}
