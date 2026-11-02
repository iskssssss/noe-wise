package example.sdk;

import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.runtime.api.port.MediaPort;
import com.skillforge.runtime.api.port.SessionStorePort;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.runtime.starter.AgentRuntimeAutoConfiguration;
import com.skillforge.runtime.starter.ExecutionHostRegistry;
import com.skillforge.runtime.starter.ExecutionHostWorker;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 使用发布 Starter 的正式自动装配启动单一验收宿主；未声明的媒体能力显式拒绝，记忆能力关闭装配。 */
public final class PublishedHost {
    private PublishedHost() { }

    /** 创建真实 JDBC 连接并完成独立正式构件的 Spring 图启动，不执行模型、工具或 worker 调度。 */
    public static void main(String[] arguments) throws Exception {
        try (var context = start(dataSource(environment("SDK_CONSUMER_JDBC_USER"),
                environment("SDK_CONSUMER_JDBC_PASSWORD")))) {
            requirePublishedOrigin(AgentRuntimeAutoConfiguration.class);
            requirePublishedOrigin(SessionStorePort.class);
            requirePublishedOrigin(context.getBean(SessionStorePort.class).getClass());
            requirePublishedOrigin(context.getBean(SessionLogPort.class).getClass());
            requireNoExecutionHost(context);
            try (var connection = context.getBean(DataSource.class).getConnection();
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT 1")) {
                if (!rows.next() || rows.getInt(1) != 1) throw new IllegalStateException("real_database_unavailable");
            }
            System.out.println("SDK_PUBLISHED_HOST_STARTUP_COMPLETE");
        }
    }

    /** 连接明确的新隔离 PostgreSQL；密码只来自进程环境，不写入源码、属性文件或报告。 */
    static DataSource dataSource(String user, String password) {
        return new DriverManagerDataSource(environment("SDK_CONSUMER_JDBC_URL"), user, password);
    }

    /** 通过发布的 AutoConfiguration.imports 装配生产图，保留 Starter 的全部默认生产实现。 */
    static AnnotationConfigApplicationContext start(DataSource source) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("consumer", Map.of(
                "agent-runtime.workers.auto-startup", "false",
                "agent-runtime.memory.enabled", "false",
                "spring.flyway.enabled", "false")));
        context.registerBean(DataSource.class, () -> source);
        context.register(HostCapabilities.class);
        try {
            context.refresh();
            context.getBean(SessionStorePort.class);
            context.getBean(SessionLogPort.class);
            requireNoExecutionHost(context);
            if (!context.getBeansOfType(MemoryRuntime.class).isEmpty()
                    || !context.getBeansOfType(com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort.class).isEmpty())
                throw new IllegalStateException("consumer_memory_outside_scope");
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    /** 必选配置缺失即拒绝，不复用宿主项目默认地址或开发凭据。 */
    static String environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing_environment:" + name);
        return value;
    }

    /** SDK 类型必须由解析到的正式 JAR 提供，不能来自本树源码或 classes 目录。 */
    private static void requirePublishedOrigin(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null || !source.getLocation().getPath().endsWith(".jar")) {
            throw new IllegalStateException("sdk_type_not_from_published_jar:" + type.getName());
        }
    }

    /** 这里只消费会话存储；未注册执行Host时不能装配或启动Worker。 */
    private static void requireNoExecutionHost(AnnotationConfigApplicationContext context) {
        if(!context.getBeansOfType(ExecutionHostRegistry.Host.class).isEmpty()
                ||!context.getBeansOfType(ExecutionHostRegistry.class).isEmpty()
                ||!context.getBeansOfType(ExecutionHostWorker.class).isEmpty())
            throw new IllegalStateException("consumer_execution_host_outside_scope");
    }

    /** 仅声明验收宿主真实拥有的能力；媒体/记忆没有授权范围，因此显式禁用而不提供 Mock。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class HostCapabilities {
        /** 身份由消费项目拥有；没有宿主显式登记的事实时所有认证和授权均拒绝。 */
        @Bean ConsumerHostIdentity consumerHostIdentity() { return new ConsumerHostIdentity(); }

        /** 当前宿主未授权外部秘密解密，生产适配器的秘密入口保持拒绝。 */
        @Bean com.skillforge.runtime.starter.AgentRuntimeDecrypt agentRuntimeDecrypt() {
            return ciphertext -> { throw new IllegalArgumentException("consumer_external_secrets_disabled"); };
        }

        /** 媒体未启用，准备和读取均必须拒绝。 */
        @Bean MediaPort mediaPort() {
            return new MediaPort() {
                /** 未授权读取媒体时保留拒绝，不能制造空白成功字节。 */
                @Override public byte[] read(SessionRef owner, MediaRef reference) {
                    throw new IllegalArgumentException("consumer_media_disabled");
                }
                /** 未授权准备媒体时保留拒绝，不能返回未验证引用。 */
                @Override public MediaRef prepare(SessionRef owner, MediaRef reference, ModelImagePolicy policy) {
                    throw new IllegalArgumentException("consumer_media_disabled");
                }
            };
        }

    }
}
