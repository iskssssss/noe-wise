import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.ValidateResult;

/** 明确副本数据库的一次性迁移入口；只允许 V118 校验和修复与正式 V262 接入。 */
public final class RuntimeCheckpointMigration {
    private static final String TARGET_VERSION = "262";
    private static String stage = "arguments";

    public static void main(String[] args) {
        try {
            require(args.length == 1 && !args[0].isBlank(), "target_database_required");
            String targetDatabase = args[0];
            String url = environment("RUNTIME_MIGRATION_JDBC_URL");
            String user = environment("RUNTIME_MIGRATION_DB_USER");
            String password = environment("RUNTIME_MIGRATION_DB_PASSWORD");
            require(databaseFromUrl(url).equals(targetDatabase), "jdbc_target_mismatch");

            stage = "preflight";
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("SELECT current_database()")) {
                    require(rows.next() && targetDatabase.equals(rows.getString(1)), "connected_target_mismatch");
                }
                try (ResultSet rows = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                    require(rows.next() && rows.getBoolean(1), "unfiltered_maintenance_role_required");
                }
                require(version(statement).equals("261"), "initial_version_must_be_261");
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM public.flyway_schema_history WHERE NOT success")) {
                    require(rows.next() && rows.getLong(1) == 0, "failed_migration_history");
                }
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM public.agent_runtime_session_preparations")) {
                    require(rows.next() && rows.getLong(1) == 0, "preparation_history_not_empty");
                }
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='agent_runtime_session_preparations' AND column_name='payload_data' AND data_type='bytea' AND is_nullable='NO' AND column_default IS NULL")) {
                    require(rows.next() && rows.getLong(1) == 1, "prepared_schema_required");
                }
            }

            // 禁用 Flyway 日志，URL、账号、口令和底层异常正文不进入输出。
            Flyway flyway = Flyway.configure().dataSource(url, user, password)
                    .locations("classpath:db/migration").target(TARGET_VERSION)
                    .cleanDisabled(true).loggers(new String[0]).load();
            for (MigrationInfo migration : flyway.info().all()) {
                if (migration.getVersion() != null) {
                    require(migration.getVersion().compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion(TARGET_VERSION)) <= 0,
                            "migration_above_262_in_classpath");
                }
            }

            stage = "validate_before_repair";
            ValidateResult validation = flyway.validateWithResult();
            int checksumChanges = 0;
            for (var invalid : validation.invalidMigrations) {
                String code = String.valueOf(invalid.errorDetails.errorCode);
                if ("118".equals(invalid.version) && "CHECKSUM_MISMATCH".equals(code)) {
                    checksumChanges++;
                } else {
                    // 正式 V262 尚未执行可以出现 pending；任何其他 validate 差异均阻断。
                    require("262".equals(invalid.version)
                                    && "RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED".equals(code),
                            "unexpected_validation_difference");
                }
            }
            require(checksumChanges == 1, "exactly_one_v118_checksum_change_required");
            require(flyway.info().pending().length == 1
                    && "262".equals(flyway.info().pending()[0].getVersion().getVersion()), "only_v262_may_be_pending");

            stage = "repair_v118";
            var repaired = flyway.repair();
            require(repaired.migrationsAligned.size() == 1
                    && "118".equals(repaired.migrationsAligned.get(0).version), "repair_must_only_align_v118");
            require(repaired.migrationsDeleted.isEmpty() && repaired.migrationsRemoved.isEmpty(),
                    "repair_must_not_remove_history");

            stage = "migrate_v262";
            var migrated = flyway.migrate();
            require(migrated.success && migrated.migrationsExecuted == 1
                    && TARGET_VERSION.equals(migrated.targetSchemaVersion), "exactly_v262_migration_required");
            stage = "validate_after_migrate";
            require(flyway.validateWithResult().validationSuccessful, "final_validation_failed");
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                require(version(statement).equals(TARGET_VERSION), "final_version_must_be_262");
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM public.flyway_schema_history WHERE NOT success")) {
                    require(rows.next() && rows.getLong(1) == 0, "final_failed_migration_history");
                }
                try (ResultSet rows = statement.executeQuery("SELECT version, checksum FROM public.flyway_schema_history WHERE success AND version IN ('118','262') ORDER BY installed_rank")) {
                    while (rows.next()) {
                        System.out.println("migration_receipt version=" + rows.getString(1) + " checksum=" + rows.getInt(2));
                    }
                }
            }
            System.out.println("migration_success initial_version=261 final_version=262 repaired_version=118 applied_version=262");
        } catch (Throwable failure) {
            String code = failure instanceof Rejected rejected ? rejected.code : "migration_operation_failed";
            System.err.println("migration_failure stage=" + stage + " code=" + code);
            System.err.println("failure_type=" + failure.getClass().getName());
            if (failure instanceof java.sql.SQLException sqlFailure) {
                System.err.println("sql_state=" + sqlFailure.getSQLState());
            }
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
                System.err.println("cause_type=" + cause.getClass().getName());
                for (int frame = 0; frame < Math.min(3, cause.getStackTrace().length); frame++) {
                    System.err.println("failure_frame=" + cause.getStackTrace()[frame]);
                }
            }
            System.exit(1);
        }
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank(), "required_environment_missing");
        return value;
    }

    private static String databaseFromUrl(String url) {
        require(url.startsWith("jdbc:postgresql://"), "unsupported_jdbc_url");
        URI uri = URI.create(url.substring("jdbc:".length()));
        require(uri.getHost() != null && uri.getRawUserInfo() == null, "unsupported_jdbc_authority");
        String path = Objects.requireNonNull(uri.getRawPath());
        require(path.startsWith("/") && path.length() > 1 && path.indexOf('/', 1) < 0, "explicit_url_database_required");
        return URLDecoder.decode(path.substring(1).replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String version(Statement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT version FROM public.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")) {
            require(rows.next(), "migration_history_empty");
            return rows.getString(1);
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new Rejected(code);
    }

    private static final class Rejected extends RuntimeException {
        final String code;
        Rejected(String code) { this.code = code; }
    }
}
