package com.skillforge.runtime.core;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 从真实main产物检查纯计算边界，防止重新引入Provider、存储或隐式时钟依赖。 */
class CoreBoundaryTest {
    @Test void mainClassesHaveNoInfrastructureFieldsOrConstructorDependencies() throws Exception {
        var root=Path.of(CoreRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(root));
        try(var files=Files.walk(root.resolve("com/skillforge/runtime/core"))) {
            var classes=files.filter(p->p.toString().endsWith(".class")).toList();
            assertTrue(classes.size()>10);
            for(var file:classes) {
                String relative=root.relativize(file).toString().replace('\\','.').replace('/','.');
                var type=Class.forName(relative.substring(0,relative.length()-6),false,CoreRuntime.class.getClassLoader());
                for(var field:type.getDeclaredFields()) assertFalse(infrastructure(field.getType()),field.toString());
                for(var constructor:type.getDeclaredConstructors()) for(var argument:constructor.getParameterTypes())
                    assertFalse(infrastructure(argument),constructor.toString());
            }
        }
    }

    @Test void publicRuntimeOnlyExposesPureProtocolOperations() {
        var methods=List.of(CoreRuntime.class.getDeclaredMethods());
        assertTrue(methods.stream().anyMatch(m->m.getName().equals("portalResults")));
        assertTrue(methods.stream().anyMatch(m->m.getName().equals("decodeModelOutcome")));
        for(var method:methods) if(Modifier.isPublic(method.getModifiers())) {
            assertFalse(method.getName().matches("actionCalls|model|tool|workflow|knowledge|.*Cycle|.*Cancellation|.*Journal"),method.toString());
            for(var argument:method.getParameterTypes()) assertFalse(infrastructure(argument),method.toString());
        }
    }

    /** Port数据record可被纯计算消费，可调用的Port本体与基础设施对象不可被持有。 */
    private static boolean infrastructure(Class<?> type) {
        var name=type.getName();
        return name.startsWith("javax.sql.")||name.startsWith("java.sql.")||name.startsWith("org.apache.ibatis.")
                ||name.startsWith("org.springframework.")||name.equals("java.time.Clock")
                ||name.matches("com\\.skillforge\\.runtime\\.api\\.port\\.[^$]+Port")
                ||name.equals("com.skillforge.runtime.api.port.KnowledgePortal")
                ||name.equals("com.skillforge.runtime.api.port.ToolPortal")
                ||name.equals("com.skillforge.runtime.api.port.WorkflowPortal");
    }
}
