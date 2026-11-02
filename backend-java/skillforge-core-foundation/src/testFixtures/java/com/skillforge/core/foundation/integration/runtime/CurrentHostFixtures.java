package com.skillforge.core.foundation.integration.runtime;

import java.sql.Connection;
import java.sql.SQLException;

/** 正式宿主 schema 的最小身份事实；只提供明确列，不创建简化业务表。 */
public final class CurrentHostFixtures {
    /** 本批宿主身份造数的独立套餐标识。 */
    private static final long PLAN = 800000000000000301L;

    private CurrentHostFixtures() { }

    /** 管理连接在自有新库建立套餐及机构，重复机构ID保持已有事实。 */
    public static void tenant(Connection connection, long id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO public.nw_billing_plans(id,plan_code,display_name,slo_availability_target)
                VALUES (?,'fixture-host-iam','Host IAM fixture',0.999) ON CONFLICT(id) DO NOTHING
                """)) {
            statement.setLong(1, PLAN); statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO public.nw_tenants(id,name,slug,plan_id,status)
                VALUES (?,?,?,?,'active') ON CONFLICT(id) DO NOTHING
                """)) {
            statement.setLong(1, id); statement.setString(2, "Fixture tenant " + id);
            statement.setString(3, "fixture-tenant-" + id); statement.setLong(4, PLAN); statement.executeUpdate();
        }
    }

    /** 建立指定机构的已发布测试Agent，不伪造团队或应用授权。 */
    public static void agent(Connection connection, long tenant, long id) throws SQLException {
        tenant(connection, tenant);
        try (var statement = connection.prepareStatement("""
                INSERT INTO public.nw_agents(id,agent_key,tenant_id,name,status)
                VALUES (?,?,?,?,'published')
                """)) {
            statement.setLong(1, id); statement.setString(2, "fixture-agent-" + id);
            statement.setLong(3, tenant); statement.setString(4, "Fixture agent " + id); statement.executeUpdate();
        }
    }

    /** 建立指定机构的活跃终端用户。 */
    public static void endUser(Connection connection, long tenant, long id) throws SQLException {
        tenant(connection, tenant);
        try (var statement = connection.prepareStatement("INSERT INTO public.nw_end_users(id,tenant_id,status) VALUES (?,?,'active')")) {
            statement.setLong(1, id); statement.setLong(2, tenant); statement.executeUpdate();
        }
    }

    /** Callback 传入其真实受控 AES 密文；不消费签名的身份场景仍提供非空测试凭据字段。 */
    public static void servicePrincipal(Connection connection, long id, long tenant, String principal,
            String integration, long version, String scopes, String encryptedSigning) throws Exception {
        tenant(connection, tenant);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(("fixture-key-" + tenant + "-" + principal).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try (var statement = connection.prepareStatement("""
                INSERT INTO public.nw_service_principals(id,tenant_id,principal_id,integration_id,credential_version,
                    status,scopes,api_key_hash,api_key_prefix,signing_secret_encrypted)
                VALUES (?,?,?,?,?,'active',?::jsonb,?,'fixture',?)
                """)) {
            statement.setLong(1, id); statement.setLong(2, tenant); statement.setString(3, principal);
            statement.setString(4, integration); statement.setLong(5, version); statement.setString(6, scopes);
            statement.setString(7, hash); statement.setString(8, encryptedSigning); statement.executeUpdate();
        }
    }
}
