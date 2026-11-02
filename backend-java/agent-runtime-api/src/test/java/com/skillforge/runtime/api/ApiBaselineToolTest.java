package com.skillforge.runtime.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 使用真实 javac/JAR/javap 验证 ABI 发现和拒绝漂移，不以手写输出模拟 JDK 工具。 */
class ApiBaselineToolTest {

    private static final String SURFACE = """
            package com.skillforge.runtime.api.fixture;
            /** 公开夹具覆盖泛型、throws、record、enum 和 sealed nested 类型。 */
            public class Surface {
                /** 若工具初始化类型而不是只加载，测试立即失败。 */
                static { if (System.nanoTime() != 0) throw new IllegalStateException("must not initialize"); }
                /** 泛型与 throws 都属于需冻结的公开签名。 */
                public java.util.List<String> values() throws java.io.IOException { return null; }
                /** 嵌套 record 的构造器及字段投影必须一起进入 baseline。 */
                public record Data(String value) { }
                /** 枚举常量不能被只扫方法的工具漏掉。 */
                public enum Phase { READY, DONE }
                /** 故意用反序 permits，输出必须按 binary FQN 排序。 */
                public sealed interface Choice permits Zed, Alpha { }
                /** sealed 终端类型只提供合法层次，不实现业务。 */
                public static final class Zed implements Choice { }
                /** sealed 终端类型只提供合法层次，不实现业务。 */
                public static final class Alpha implements Choice { }
                /** 私有类型不构成 JVM public ABI。 */
                private static class Hidden { }
                // EXTRA_TYPES
            }
            """;

    /** 每个测试独占含空格的临时路径，不污染真实 baseline 或共享 build 输出。 */
    @TempDir(factory = BuildTempDirectoryFactory.class)
    Path temporaryDirectory;

    /** 真实多批次 javap、JAR entry 反序及内部类 dollar 字符均不改变规范输出。 */
    @Test
    void discoversAndSortsAllPublicTypesWithoutInitialization() throws Exception {
        StringBuilder extras = new StringBuilder();
        for (int index = 0; index < 35; index++) {
            // 超过工具的单批上限，防止只有单批输出正确的假绿。
            extras.append("/** 批处理边界夹具。 */ public static class Extra")
                    .append(index).append(" {}\n");
        }
        String source = SURFACE.replace("// EXTRA_TYPES", extras);
        Path jar = compileJar("first fixture", source, false);
        Path reorderedJar = compileJar("reordered fixture", source, true);
        Path baseline = temporaryDirectory.resolve("baseline.txt");
        Path reorderedBaseline = temporaryDirectory.resolve("reordered.txt");
        generate(jar, baseline);
        generate(reorderedJar, reorderedBaseline);
        assertArrayEquals(Files.readAllBytes(baseline), Files.readAllBytes(reorderedBaseline));

        String content = Files.readString(baseline);
        String inventory = content.split("== PUBLIC TYPES ==\n", 2)[1]
                .split("\n== SEALED PERMITS ==", 2)[0].strip();
        List<String> types = inventory.lines().toList();
        assertEquals(41, types.size());
        assertEquals(types.stream().sorted().toList(), types);
        assertFalse(inventory.contains("Hidden"));
        assertTrue(content.contains("Surface$Choice -> com.skillforge.runtime.api.fixture.Surface$Alpha,"
                + "com.skillforge.runtime.api.fixture.Surface$Zed"));
        assertTrue(content.contains("java.util.List<java.lang.String> values() throws java.io.IOException"));
        assertTrue(content.contains("descriptor: ()Ljava/util/List;"));
        assertFalse(content.contains("Compiled from"));
        assertFalse(content.contains("\r"));
        assertFalse(content.contains(temporaryDirectory.toString()));
        assertTrue(content.endsWith("\n"));
        assertFalse(content.endsWith("\n\n"));
    }

