package com.skillforge.runtime.adapter.sqlite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.sqlite.Function;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** 写操作使用 IMMEDIATE；纯读取使用独立 DEFERRED 快照，不争用单机写权。 */
final class SqliteDatabase implements AutoCloseable {
    private final SQLiteDataSource source;
    private final SQLiteDataSource readSource;
    private volatile boolean closed;
    SqliteDatabase(Path file, Clock clock) {
        Objects.requireNonNull(file); Objects.requireNonNull(clock);
        Path absolute = file.toAbsolutePath().normalize();
        try { Files.createDirectories(absolute.getParent()); }
        catch (IOException failure) { throw new IllegalStateException("desktop_database_directory_failed", failure); }
        source = source(absolute, clock, SQLiteConfig.TransactionMode.IMMEDIATE);
        readSource = source(absolute, clock, SQLiteConfig.TransactionMode.DEFERRED);
        var configuration = configuration(source);
        load(configuration, "mapper/SqliteSchemaMapper.xml");
        load(configuration, "mapper/SqliteBudgetSchemaMapper.xml");
        load(configuration, "mapper/SqliteBudgetPolicyMigrationMapper.xml");
        var factory = new SqlSessionFactoryBuilder().build(configuration);
        // 只在本次初始化连接关闭 FK 检查；重建与数据复制共用一个 IMMEDIATE 事务。
        // 数据源不池化该连接，新业务连接仍由 SQLiteConfig 强制开启外键。
        try (var connection = source.getConnection(); var session = factory.openSession(connection)) {
            String migration = "com.skillforge.runtime.adapter.sqlite.SqliteBudgetPolicyMigrationMapper.";
            int version = session.selectOne("com.skillforge.runtime.adapter.sqlite.SqliteSchemaMapper.version");
            if (version < 0 || version > 1) throw new IllegalStateException("desktop_database_schema_version_unsupported");
            // 当前布局已原子完成初始化，不重复执行 DDL、迁移探测和全库外键扫描。
            if (version == 1) return;
            session.update(migration + "disableForeignKeysForBootstrap");
            connection.setAutoCommit(false);
            try {
                var names = configuration.getMappedStatementNames().stream().filter(name -> name.contains("SqliteSchemaMapper.create") || name.contains("SqliteBudgetSchemaMapper.create")).sorted(java.util.Comparator.comparing((String name) -> name.substring(0, name.lastIndexOf('.'))).thenComparingInt(name -> Integer.parseInt(name.substring(name.lastIndexOf("create") + 6)))).toList();
                for (String name : names) session.update(name);
                if (session.<Integer>selectOne(migration + "hasPolicyMode") == 0) {
                    for (String step : java.util.List.of("createRoots", "createRuns", "createBalances",
                            "copyRoots", "copyRuns", "copyBalances", "dropRuns", "dropRoots", "dropBalances",
                            "renameRoots", "renameRuns", "renameBalances", "indexRuns")) session.update(migration + step);
                }
                if (session.<Integer>selectOne(migration + "foreignKeyViolations") != 0)
                    throw new IllegalStateException("desktop_database_migration_foreign_key_invalid");
                session.selectList("com.skillforge.runtime.adapter.sqlite.SqliteSchemaMapper.validateCurrentColumns");
                session.update("com.skillforge.runtime.adapter.sqlite.SqliteSchemaMapper.recordVersion");
                // 会话建立时连接尚为自动提交；切换后必须显式提交/回滚实际 IMMEDIATE 事务。
                session.commit(true);
            } catch (RuntimeException | Error failure) {
                try { session.rollback(true); }
                catch (RuntimeException | Error rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("desktop_database_migration_failed", failure);
        }
    }
    private SQLiteDataSource source(Path absolute, Clock clock, SQLiteConfig.TransactionMode mode) {
        var options = new SQLiteConfig();
        options.enforceForeignKeys(true);
        options.setBusyTimeout(5_000);
        options.setJournalMode(SQLiteConfig.JournalMode.WAL);
        options.setTransactionMode(mode);
        var dataSource = new SQLiteDataSource(options) {
            @Override public org.sqlite.SQLiteConnection getConnection() throws SQLException { return getConnection(null, null); }
            @Override public org.sqlite.SQLiteConnection getConnection(String user, String password) throws SQLException { return initialize(super.getConnection(user, password)); }
            private org.sqlite.SQLiteConnection initialize(org.sqlite.SQLiteConnection connection) throws SQLException {
                try {
                    if (closed) throw new SQLException("desktop_database_closed");
                    // 时钟函数仅由连接上下文注册；业务数据读写仍全部经过 MyBatis Mapper。
                    Function.create(connection, "runtime_now", new Function() { @Override protected void xFunc() throws SQLException { result(SqliteInstantTypeHandler.format(clock.instant())); } });
                    Function.create(connection, "runtime_lease_valid", new Function() { @Override protected void xFunc() throws SQLException { result(Instant.ofEpochSecond(value_long(0), value_int(1)).isAfter(clock.instant()) ? 1 : 0); } });
                    return connection;
                } catch (SQLException | RuntimeException | Error failure) {
                    // 返回给 MyBatis 之前仍由本边界拥有连接，初始化失败不能遗留 native 文件句柄。
                    try { connection.close(); }
                    catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
        };
        dataSource.setUrl("jdbc:sqlite:" + absolute);
        return dataSource;
    }
    DataSource readSource() { if (closed) throw new IllegalStateException("desktop_database_closed"); return readSource; }
    DataSource source() { if (closed) throw new IllegalStateException("desktop_database_closed"); return source; }
    static Configuration configuration(DataSource source) {
        var configuration = new Configuration(new Environment("desktop-sqlite", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.getTypeHandlerRegistry().register(Instant.class, SqliteInstantTypeHandler.class);
        return configuration;
    }
    static void load(Configuration configuration, String resource) {
        try (InputStream input = SqliteDatabase.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("desktop_mapper_missing");
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (IOException failure) { throw new IllegalStateException("desktop_mapper_failed", failure); }
    }
    @Override public void close() { closed = true; }
}
