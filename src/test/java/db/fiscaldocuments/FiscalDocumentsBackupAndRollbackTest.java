package db.fiscaldocuments;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static db.fiscaldocuments.FiscalDocumentsMigrationsTest.BEFORE;
import static db.fiscaldocuments.FiscalDocumentsMigrationsTest.SCRATCH;
import static db.fiscaldocuments.FiscalDocumentsMigrationsTest.columns;
import static db.fiscaldocuments.FiscalDocumentsMigrationsTest.flyway;
import static db.fiscaldocuments.FiscalDocumentsMigrationsTest.jdbc;
import static db.fiscaldocuments.FiscalDocumentsMigrationsTest.tableExists;
import static db.fiscaldocuments.LegacyFixtures.doc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runbook's way back (spec 2026-11-27, FD-RN-52, 6.5, CA-FD-13, 26; CT-FD-23): {@code backup.sh} dumps the old
 * tables from the disposable MySQL container and checks the dump restored in a scratch schema; V86 to V89 run;
 * {@code parity.sql} compares the restored backup with the new tables (no divergent group); {@code rollback.sql}
 * restores the backup and the four old tables, {@code fiscal_xml_sync_control} and the Flyway history come back
 * identical ({@code CHECKSUM TABLE}, columns) and the previous image's Flyway validates. Also the way back after a failed
 * V88 (legacy tables still there).
 *
 * <p>Needs the disposable container of {@code scripts/test/test-mysql.sh} ({@code FD_MYSQL_CONTAINER}, e.g.
 * {@code taxify-test-fdmig}) for {@code mysqldump}/{@code mysql}, and a POSIX bash ({@code FD_BASH}, e.g. Git Bash).
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1")
@EnabledIfEnvironmentVariable(named = "FD_MYSQL_CONTAINER", matches = "taxify-test-.+")
class FiscalDocumentsBackupAndRollbackTest {

    private static final List<String> OLD_TABLES = List.of("fiscal_xml_documents", "fiscal_xml_document_items",
            "service_invoices", "fiscal_xml_events", "fiscal_xml_sync_control");
    private static final String CONTAINER = System.getenv("FD_MYSQL_CONTAINER");
    private static final Path SCRIPTS = Path.of("scripts", "fiscal-documents").toAbsolutePath();

    @TempDir
    Path out;

    private record Result(int exit, String output) {
    }

    @BeforeEach
    void createScratchDatabase() {
        JdbcTemplate admin = jdbc("");
        admin.execute("DROP DATABASE IF EXISTS " + SCRATCH);
        admin.execute("DROP DATABASE IF EXISTS fd_old");
        admin.execute("CREATE DATABASE " + SCRATCH + " CHARACTER SET utf8mb4");
    }

    @AfterEach
    void dropScratchDatabases() {
        jdbc("").execute("DROP DATABASE IF EXISTS " + SCRATCH);
        jdbc("").execute("DROP DATABASE IF EXISTS fd_old");
        jdbc("").execute("DROP DATABASE IF EXISTS fd_backup_check");
    }

