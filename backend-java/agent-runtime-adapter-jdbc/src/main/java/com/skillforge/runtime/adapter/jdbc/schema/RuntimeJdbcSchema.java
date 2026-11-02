package com.skillforge.runtime.adapter.jdbc.schema;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;

/**
 * PostgreSQL Runtime 存储的显式生命周期入口；宿主拥有 DataSource、数据库和应用角色。
 *
 * <p>表固定安装到 public，迁移历史与宿主完全分离。构造任何 Runtime 适配器均不执行 DDL。
 * 初始化连接须具备建表和安装 pgvector 扩展的权限；正常执行连接应使用宿主配置的非超级用户、NOBYPASSRLS 角色。</p>
 */
public final class RuntimeJdbcSchema {
    public static final String MIGRATION_LOCATION =
            "classpath:com/skillforge/runtime/adapter/jdbc/schema/migration";
    public static final String HISTORY_TABLE = "agent_runtime_schema_history";
    private static final String MANIFEST =
            "com/skillforge/runtime/adapter/jdbc/schema/runtime-schema-v1.tsv";
    private static final String MAPPER = "mapper/AgentRuntimeSchemaMapper.xml";

    private RuntimeJdbcSchema() { }

    /** 空库安装或升级已纳管的 Runtime；非空且未纳管的数据库必须显式调用 adopt。 */
    public static void install(DataSource source) {
        flyway(source).migrate();
        validate(source);
    }

    /** 只读校验完整 Runtime 结构；无历史时也可用于接管前预检，不创建对象或修复差异。 */
    public static void validate(DataSource source) {
        try (var session = sessions(source).openSession()) {
            verify(session.getMapper(RuntimeSchemaMapper.class).structure());
        }
    }

    /**
     * 显式接管已具备当前完整结构的数据库，不执行宿主迁移，不自动修补或静默 baseline。
     *
     * <p>必须由宿主在维护窗口停止其他 DDL/迁移后调用。接管事务取得所有
     * Runtime 表的排他锁，再校验结构；任一差异直接终止，不建立成功的 Runtime 历史。
     * DataSource 须允许至少两条连接，结构锁在 Flyway 完成历史写入后释放。</p>
     */
    public static void adopt(DataSource source) {
        Objects.requireNonNull(source, "source");
        try (var session = sessions(source).openSession(false)) {
            var mapper = session.getMapper(RuntimeSchemaMapper.class);
            // 锁由外层事务持有，避免 Flyway 前置回调独立提交后提前释放结构保护。
            mapper.lockOwnedTables();
            if (mapper.hasHistory()) {
                throw new IllegalStateException("runtime_schema_already_managed");
            }
            verify(mapper.structure());
            Flyway.configure().dataSource(source).defaultSchema("public")
                    .locations(MIGRATION_LOCATION).table(HISTORY_TABLE)
                    .baselineOnMigrate(false).baselineVersion("1")
                    .baselineDescription("Explicitly verified Runtime schema")
                    .cleanDisabled(true).load().baseline();
        }
        flyway(source).validate();
    }

    private static Flyway flyway(DataSource source) {
        return Flyway.configure().dataSource(Objects.requireNonNull(source, "source"))
                .defaultSchema("public").locations(MIGRATION_LOCATION).table(HISTORY_TABLE)
                .baselineOnMigrate(false).cleanDisabled(true).load();
    }

    private static SqlSessionFactory sessions(DataSource source) {
        var configuration = new Configuration(new Environment("runtime-schema", new JdbcTransactionFactory(),
                Objects.requireNonNull(source, "source")));
        try (var xml = RuntimeJdbcSchema.class.getClassLoader().getResourceAsStream(MAPPER)) {
            if (xml == null) throw new IllegalStateException("runtime_schema_mapper_missing");
            new XMLMapperBuilder(xml, configuration, MAPPER, configuration.getSqlFragments()).parse();
        } catch (IOException failure) {
            throw new IllegalStateException("runtime_schema_mapper_unavailable", failure);
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void verify(List<RuntimeSchemaEntry> actual) {
        List<RuntimeSchemaEntry> expected;
        try (var resource = RuntimeJdbcSchema.class.getClassLoader().getResourceAsStream(MANIFEST)) {
            if (resource == null) throw new IllegalStateException("runtime_schema_manifest_missing");
            try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
                expected = reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .map(RuntimeJdbcSchema::entry).toList();
            }
        } catch (IOException failure) {
            throw new IllegalStateException("runtime_schema_manifest_unavailable", failure);
        }
        if (!expected.equals(actual)) {
            var missing = expected.stream().filter(entry -> !actual.contains(entry))
                    .map(entry -> entry.kind() + ":" + entry.name()).limit(12).toList();
            var unexpected = actual.stream().filter(entry -> !expected.contains(entry))
                    .map(entry -> entry.kind() + ":" + entry.name()).limit(12).toList();
            throw new IllegalStateException("runtime_schema_structure_mismatch expected=" + missing
                    + " unexpected=" + unexpected);
        }
    }

    private static RuntimeSchemaEntry entry(String line) {
        String[] fields = line.split("\t", 3);
        if (fields.length != 3) throw new IllegalStateException("runtime_schema_manifest_invalid");
        return new RuntimeSchemaEntry(fields[0], fields[1],
                new String(Base64.getDecoder().decode(fields[2]), StandardCharsets.UTF_8));
    }
}
