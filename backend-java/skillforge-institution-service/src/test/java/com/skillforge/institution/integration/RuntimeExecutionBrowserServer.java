package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.execution.browser.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

/**
 * 确定Browser service HTTP协议端，不替换BrowserClient、Handler、设备归属检查或媒体服务。
 * 实际HTTP发送、认证、命令与截图请求均计数；该夹具不代表真实浏览器扩展执行证据。
 */
final class RuntimeExecutionBrowserServer implements AutoCloseable {
    static final String TOKEN = "synthetic-runtime-browser-protocol-token";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Owner, BrowserDevices> devices = new ConcurrentHashMap<>();
    private final Map<Invocation, BrowserReceipt> receipts = new ConcurrentHashMap<>();
    private final Map<Task, BrowserReceipt> tasks = new ConcurrentHashMap<>();
    private final List<BrowserRequest> executions = new CopyOnWriteArrayList<>();
    private final List<Exchange> exchanges = new CopyOnWriteArrayList<>();
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private final byte[] screenshot;
    private final Map<Owner, byte[]> ownerScreenshots = new ConcurrentHashMap<>();

    /** 仅测试启动时绑定loopback；关闭责任归调用测试类。 */
    RuntimeExecutionBrowserServer() throws IOException {
        screenshot = png();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers); server.createContext("/internal/browser", this::handle); server.start();
    }

    /** Spring的生产BrowserClient只使用此真实HTTP地址。 */
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    /** 模拟外部个人设备上线，不直接调用宿主恢复或修改Runtime等待。 */
    void devices(String tenant, String user, List<BrowserDevices.Device> items, String selectedBrowser) {
        devices.put(new Owner(tenant, user), new BrowserDevices(items, "https://example.invalid/install-browser", selectedBrowser));
    }

    /** 按真实请求中的可信owner统计实际命令发送，包括重复发送而非只数去重回执。 */
    List<BrowserRequest> executions(String tenant, String user) {
        return executions.stream().filter(value -> tenant.equals(value.tenant()) && user.equals(value.endUser())).toList();
    }

    /** 精确owner的实际HTTP路径观察，不将调用次数当作业务成功。 */
    List<Exchange> exchanges(String tenant, String user) {
        return exchanges.stream().filter(value -> value.owner().equals(new Owner(tenant, user))).toList();
    }

    /** 原截图字节独立保留供媒体持久证据比对。 */
    byte[] screenshot() { return screenshot.clone(); }

    /** 独立owner的外部大图来源；仍只由实际截图HTTP读取，不直接注册宿主媒体。 */
    void screenshot(String tenant, String user, byte[] bytes) { ownerScreenshots.put(new Owner(tenant, user), bytes.clone()); }

    /** 异常不吞成成功；测试结束时必须核对本列表为空。 */
    List<String> failures() { return List.copyOf(failures); }

    /** HTTP路由保持生产内部token与owner协议，不接受测试专用业务捷径。 */
    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Browser-Service-Token"))) {
                send(exchange, 401, Map.of("error", "browser_service_authentication_required")); return;
            }
            String path = exchange.getRequestURI().getPath().substring("/internal/browser".length());
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) get(exchange, path);
            else if ("POST".equals(method)) post(exchange, path);
            else send(exchange, 405, Map.of("error", "browser_method_rejected"));
        } catch (Exception failure) {
            failures.add(failure.getClass().getSimpleName() + ":" + failure.getMessage());
            send(exchange, 500, Map.of("error", "browser_fixture_protocol_failure"));
        } finally { exchange.close(); }
    }

    /** 查询严格按tenant/user划分设备、任务和截图，不能跨个人读取。 */
    private void get(HttpExchange exchange, String path) throws IOException {
        var query = query(exchange.getRequestURI().getRawQuery());
        var owner = new Owner(query.get("tenant"), query.get("user"));
        exchanges.add(new Exchange("GET", path, owner));
        switch (path) {
            case "/devices" -> send(exchange, 200, observed(owner));
            case "/tasks", "/task-states" -> send(exchange, 200, tasks.entrySet().stream()
                    .filter(value -> value.getKey().owner().equals(owner)).map(Map.Entry::getValue).toList());
            case "/screenshot" -> {
                var receipt = receipts.get(new Invocation(owner, query.get("invocation")));
                if (receipt == null || !"screenshot".equals(receipt.operation()) || !"succeeded".equals(receipt.status())) {
                    send(exchange, 404, Map.of("error", "browser_screenshot_not_owned")); return;
                }
                byte[] body = ownerScreenshots.getOrDefault(owner, screenshot);
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            }
            default -> send(exchange, 404, Map.of("error", "browser_fixture_route_not_found"));
        }
    }

    /** 设备选择与实际命令都经HTTP；选择器本身不替代执行时的当前设备归属与有效期检查。 */
    private void post(HttpExchange exchange, String path) throws Exception {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if ("/execute".equals(path)) {
            var request = json.readValue(body, BrowserRequest.class);
            var owner = new Owner(request.tenant(), request.endUser());
            exchanges.add(new Exchange("POST", path, owner)); executions.add(request);
            send(exchange, 200, execute(owner, request, digest(body))); return;
        }
        JsonNode command = json.readTree(body);
        var owner = new Owner(command.path("tenant").asText(), command.path("user").asText());
        exchanges.add(new Exchange("POST", path, owner));
        if ("/select".equals(path)) {
            var current = observed(owner); String id = command.path("device").asText();
            var chosen = current.items().stream().filter(value -> id.equals(value.deviceId())).findFirst();
            if (chosen.isEmpty()) { send(exchange, 403, Map.of("error", "browser_device_not_owned")); return; }
            var next = new BrowserDevices(current.items(), current.installationUrl(), chosen.orElseThrow().browserId());
            devices.put(owner, next); send(exchange, 200, next);
        } else send(exchange, 404, Map.of("error", "browser_fixture_route_not_found"));
    }

    /** 同一invocation只允许相同真实命令；外部新命令再次核验准确task和当前个人设备。 */
    private synchronized BrowserReceipt execute(Owner owner, BrowserRequest request, String fingerprint) {
        var key = new Invocation(owner, request.invocation());
        var previous = receipts.get(key);
        if (previous != null) return previous.fingerprint().equals(fingerprint) ? previous
                : receipt(request, fingerprint, request.task(), null, "rejected", "browser_invocation_conflict");
        BrowserReceipt started = request.task() == null ? null : tasks.get(new Task(owner, request.task()));
        String browser = request.parameters() instanceof BrowserParameters.StartParameters start ? start.browser()
                : started == null ? null : started.browser();
        String failure = null;
        if (!request.deadlineAt().isAfter(Instant.now())) failure = "browser_deadline_exceeded";
        else if (!"start".equals(request.operation()) && (started == null || !started.session().equals(request.session())
                || !started.run().equals(request.run()))) failure = "browser_task_not_owned";
        else if (observed(owner).items().stream().noneMatch(value -> Objects.equals(browser, value.browserId())
                && value.connected() && Instant.parse(value.expiresAt()).isAfter(Instant.now()))) failure = "browser_device_not_owned";
        String task = "start".equals(request.operation())
                ? UUID.nameUUIDFromBytes((owner.tenant() + ":" + owner.user() + ":" + request.invocation()).getBytes(StandardCharsets.UTF_8)).toString()
                : request.task();
        var result = receipt(request, fingerprint, task, browser, failure == null ? "succeeded" : "rejected", failure);
        receipts.put(key, result);
        if (failure == null && "start".equals(request.operation())) tasks.put(new Task(owner, task), result);
        if (failure == null && "stop".equals(request.operation())) tasks.remove(new Task(owner, task));
        return result;
    }

    /** 合法外部回执完整绑定请求，不从测试断言反向合成Runtime执行事实。 */
    private BrowserReceipt receipt(BrowserRequest request, String fingerprint, String task, String browser, String status, String error) {
        var result = json.createObjectNode().put("ok", error == null);
        if (error != null) result.put("error", error);
        if ("screenshot".equals(request.operation()) && error == null) result.put("tab_id", 1).put("mime_type", "image/png");
        return new BrowserReceipt(fingerprint, request.invocation(), request.operation(), task, request.session(), request.run(),
                Instant.now().toString(), status, null, browser,
                browser == null ? null : new BrowserReceipt.BrowserBinding("upstream-session:" + task, "instance:" + task, 1L), result);
    }

    /** 未观察到设备的owner保持空目录，不能借用其他测试或用户的选择。 */
    private BrowserDevices observed(Owner owner) {
        return devices.getOrDefault(owner, new BrowserDevices(List.of(), "https://example.invalid/install-browser", null));
    }

    /** JSON命名与生产Spring BrowserClient一致，时间始终为ISO字符串。 */
    private void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = json.writeValueAsBytes(value); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body);
    }

    /** 查询只接受明确字段，重复key拒绝，避免fixture宽松掩盖真实owner协议错误。 */
    private static Map<String, String> query(String raw) {
        var result = new LinkedHashMap<String, String>();
        for (var entry : Objects.requireNonNull(raw).split("&")) {
            var parts = entry.split("=", 2);
            if (parts.length != 2 || result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null) throw new IllegalArgumentException("browser_query_invalid");
        }
        return result;
    }

    /** 真实PNG图像字节由HTTP传递并交生产媒体解析，未伪造MediaRef或模型图片结果。 */
    private static byte[] png() throws IOException {
        return png(40, 20);
    }

    /** 生成可解码的真实PNG，尺寸由样本选择；原始图可以超过当前冻结模型dimension。 */
    static byte[] png(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            image.setRGB(x, y, x < width / 2 ? 0xffff0000 : 0xff0000ff);
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("png_writer_missing");
        return bytes.toByteArray();
    }

    /** 指纹仅绑定实际HTTP字节，不记录内部认证token。 */
    private static String digest(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    /** 先停止监听再等所有真实HTTP handler退出，测试资源不跨类保留。 */
    @Override public void close() { server.stop(0); workers.close(); }

    /** @param tenant 内部协议机构 @param user 内部协议个人归属 */
    private record Owner(String tenant, String user) {
        private Owner {
            if (tenant == null || user == null || !tenant.matches("[1-9][0-9]*") || !user.matches("[1-9][0-9]*"))
                throw new IllegalArgumentException("browser_owner_invalid");
        }
    }
    /** @param owner 原个人 @param id 原实际调用身份 */
    private record Invocation(Owner owner, String id) { }
    /** @param owner 原个人 @param id 已建立任务UUID */
    private record Task(Owner owner, String id) { }
    /** @param method 实际HTTP方法 @param path 内部协议路径 @param owner 实际绑定个人 */
    record Exchange(String method, String path, Owner owner) { }
}
