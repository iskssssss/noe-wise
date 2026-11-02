import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;

/** 只为本次新容器创建完整正式Schema；业务连接从不持有migration owner身份。 */
class ExecutionFixtureBootstrap {
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 21) throw new IllegalStateException("Java21 required");
        String url = required("SF_FIXTURE_JDBC"), owner = required("SF_FIXTURE_OWNER");
        String secret = required("SF_FIXTURE_OWNER_PASSWORD"), password = required("SF_FIXTURE_APP_PASSWORD");
        String lane = required("SF_FIXTURE_LANE"), token = required("SF_FIXTURE_TOKEN");
        if (!lane.matches("[a-z][a-z0-9_]{0,31}") || !password.matches("[a-zA-Z0-9_-]+"))
            throw new IllegalArgumentException("invalid synthetic fixture identifier");
        try (var connection = DriverManager.getConnection(url, owner, secret); var sql = connection.createStatement()) {
            try (var row = sql.executeQuery("SELECT current_database()")) {
                if (!row.next() || !row.getString(1).matches("sf_s03_" + lane + "_[a-z0-9_]+"))
                    throw new IllegalStateException("isolated database required");
            }
            sql.execute("CREATE ROLE skillforge LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '" + password + "'");
            sql.execute("CREATE ROLE sf_fixture_platform LOGIN NOSUPERUSER BYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '" + password + "'");
        }
        var flyway = Flyway.configure().dataSource(url, owner, secret).locations("classpath:db/migration").load();
        var latest = Arrays.stream(flyway.info().all()).map(MigrationInfo::getVersion).filter(Objects::nonNull)
                .max(MigrationVersion::compareTo).orElseThrow();
        var migrated = flyway.migrate();
        if (!latest.equals(flyway.info().current().getVersion()) || flyway.info().pending().length != 0
                || !flyway.validateWithResult().validationSuccessful) throw new IllegalStateException("complete migration validation failed");
        try (var connection = DriverManager.getConnection(url, owner, secret); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE public.s03_host_fixture_guard(lane text PRIMARY KEY,token text NOT NULL)");
            try (var insert = connection.prepareStatement("INSERT INTO public.s03_host_fixture_guard VALUES (?,?)")) {
                insert.setString(1, lane); insert.setString(2, token); insert.executeUpdate();
            }
            try (var row = sql.executeQuery("SELECT pg_get_userbyid(relowner)=current_user FROM pg_class WHERE oid='public.s03_host_fixture_guard'::regclass")) {
                if (!row.next() || !row.getBoolean(1)) throw new IllegalStateException("guard must belong to migration owner");
            }
        }
        try (var connection = DriverManager.getConnection(url, "skillforge", password);
             var sql = connection.createStatement(); var row = sql.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
            if (!row.next() || row.getBoolean(1)) throw new IllegalStateException("restricted business role required");
        }
        Files.writeString(Path.of(required("SF_FIXTURE_BOOTSTRAP_REPORT")), new ObjectMapper().writeValueAsString(
                Map.of("status", "PASS", "schemaVersion", latest.getVersion(), "migrationsExecuted", migrated.migrationsExecuted,
                        "pending", 0, "businessRole", "skillforge", "businessRoleBypassesRls", false)));
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
