package com.skillforge.runtime.desktop;

import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;

/** Electron 私有 stdio 入口；标准输出仅写协议，应用异常不展开输入、凭据或供应商载荷。 */
public final class DesktopHostMain {
    private DesktopHostMain() { }

    public static void main(String[] arguments) {
        if (arguments.length != 2 || !arguments[0].equals("--data-dir")) {
            System.err.println("Usage: noewise-desktop-host --data-dir <directory>"); return;
        }
        var output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        var wire = new DesktopProtocol(output);
        try (var runtime = new DesktopRuntime(Path.of(arguments[1]), Clock.systemDefaultZone(), DesktopPolicy.defaults(), wire::event)) {
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(runtime::close));
            runtime.start();
            wire.event(DesktopJson.object().put("event", "ready").set("data", DesktopJson.object().put("protocolVersion", 1)));
            wire.serve(new InputStreamReader(System.in, StandardCharsets.UTF_8), runtime);
        } catch (Exception failure) {
            System.err.println("桌面宿主启动或关闭失败，请检查数据目录、Java 环境及本地状态。");
            wire.event(DesktopJson.object().put("event", "fatal").set("data", DesktopJson.object().put("code", "HOST_FAILED")
                    .put("message", "本地宿主无法运行，请检查数据目录是否被其他实例占用。")));
        }
    }
}
