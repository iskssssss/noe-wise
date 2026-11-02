package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 先实际监听动态端口，再提供 Spring CORS 来源；后端就绪后配置此进程的代理目标。 */
final class MemoryBrowserServer implements AutoCloseable {
    private static final String SIGNAL = "MEMORY_BROWSER_SIGNAL ";
    private final Process process;
    private final MemoryBrowserProcesses processes;
    private final CompletableFuture<String> listening = new CompletableFuture<>();
    private final CompletableFuture<String> backendReady = new CompletableFuture<>();
    private final Thread output;
    private final Thread shutdownHook;
    private final Path evidence;
    private final ObjectMapper json = new ObjectMapper();
    private boolean closed;

    /** Node 和 Vite 仅使用现有安装；失败仍登记并等待自有进程清理。 */
    private MemoryBrowserServer(Path evidence, Path admin) throws IOException {
        this.evidence = evidence;
        Files.createDirectories(evidence);
        Path vite = admin.resolve("node_modules/vite/dist/node/index.js");
        if (!Files.isRegularFile(vite)) throw new IllegalStateException("未安装 Vite：" + vite);
        Path helper = Files.createTempFile(evidence, "vite-memory-", ".mjs");
        Files.writeString(helper, helperSource(vite), StandardCharsets.UTF_8);
        ProcessBuilder command = new ProcessBuilder(nodeExecutable(), helper.toString());
        command.directory(admin.toFile());
        command.redirectErrorStream(true);
        command.environment().remove("E2E_BACKEND_URL");
        command.environment().remove("SDK4_LIVE_MODEL_KEY");
        process = command.start();
        processes = new MemoryBrowserProcesses(process.toHandle());
        shutdownHook = new Thread(this::close, "memory-browser-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        output = Thread.ofPlatform().name("memory-browser-vite-output").start(this::readOutput);
    }

    /** 创建唯一运行目录并等待服务报告已监听；绝不扫描空闲端口。 */
    static MemoryBrowserServer start() {
        MemoryBrowserServer server = null;
        try {
            Path evidence;
            String configured = System.getenv("LIVE_MEMORY_EVIDENCE_DIR");
            if (configured == null || configured.isBlank()) {
                Path state = repoRoot().resolve(".agent-work-state");
                Files.createDirectories(state);
                evidence = Files.createTempDirectory(state, "memory-browser-");
            } else evidence = Path.of(configured).toAbsolutePath().normalize().resolve("browser");
            server = new MemoryBrowserServer(evidence, repoRoot().resolve("frontend-admin"));
            server.baseUrl();
            return server;
        } catch (Exception failure) {
            if (server != null) {
                try { server.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            throw new IllegalStateException("隔离记忆浏览器前端启动失败", failure);
        }
    }

    /** 返回实际监听地址；等待失败带有本次日志位置。 */
    String baseUrl() {
        try {
            return listening.get(90, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("Vite 未报告监听完成；证据=" + evidence, failure);
        }
    }

    /** 本次证据根；包含 Vite 日志、Playwright 报告和持久事实。 */
    Path evidence() {
        return evidence;
    }

    /** 将已经监听的 Spring 地址交给 Vite，等待代理配置 ACK 后才允许浏览器请求。 */
    void bindBackend(String backendUrl) throws Exception {
        process.outputWriter(StandardCharsets.UTF_8).write(json.writeValueAsString(java.util.Map.of("backendUrl", backendUrl)) + "\n");
        process.outputWriter(StandardCharsets.UTF_8).flush();
        String acknowledged = backendReady.get(15, TimeUnit.SECONDS);
        if (!backendUrl.equals(acknowledged)) throw new IllegalStateException("隔离后端代理 ACK 不匹配");
    }

    /** 日志泵同时保存监听和代理就绪信号；EOF 不能被当作成功就绪。 */
    private void readOutput() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = Files.newBufferedWriter(evidence.resolve("vite.log"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
                writer.flush();
                processes.capture();
                if (!line.startsWith(SIGNAL)) continue;
                var signal = json.readTree(line.substring(SIGNAL.length()));
                if (signal.has("baseUrl")) listening.complete(signal.path("baseUrl").asText());
                if (signal.has("backendUrl")) backendReady.complete(signal.path("backendUrl").asText());
            }
            var failure = new IllegalStateException("Vite 子进程在就绪前退出；日志=" + evidence.resolve("vite.log"));
            listening.completeExceptionally(failure);
            backendReady.completeExceptionally(failure);
        } catch (IOException failure) {
            listening.completeExceptionally(failure);
            backendReady.completeExceptionally(failure);
        }
    }

    /** 关闭监听和所有已确认后代，随后等待日志线程静止；多次调用无副作用。 */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            processes.close();
            output.join(TimeUnit.SECONDS.toMillis(10));
            if (output.isAlive()) throw new IllegalStateException("记忆浏览器 Vite 日志线程未静止");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Vite 清理时中断", failure);
        } finally {
            if (Thread.currentThread() != shutdownHook) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException shuttingDown) { /* JVM 已关闭，原钩子不再使用。 */ }
            }
        }
    }

    /** Node 直接导入安装的 Vite；stdin 只接收本夹具提供的后端地址。 */
    private static String helperSource(Path vite) {
        String viteUrl = vite.toUri().toString().replace("'", "%27");
        return """
                import { createServer } from '%s';
                import { createInterface } from 'node:readline';
                const proxies = [];
                const proxy = () => ({
                  target: 'http://127.0.0.1:9', changeOrigin: true,
                  configure(instance) { proxies.push(instance); }
                });
                const server = await createServer({
                  server: { host: '127.0.0.1', port: 0, strictPort: true,
                    proxy: { '/api': proxy(), '/health': proxy() } }
                });
                await server.listen(0);
                const address = server.httpServer.address();
                console.log('MEMORY_BROWSER_SIGNAL ' + JSON.stringify({ baseUrl: 'http://127.0.0.1:' + address.port }));
                const input = createInterface({ input: process.stdin });
                input.on('line', line => {
                  const { backendUrl } = JSON.parse(line);
                  const url = new URL(backendUrl);
                  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1') throw new Error('invalid isolated backend');
                  for (const instance of proxies) instance.options.target = backendUrl;
                  console.log('MEMORY_BROWSER_SIGNAL ' + JSON.stringify({ backendUrl }));
                });
                let closing = false;
                async function close() { if (closing) return; closing = true; input.close(); await server.close(); process.exit(0); }
                process.on('SIGTERM', close);
                process.on('SIGINT', close);
                input.on('close', close);
                """.formatted(viteUrl);
    }

    /** 使用调用方明确的 Node，缺省使用 PATH 中现有 Node。 */
    static String nodeExecutable() {
        String value = System.getenv("NODE_BINARY");
        return value == null || value.isBlank() ? "node" : value;
    }

    /** 从当前模块向上找正式前端根，不依赖固定机器路径。 */
    static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath().normalize();
        while (path != null) {
            if (Files.isRegularFile(path.resolve("frontend-admin/package.json"))) return path;
            path = path.getParent();
        }
        throw new IllegalStateException("repository_root_missing");
    }
}
