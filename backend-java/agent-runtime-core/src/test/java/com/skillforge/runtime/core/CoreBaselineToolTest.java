package com.skillforge.runtime.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;

/** 用独立编译的 JAR 验证 Core ABI 工具，不让当前测试 classes 隐式补齐缺失依赖。 */
class CoreBaselineToolTest {
    @TempDir(factory = BuildDirectory.class) Path temporary;

    /** 子进程同样需要访问 JAR；沿用项目既有 ABI 测试做法，将临时构件放在隔离 worktree 的 build 内。 */
    public static final class BuildDirectory implements TempDirFactory {
        /** JUnit 显式创建工厂，不使用用户配置或共享用户 Temp。 */
        public BuildDirectory() { }
        /** 每个测试独立目录，避免 Windows sandbox/文件锁与其他 worktree 的结果交叠。 */
        @Override public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws Exception {
            Path parent = Files.createDirectories(Path.of("build", "tmp", "core-abi"));
            return Files.createTempDirectory(parent, "fixture-");
        }
    }

    /** 真实依赖只用于解析，不进入 inventory；nested/sealed 及超过一个 javap 批次都必须稳定。 */
    @Test
    void discoversSortedNestedSealedTypesWithoutImportingDependencyInventory() throws Exception {
        var api = dependency();
        var nested = new StringBuilder();
        for (int index = 0; index < 35; index++) { nested.append("/** 批次边界测试值。 */ public record V").append(index).append("(int value) {}\n"); }
        var classes = compile("probe", "com.skillforge.runtime.core.Probe", """
                package com.skillforge.runtime.core;
                /** 独立编译的 public ABI 夹具。 */
                public final class Probe {
                    /** 封闭联合不能漏记子类型。 */
                    public sealed interface Choice permits Left, Right {}
                    /** 第一个合法分支。 */ public record Left(int value) implements Choice {}
                    /** 第二个合法分支。 */ public record Right(int value) implements Choice {}
                    /** 返回依赖但不把依赖列入 Core inventory。 */
                    public com.skillforge.runtime.api.Dependency dependency() { return null; }
                    /** 私有 owner 中的 public 类型不对外可见。 */
                    private static final class Hidden { public record Nested(int value) {} }
                """ + nested + "}\n", api);
        var forward = jar(classes, "forward.jar", false);
        var reverse = jar(classes, "reverse.jar", true);
        String snapshot = CoreBaselineTool.snapshot(forward, api);
        assertEquals(snapshot, CoreBaselineTool.snapshot(reverse, api));
        String inventory = snapshot.substring(snapshot.indexOf("== PUBLIC TYPES =="), snapshot.indexOf("== SEALED PERMITS =="));
        assertTrue(inventory.contains("Probe$V34"));
        assertFalse(inventory.contains("Dependency")); assertFalse(inventory.contains("Hidden"));
        assertTrue(snapshot.contains("com.skillforge.runtime.core.Probe$Choice -> com.skillforge.runtime.core.Probe$Left,com.skillforge.runtime.core.Probe$Right"));
        assertTrue(snapshot.contains("descriptor: ()Lcom/skillforge/runtime/api/Dependency;"));
    }

    /** 错包/空输入和实际签名缺依赖都拒绝，不允许生成零类型基线。 */
    @Test
    void refusesEmptyInventoryMissingJarsAndMissingSignatureDependencies() throws Exception {
        var api = dependency();
        var empty = jar(Files.createDirectory(temporary.resolve("empty")), "empty.jar", false);
        assertThrows(IllegalStateException.class, () -> CoreBaselineTool.snapshot(empty, api));
        assertThrows(IllegalStateException.class, () -> CoreBaselineTool.snapshot(api, api));
        assertThrows(IllegalArgumentException.class, () -> CoreBaselineTool.snapshot(api, temporary.resolve("missing.jar")));
        var classes = compile("missing", "com.skillforge.runtime.core.NeedsApi", """
                package com.skillforge.runtime.core;
                /** 缺失依赖不能借测试父 loader 补齐。 */
                public final class NeedsApi {
                    /** 签名强制依赖实际 API JAR。 */
                    public com.skillforge.runtime.api.Dependency value() { return null; }
                }
                """, api);
        var core = jar(classes, "needs.jar", false);
        assertThrows(LinkageError.class, () -> CoreBaselineTool.snapshot(core, empty));
    }