    private static Result run(List<String> command, Map<String, String> env, File stdin) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(env);
        if (stdin != null) {
            builder.redirectInput(stdin);
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(5, TimeUnit.MINUTES)).isTrue();
        return new Result(process.exitValue(), output);
    }

    private Result backup() throws Exception {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DOCKER_CONTAINER", CONTAINER);
        env.put("DB_NAME", SCRATCH);
        env.put("DB_USER", "root");
        env.put("MYSQL_PWD", System.getenv().getOrDefault("DB_PASSWORD", "root"));
        env.put("OUT_DIR", out.toString().replace('\\', '/'));
        env.put("KEEP_CHECK_SCHEMA", "1");
        env.put("CHECK_SCHEMA", "fd_old");
        return run(List.of(System.getenv().getOrDefault("FD_BASH", "bash"),
                SCRIPTS.resolve("backup.sh").toString().replace('\\', '/')), env, null);
    }

    private Path dumpFile() throws IOException {
        try (var files = Files.list(out)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql")).findFirst().orElseThrow();
        }
    }

    private Result mysqlInContainer(String workdir, File stdin) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker", "exec", "-i", "-e", "MYSQL_PWD"));
        if (workdir != null) {
            command.addAll(List.of("-w", workdir));
        }
        command.addAll(List.of(CONTAINER, "mysql", "-uroot", "-N", SCRATCH));
        return run(command, Map.of("MYSQL_PWD", System.getenv().getOrDefault("DB_PASSWORD", "root")), stdin);
    }

    private Result rollback() throws Exception {
        assertThat(run(List.of("docker", "exec", CONTAINER, "mkdir", "-p", "/tmp/fd-rollback"), Map.of(), null).exit()).isZero();
        assertThat(run(List.of("docker", "cp", dumpFile().toString(), CONTAINER + ":/tmp/fd-rollback/fiscal-documents-backup.sql"),
                Map.of(), null).exit()).isZero();
        Result result = mysqlInContainer("/tmp/fd-rollback", SCRIPTS.resolve("rollback.sql").toFile());
        run(List.of("docker", "exec", CONTAINER, "rm", "-rf", "/tmp/fd-rollback"), Map.of(), null);
        return result;
    }

    private static Map<String, Object> snapshot(JdbcTemplate jdbc) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String table : OLD_TABLES) {
            snapshot.put(table + ".checksum", jdbc.queryForMap("CHECKSUM TABLE " + table).get("Checksum"));
            snapshot.put(table + ".rows", jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
            snapshot.put(table + ".columns", columns(jdbc, table));
        }
        snapshot.put("history", jdbc.queryForList("SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank"));
        return snapshot;
    }

    private static void seed(JdbcTemplate jdbc) {
        LegacyFixtures.scenario(jdbc);
        LegacyFixtures.addV85Columns(jdbc);
        LegacyFixtures.syncControls(jdbc);
    }

    // ------------------------------------------------------------------ CA-FD-13; CT-FD-23 (way back after V89, before step 6)

    @Test
    void backupParityAndRollbackAfterV89RestoreEverything() throws Exception {
        flyway(BEFORE).migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        seed(jdbc);
        Map<String, Object> before = snapshot(jdbc);

        Result backup = backup();
        assertThat(backup.exit()).as(backup.output()).isZero();
        assertThat(backup.output()).contains("BACKUP CONFERIDO").contains("fiscal_xml_documents: 13 linhas - OK")
                .contains("fiscal_xml_sync_control: 3 linhas - OK").doesNotContain(LegacyFixtures.E1_CNPJ);
        assertThat(Files.readString(dumpFile().resolveSibling(dumpFile().getFileName() + ".sha256"))).contains(dumpFile().getFileName().toString());

        flyway("latest").migrate();
        assertThat(tableExists(jdbc, "fiscal_xml_documents")).isFalse();

        Result parity = mysqlInContainer(null, SCRIPTS.resolve("parity.sql").toFile());
        assertThat(parity.exit()).as(parity.output()).isZero();
        assertThat(parity.output().replace("mysql: [Warning] Using a password on the command line interface can be insecure.", "").strip())
                .as("parity.sql lists no divergent group").isEmpty();
        // and it does see a difference: one cent less on K1, one item gone
        jdbc.update("UPDATE fiscal_documents SET total_amount = total_amount - 0.01 WHERE id = ?", doc("k1"));
        jdbc.update("DELETE FROM fiscal_document_items WHERE id = 'i1000000-0000-4000-8000-000000000002'");
        Result planted = mysqlInContainer(null, SCRIPTS.resolve("parity.sql").toFile());
        assertThat(planted.output()).contains("goods\t" + LegacyFixtures.E1 + "\t2026-10\tNFE\tINBOUND")
                .contains("items missing\t1").contains("items\t" + LegacyFixtures.E1 + "\t2026-10\t1102")
                .doesNotContain("1000.00").doesNotContain("999.99");

        Result rollback = rollback();
        assertThat(rollback.exit()).as(rollback.output()).isZero();

        assertThat(snapshot(jdbc)).isEqualTo(before);
        for (String table : FiscalDocumentTables.TABLES) {
            assertThat(tableExists(jdbc, table)).as(table).isFalse();
        }
        assertThat(columns(jdbc, "fiscal_xml_sync_control")).contains("total_sieg", "received_prev_month", "issued_earlier_gap");
        Flyway previousImage = flyway("latest", "classpath:db/migration");
        assertThat(previousImage.validateWithResult().validationSuccessful).isTrue();
        assertThat(previousImage.migrate().migrationsExecuted).isZero();
    }

    /** CT-FD-34 + 6.5 item 0: V88 failed (legacy_* still there); the script renames them back and restores the backup. */
    @Test
    void rollbackAfterAFailedParity() throws Exception {
        flyway(BEFORE).migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        seed(jdbc);
        Map<String, Object> before = snapshot(jdbc);
        assertThat(backup().exit()).isZero();
        flyway("87").migrate();
        jdbc.update("UPDATE fiscal_documents SET total_amount = 3999.99 WHERE id = ?", doc("k1"));
        assertThatThrownBy(() -> flyway("latest").migrate()).hasRootCauseMessage(
                "fiscal documents parity failed in [goods] - see fiscal.documents.cutover.parity.mismatch; V89 will not run"
                        + " and the legacy_* tables are intact");
        assertThat(tableExists(jdbc, "legacy_fiscal_xml_documents")).isTrue();

        Result rollback = rollback();
        assertThat(rollback.exit()).as(rollback.output()).isZero();

        assertThat(snapshot(jdbc)).isEqualTo(before);
        for (LegacyTable table : LegacyTable.values()) {
            assertThat(tableExists(jdbc, table.legacyName())).isFalse();
        }
        for (String table : FiscalDocumentTables.TABLES) {
            assertThat(tableExists(jdbc, table)).as(table).isFalse();
        }
    }
}