    /** 同一既有基线必须拒绝每类公开 ABI 变更，失败时仍保留当前报告且不接受新签名。 */
    @ParameterizedTest
    @ValueSource(strings = {"nested", "generic", "descriptor", "throws", "record", "enum", "permits", "topLevel", "deleted"})
    void rejectsAbiDriftWithoutUpdatingBaseline(String change) throws Exception {
        Path baseline = temporaryDirectory.resolve("committed.txt");
        generate(compileJar("original", SURFACE, false), baseline);
        byte[] before = Files.readAllBytes(baseline);
        String changed = switch (change) {
            case "nested" -> SURFACE.replace("// EXTRA_TYPES", "public static class Added { }");
            case "generic" -> SURFACE.replace("List<String>", "List<Integer>");
            case "descriptor" -> SURFACE.replace("java.util.List<String>", "java.util.Set<String>");
            case "throws" -> SURFACE.replace("java.io.IOException", "java.sql.SQLException");
            case "record" -> SURFACE.replace("Data(String value)", "Data(String value, long revision)");
            case "enum" -> SURFACE.replace("READY, DONE", "READY, DONE, CANCELLED");
            case "permits" -> SURFACE.replace("permits Zed, Alpha", "permits Zed")
                    .replace("class Alpha implements Choice", "class Alpha");
            case "topLevel" -> SURFACE;
            case "deleted" -> SURFACE.replace("public record Data(String value) { }", "");
            default -> throw new IllegalArgumentException(change);
        };
        Path changedJar = compileJar("changed", changed, false, change.equals("topLevel"));
        Path report = temporaryDirectory.resolve("build report/current.txt");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> verify(changedJar, baseline, report));
        assertTrue(error.getMessage().contains("baseline mismatch at line"));
        assertArrayEquals(before, Files.readAllBytes(baseline));
        assertTrue(Files.isRegularFile(report));
        assertFalse(Files.readString(baseline).equals(Files.readString(report)));