    /** 验证只写报告，ABI 漂移拒绝；baseline/API/Core 及硬链接别名均不能成为输出目标。 */
    @Test
    void separatesGenerateVerifyAndProtectsEveryInputAlias() throws Exception {
        var api = dependency();
        var classes = compile("verify", "com.skillforge.runtime.core.Probe", """
                package com.skillforge.runtime.core;
                /** 简单独立 ABI 夹具。 */ public record Probe(int value) {}
                """, api);
        var core = jar(classes, "verify.jar", false);
        var baseline = temporary.resolve("baseline.txt"); var report = temporary.resolve("report.txt");
        assertThrows(IllegalStateException.class, () -> run("verify", core, api, baseline, report));
        assertFalse(Files.exists(report));
        run("generate", core, api, baseline);
        String expected = Files.readString(baseline);
        var codec = jar(Files.createDirectory(temporary.resolve("codec-empty")), "codec.jar", false);
        var json = jar(Files.createDirectory(temporary.resolve("json-empty")), "json.jar", false);
        run("generate", core, api, codec, json, baseline);
        run("verify", core, api, codec, json, baseline, report);
        assertEquals(expected, Files.readString(report));
        for (Path dependency : List.of(codec, json)) {
            assertThrows(IllegalArgumentException.class,
                    () -> run("verify", core, api, codec, json, baseline, dependency));
        }
        Files.writeString(baseline, expected.replace("\n", "\r\n"));
        run("verify", core, api, baseline, report);
        assertEquals(expected, Files.readString(report));
        Files.writeString(baseline, expected + "changed\n");
        assertThrows(IllegalStateException.class, () -> run("verify", core, api, baseline, report));
        assertEquals(expected + "changed\n", Files.readString(baseline));
        byte[] originalCore = Files.readAllBytes(core), originalApi = Files.readAllBytes(api);
        for (Path input : List.of(core, api, baseline)) {
            assertThrows(IllegalArgumentException.class, () -> run("verify", core, api, baseline, input));
        }
        for (Path input : List.of(core, api)) { assertThrows(IllegalArgumentException.class, () -> run("generate", core, api, input)); }
        var alias = temporary.resolve("api-alias.jar"); Files.createLink(alias, api);
        assertThrows(IllegalArgumentException.class, () -> run("generate", core, api, alias));
        assertArrayEquals(originalCore, Files.readAllBytes(core)); assertArrayEquals(originalApi, Files.readAllBytes(api));
    }

    /** 测试 JVM 已加载同名 CoreRuntime，工具仍只读取传入 JAR 的版本，不使用 parent-first 应用类路径。 */
    @Test
    void isolatesJarIdentityFromApplicationClassLoader() throws Exception {
        assertNotNull(CoreRuntime.class);
        var api = dependency();
        var classes = compile("shadow", "com.skillforge.runtime.core.CoreRuntime", """
                package com.skillforge.runtime.core;
                /** 与生产同名的隔离 JAR 夹具。 */
                public final class CoreRuntime {
                    /** 只有目标 JAR 版本有该方法。 */ public int isolatedMarker() { return 1; }
                }
                """, api);
        String snapshot = CoreBaselineTool.snapshot(jar(classes, "shadow.jar", false), api);
        assertTrue(snapshot.contains("isolatedMarker()")); assertFalse(snapshot.contains("ModelCycle"));
    }

    /** 独立编译依赖 JAR，其类型只有 Core fixture 的显式 classpath 能访问。 */
    private Path dependency() throws Exception {
        return jar(compile("dependency", "com.skillforge.runtime.api.Dependency", """
                package com.skillforge.runtime.api;
                /** Core fixture 所需的独立依赖。 */ public record Dependency(String value) {}
                """, null), "api.jar", false);
    }

    /** 通过 JDK 编译器生成真实 class，源码与输出都限定在每次测试自己的临时目录。 */
    private Path compile(String directory, String name, String source, Path dependency) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(directory));
        Path file = root.resolve(name.replace('.', '/') + ".java"); Files.createDirectories(file.getParent()); Files.writeString(file, source, StandardCharsets.UTF_8);
        Path classes = Files.createDirectory(root.resolve("classes"));
        var args = new ArrayList<>(List.of("--release", "21", "-encoding", "UTF-8", "-d", classes.toString()));
        if (dependency != null) { args.addAll(List.of("-classpath", dependency.toString())); }
        args.add(file.toString());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return classes;
    }

    /** 改变 JAR entry 顺序以证明输出由二进制类名排序，而不是压缩包偶然顺序决定。 */
    private Path jar(Path classes, String name, boolean reverse) throws Exception {
        Path target = temporary.resolve(name);
        try (var output = new JarOutputStream(Files.newOutputStream(target)); var paths = Files.walk(classes)) {
            var entries = paths.filter(Files::isRegularFile).sorted().toList();
            if (reverse) { entries = entries.reversed(); }
            for (var entry : entries) {
                output.putNextEntry(new JarEntry(classes.relativize(entry).toString().replace('\\', '/')));
                output.write(Files.readAllBytes(entry)); output.closeEntry();
            }
        }
        return target;
    }

    /** 调用真实 CLI 参数分派，负向测试不绕过输出路径检查。 */
    private static void run(String mode, Path... paths) throws Exception {
        var args = new ArrayList<>(List.of(mode)); for (var path : paths) { args.add(path.toString()); }
        CoreBaselineTool.main(args.toArray(String[]::new));
    }
}
