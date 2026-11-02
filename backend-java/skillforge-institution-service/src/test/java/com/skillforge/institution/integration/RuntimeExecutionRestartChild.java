package com.skillforge.institution.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.institution.InstitutionApplication;
import com.skillforge.institution.runtime.AgentRuntimeExecutionConfiguration;
import com.skillforge.institution.runtime.AgentRuntimePlatformToolProvider;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 仅供真实OS进程重启验收的子JVM启动器；应用、HTTP、Provider和PG事务仍由正式实现拥有。 */
public final class RuntimeExecutionRestartChild {
    private RuntimeExecutionRestartChild() { }

    public static void main(String[] args) throws Exception {
        requireIsolatedParentFixture();
        var context = new SpringApplicationBuilder(InstitutionApplication.class, AgentRuntimeExecutionConfiguration.class,
                ChatRuntimeChannelE2ETest.LoopbackEvents.class, DeterministicModelCatalogConfiguration.class, Boundaries.class).run(args);
        var server = (ServletWebServerApplicationContext) context;
        var marker = Path.of(context.getEnvironment().getRequiredProperty("restart-test.ready"));
        var json = new ObjectMapper();
        writeMarker(marker, json.writeValueAsString(Map.of("pid", ProcessHandle.current().pid(), "port", server.getWebServer().getPort(),
                "javaVersion", Runtime.version().feature(), "workerId", java.util.Objects.requireNonNull(
                        org.springframework.test.util.ReflectionTestUtils.getField(context.getBean(com.skillforge.runtime.starter.ExecutionHostWorker.class), "worker")))),
                StandardCharsets.UTF_8);
    }

    /** 只借父已迁移的新lane数据库，独立启动本类不能落到本机默认业务库或取得提权身份。 */
    private static void requireIsolatedParentFixture() throws Exception {
        String lane = required("SKILLFORGE_S03_EXTERNAL_FIXTURE_LANE");
        if (!lane.matches("[a-z][a-z0-9_]{0,31}")) throw new IllegalStateException("restart_fixture_lane_invalid");
        var values = new ObjectMapper().readTree(required("SPRING_APPLICATION_JSON"));
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                values.path("spring.datasource.url").textValue(), values.path("spring.datasource.username").textValue(), values.path("spring.datasource.password").textValue()));
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        if (database == null || !database.matches("sf_s03_" + lane + "_[a-z0-9_]+")) throw new IllegalStateException("restart_parent_fixture_required");
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)))
            throw new IllegalStateException("restart_business_role_required");
        if (!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT count(*) FROM public.s03_host_fixture_guard WHERE lane=? AND token=?", Integer.class,
                lane, required("SKILLFORGE_S03_EXTERNAL_FIXTURE_TOKEN")))) throw new IllegalStateException("restart_fixture_guard_mismatch");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE success AND version=?)", Boolean.class,
                required("SKILLFORGE_S03_EXTERNAL_SCHEMA_VERSION")))) throw new IllegalStateException("restart_formal_schema_required");
    }

    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException("restart_fixture_parameter_missing:" + name); return value;
    }

    /** marker只在完整写入后原子出现，父进程观察到文件即能读取完整JSON。 */
    private static void writeMarker(Path target, String value, java.nio.charset.Charset charset) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + ".writing");
        Files.writeString(temporary, value, charset, StandardOpenOption.CREATE_NEW);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Boundaries {
        @Bean
        static BeanPostProcessor actualCommitBoundary(Environment environment) {
            String mode = environment.getRequiredProperty("restart-test.mode");
            String target = environment.getRequiredProperty("restart-test.run");
            Path committed = Path.of(environment.getRequiredProperty("restart-test.committed"));
            Path tools = Path.of(environment.getRequiredProperty("restart-test.tools"));
            var json = new ObjectMapper(); var blocked = new AtomicBoolean();
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof AgentRuntimePlatformToolProvider actual) {
                        var observed = spy(actual);
                        doAnswer(call -> {
                            ExecutionStore.DispatchResult.Registered registered = call.getArgument(0);
                            ExecutionStore.Prepared prepared = call.getArgument(1);
                            if (registered.run().identity().runRef().runId().equals(target)) synchronized (RuntimeExecutionRestartChild.class) {
                                Files.writeString(tools, json.writeValueAsString(Map.of("pid", ProcessHandle.current().pid(), "action", prepared.action().actionId(),
                                        "attempt", registered.attempt().id())) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            }
                            return call.callRealMethod();
                        }).when(observed).invokeRegistered(any(ExecutionStore.DispatchResult.Registered.class), any(ExecutionStore.Prepared.class), any(ToolPortal.ToolCall.class));
                        return observed;
                    }
                    if (!(bean instanceof JdbcExecutionStore actual) || !"saved-result".equals(mode)) return bean;
                    var observed = spy(actual);
                    doAnswer(call -> {
                        ExecutionStore.Completion command = call.getArgument(0);
                        // 必须先完成正式事务；测试从不伪造结果、上下文、用量或Provider回执。
                        var completed = (ExecutionStore.CompletionResult) call.callRealMethod();
                        if (command.run().runId().equals(target) && completed.advanced() && command.kind() == ExecutionStore.ResultKind.SUCCEEDED
                                && actual.action(completed.attempt().action()).orElseThrow().kind() == ExecutionStore.Kind.TOOL && blocked.compareAndSet(false, true)) {
                            writeMarker(committed, json.writeValueAsString(Map.of("pid", ProcessHandle.current().pid(), "run", target,
                                    "action", completed.attempt().action().actionId(), "attempt", completed.attempt().id(),
                                    "resultRef", completed.attempt().result().orElseThrow().value(), "advanced", true)),
                                    StandardCharsets.UTF_8);
                            new CountDownLatch(1).await(); // 只有父进程OS强杀会结束本次已提交后的阻塞。
                        }
                        return completed;
                    }).when(observed).complete(any(ExecutionStore.Completion.class));
                    return observed;
                }
            };
        }
    }
}
