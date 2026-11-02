package com.skillforge.runtime.adapter.jdbc;

import java.sql.DriverManager;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** 仅供各suite自有新库的迁移与受限连接夹具；不创建Run、许可或旧Runtime模拟链。 */
public final class CurrentRuntimeTestDatabase {
    private CurrentRuntimeTestDatabase(){}
    /** 每个suite独立拥有容器生命周期，不共享仍运行的数据库。 */
    public static PostgreSQLContainer<?> database(String name) {
        return new PostgreSQLContainer<>("pgvector/pgvector:pg16").withDatabaseName(name).withUsername("admin").withPassword("test-only-admin");
    }
    /** 完整正式迁移之后建立非superuser、NOBYPASSRLS测试角色，不放宽领域约束。 */
    public static void migrate(PostgreSQLContainer<?> database,String role,String password) throws Exception {
        if(!role.matches("[a-z][a-z0-9_]{0,62}")||!password.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("fixture_role_invalid");
        CurrentRuntimeDatabase.migrate(database.getJdbcUrl(),database.getUsername(),database.getPassword());
        execute(database,"CREATE ROLE "+role+" LOGIN PASSWORD '"+password+"' NOSUPERUSER NOBYPASSRLS");
        execute(database,"GRANT USAGE ON SCHEMA public TO "+role);
        execute(database,"GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO "+role);
        execute(database,"GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO "+role);
    }
    /** 只提供该suite显式角色的连接，不缓存租户或身份。 */
    public static DataSource source(PostgreSQLContainer<?> database,String role,String password) {
        var source=new PGSimpleDataSource();source.setURL(database.getJdbcUrl());source.setUser(role);source.setPassword(password);return source;
    }
    /** 自有fixture管理连接只执行该suite明确的DDL或损坏注入。 */
    public static void execute(PostgreSQLContainer<?> database,String sql) throws Exception {
        try(var connection=DriverManager.getConnection(database.getJdbcUrl(),database.getUsername(),database.getPassword());var statement=connection.createStatement()){statement.execute(sql);}
    }
}