        // 唯有显式 generate 才接受新 ABI；后续 verify 必须与同一真实 JAR 匹配。
        generate(changedJar, baseline);
        verify(changedJar, baseline, report);
        assertEquals(Files.readString(baseline), Files.readString(report));
    }

    /** 缺失基线在任何输出前失败，report 的别名不能间接创建待比较的基线。 */
    @Test
    void missingBaselineFailsBeforeAnyOutput() throws Exception {
        Path jar = compileJar("missing fixture", SURFACE, false);
        Path baseline = temporaryDirectory.resolve("missing.txt");
        Path report = temporaryDirectory.resolve("report.txt");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> verify(jar, baseline, report));
        assertTrue(error.getMessage().contains("baseline is missing"));
        assertFalse(Files.exists(baseline));
        assertFalse(Files.exists(report));
        assertThrows(IllegalStateException.class, () -> verify(jar, baseline, baseline));
        assertFalse(Files.exists(baseline));
    }

    /** 只容忍 checkout 换行，不能把尾随空格误当作允许的格式归一化。 */
    @Test
    void allowsCheckoutCrLfButRejectsTrailingWhitespace() throws Exception {
        Path jar = compileJar("line endings", SURFACE, false);
        Path baseline = temporaryDirectory.resolve("baseline.txt");
        Path report = temporaryDirectory.resolve("report.txt");
        generate(jar, baseline);
        String canonical = Files.readString(baseline);
        Files.writeString(baseline, canonical.replace("\n", "\r\n"));
        verify(jar, baseline, report);
        assertEquals(canonical, Files.readString(report));
        Files.writeString(baseline, canonical.replaceFirst("baseline v1", "baseline v1 "));
        assertThrows(IllegalStateException.class, () -> verify(jar, baseline, report));
    }

    /** 验证模式不能让 report 与输入别名重合，否则会在比较前覆盖证据或损坏 JAR。 */
    @Test
    void rejectsOutputAliasesBeforeWritingAnyInput() throws Exception {
        Path jar = compileJar("alias fixture", SURFACE, false);
        Path baseline = temporaryDirectory.resolve("baseline.txt");
        generate(jar, baseline);
        byte[] baselineBefore = Files.readAllBytes(baseline);
        byte[] jarBefore = Files.readAllBytes(jar);
        assertThrows(IllegalArgumentException.class,
                () -> verify(jar, baseline, baseline.getParent().resolve(".").resolve("baseline.txt")));
        assertThrows(IllegalArgumentException.class, () -> verify(jar, baseline, jar));
        assertThrows(IllegalArgumentException.class, () -> generate(jar, jar));
        // NTFS/Unix 硬链接具有不同路径但相同文件身份，不能靠字符串比较放行。
        Path linkedBaseline = Files.createLink(temporaryDirectory.resolve("linked.txt"), baseline);
        assertThrows(IllegalArgumentException.class, () -> verify(jar, baseline, linkedBaseline));
        assertArrayEquals(baselineBefore, Files.readAllBytes(baseline));
        assertArrayEquals(jarBefore, Files.readAllBytes(jar));
    }

    /** 错误模式、参数数量及不存在的输入不能静默运行生成路径。 */
    @Test
    void rejectsInvalidInvocation() {
        assertThrows(IllegalArgumentException.class, () -> ApiBaselineTool.main(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> ApiBaselineTool.main(new String[] {"update"}));
        assertThrows(IllegalArgumentException.class, () -> ApiBaselineTool.main(new String[] {"verify"}));
        assertThrows(IllegalArgumentException.class,
                () -> generate(temporaryDirectory.resolve("absent.jar"), temporaryDirectory.resolve("baseline")));
    }

    /** 通过公共 CLI 分支生成，不绕过参数与 JDK 版本检查。 */
    private static void generate(Path jar, Path baseline) throws Exception {
        ApiBaselineTool.main(new String[] {"generate", jar.toString(), baseline.toString()});
    }

    /** 通过公共 CLI 分支验证，覆盖真实报告落盘路径。 */
    private static void verify(Path jar, Path baseline, Path report) throws Exception {
        ApiBaselineTool.main(new String[] {"verify", jar.toString(), baseline.toString(), report.toString()});
    }

    /** 常规夹具仅包含一个源码文件，委托同一编译和打包实现。 */
    private Path compileJar(String directoryName, String source, boolean reverseEntries) throws IOException {
        return compileJar(directoryName, source, reverseEntries, false);
    }

    /** 临时文件归本 worktree 的 build，避免沙箱子进程访问其他用户的系统临时目录。 */
    public static class BuildTempDirectoryFactory implements TempDirFactory {
        /** JUnit 扩展通过无参数构造器创建独立临时目录工厂。 */
        public BuildTempDirectoryFactory() {
        }

        /** 每次分配唯一目录，由 JUnit 在该测试结束后清理。 */
        @Override
        public Path createTempDirectory(AnnotatedElementContext elementContext, ExtensionContext extensionContext)
                throws IOException {
            Path parent = Files.createDirectories(Path.of("build", "tmp", "api-baseline-tests"));
            return Files.createTempDirectory(parent, "fixture-");
        }
    }

    /** 使用真实 javac 编译后自行排列 JAR entry；辅助顶层类用于发现覆盖测试。 */
    private Path compileJar(String directoryName, String source, boolean reverseEntries, boolean extraTopLevel)
            throws IOException {
        Path directory = Files.createDirectories(temporaryDirectory.resolve(directoryName));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Path sourceFile = directory.resolve("Surface.java");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        var arguments = new ArrayList<>(List.of("--release", "21", "-encoding", "UTF-8", "-d",
                classes.toString(), sourceFile.toString()));
        if (extraTopLevel) {
            // 新 public 顶层类必须被最终 JAR 扫描发现，而非依赖已有手工名单。
            Path extra = directory.resolve("Added.java");
            Files.writeString(extra, "package com.skillforge.runtime.api.fixture; /** 新顶层 ABI。 */ public class Added {}");
            arguments.add(extra.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        Path jar = directory.resolve("fixture with spaces.jar");
        try (var paths = Files.walk(classes); var output = new JarOutputStream(Files.newOutputStream(jar))) {
            Comparator<Path> order = Comparator.naturalOrder();
            if (reverseEntries) {
                // 反序输入用于证明基线不依赖 zip central-directory 的遍历顺序。
                order = order.reversed();
            }
            for (Path file : paths.filter(Files::isRegularFile).sorted(order).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return jar;
    }
}
